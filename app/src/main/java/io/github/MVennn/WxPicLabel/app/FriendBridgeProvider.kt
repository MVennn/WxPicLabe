package io.github.MVennn.WxPicLabel.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import io.github.MVennn.WxPicLabel.core.BridgeProtocol
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * 好友名单回传通道：**微信进程 → 模块 App**。
 *
 * remote preferences 的方向是反的（目标进程侧 edit() 直接抛
 * `UnsupportedOperationException("Read only implementation")`，上游 javadoc 原文
 * "read-only in hooked apps"），所以名单只能走这条 Provider。
 *
 * 协议（`insert content://<pkg>.bridge/<path>`）：
 *
 *  - `chunk`：一段 JSON 文本片段
 *  - `index` / `total`：分片序号与总数（避开 Binder ~1MB 上限）
 *  - 收到 `index == total - 1` 时按序拼接，全量落盘
 *
 * 两条路径的片段**形态不同**：
 *
 *  - `friends`：片段是「条目 + 尾逗号」，Provider 套上
 *    `{"fetchedAt":..,"friends":[ .. ]}` 外壳，每片 ≤200 条。
 *  - `records`：片段是**整份文档的字符切片**（发送记录既要聚合计数表
 *    又要逐次台账，由 `RecordExporter` 一次拼好），Provider 只按序拼接、
 *    **不加任何外壳** —— 它不需要懂 JSON 结构，以后换字段也不用改这里。
 *
 * 安全：exported 且**不声明** android:permission —— 微信没有本模块签名，
 * signature 级自定义权限会把微信直接挡死（[DebugConfigProvider] 的注释记录过同一个坑）。
 * 访问控制收敛为运行时 UID 白名单：本应用 / root / shell / `com.tencent.mm`。
 *
 * 名单数据全部来自**当前登录账号**的运行时抓取，这里不做任何缓存淘汰 ——
 * 每次回传都是全量覆盖。
 */
class FriendBridgeProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = BridgeProtocol.AUTHORITY
        const val PATH_FRIENDS = BridgeProtocol.PATH_FRIENDS
        /** 发送记录（同名图片计数表）回传通道。 */
        const val PATH_RECORDS = BridgeProtocol.PATH_RECORDS
        /** 只读探针（见 [BridgeProtocol.PATH_STATUS]）。 */
        const val PATH_STATUS = BridgeProtocol.PATH_STATUS
        const val SCHEMA = BridgeProtocol.SCHEMA
        const val COL_CHUNK = "chunk"
        const val COL_INDEX = "index"
        const val COL_TOTAL = "total"
        const val COL_JSON = "friends_json"
        const val COL_FETCHED_AT = "fetched_at"
        const val COL_COUNT = "count"

        // /status 的列名。**只增不改**：老调用方查 friends 路径拿的还是上面三列，
        // 新调用方查 status 路径看到 schema 列才算「对端有探针能力」。
        const val COL_SCHEMA = "schema"
        const val COL_MODULE = "module"
        const val COL_AT = "at"
        const val COL_FEATURES = "features"
        const val COL_LAST_RECORDS_AT = "last_records_at"
        const val COL_LAST_FRIENDS_AT = "last_friends_at"
        const val COL_RECORDS_CHARS = "records_chars"
        const val COL_FRIENDS_COUNT = "friends_count"

        /** 每批条数上限：Binder 事务 ~1MB，留足余量。 */
        const val CHUNK_SIZE = 200

        /**
         * 一次回传允许的最大分片数 / 单片字符数。
         *
         * 白名单里的调用方（本应用 / root / shell / 微信）本意都是「一批选图或一次好友同步」，
         * 但协议里 total 是调用方说了算：不设上限时一句
         * `content insert --bind total:i:100000000` 就能让末片的拼装循环把模块 App 进程撑爆。
         * 512 片（按 200 条一片算已是十万级名单）远超真实用量，撞到上限就整批拒收。
         */
        private const val MAX_CHUNKS = 512
        private const val MAX_CHUNK_CHARS = 512 * 1024

        private const val PREFS = "wxpiclabel_friends"
        private const val KEY_JSON = "friends_json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_RECORDS_JSON = "records_json"
        private const val KEY_RECORDS_AT = "records_at"
        /** 分片键前缀：好友与记录共用同一个 prefs 文件，靠前缀分开。 */
        private const val PENDING_FRIENDS = "pending_"
        private const val PENDING_RECORDS = "pending_r_"
    }

    override fun onCreate(): Boolean = true

    /** 调用方白名单：本应用 / root / shell / 微信，其余一律拒绝。 */
    private fun ensureAllowed() {
        val uid = Binder.getCallingUid()
        if (uid == 0 || uid == Process.SHELL_UID || uid == Process.myUid()) {
            return
        }
        val wxUid = try {
            context!!.packageManager.getApplicationInfo("com.tencent.mm", 0).uid
        } catch (t: Throwable) {
            -1
        }
        if (uid == wxUid) {
            return
        }
        throw SecurityException("bridge: uid $uid not allowed")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        ensureAllowed()
        if (values == null) {
            return null
        }
        val isFriends = uri.lastPathSegment == PATH_FRIENDS
        if (!isFriends && uri.lastPathSegment != PATH_RECORDS) {
            return null
        }
        val pendingPrefix = if (isFriends) PENDING_FRIENDS else PENDING_RECORDS
        val targetKey = if (isFriends) KEY_JSON else KEY_RECORDS_JSON
        val stampKey = if (isFriends) KEY_FETCHED_AT else KEY_RECORDS_AT
        val chunk = values.getAsString(COL_CHUNK) ?: return null
        val index = values.getAsInteger(COL_INDEX) ?: return null
        val total = values.getAsInteger(COL_TOTAL) ?: return null
        if (total <= 0 || index < 0 || index >= total) {
            return null
        }
        // 规模闸门放在落盘之前：拒收就是一行 null，不留下半份分片
        if (total > MAX_CHUNKS || chunk.length > MAX_CHUNK_CHARS) {
            return null
        }
        val store = store()
        synchronized(store) {
            // 每个分片按 index 落位；最后一片到达时按序拼接成完整 JSON 全量覆盖
            store.edit().putString(pendingPrefix + index, chunk).commit()
            if (index == total - 1) {
                val sb = StringBuilder()
                for (i in 0 until total) {
                    sb.append(store.getString(pendingPrefix + i, ""))
                }
                val now = System.currentTimeMillis()
                // friends 由这里套外壳；records 的片段本身就是完整文档，原样拼接
                val json = if (isFriends) {
                    // 信封带上 schema：读侧（FriendStore）忽略未知键，但排障与以后的新调用方
                    // 能一眼看出这份快照是哪一版写出来的
                    "{\"v\":$SCHEMA,\"fetchedAt\":$now,\"friends\":[$sb]}"
                } else {
                    sb.toString()
                }
                val e = store.edit()
                    .putString(targetKey, json)
                    .putLong(stampKey, now)
                // 按前缀清全部分片，不是只清 0..total-1：上一批如果更长（比如 6 片），
                // 这一批只有 3 片时 3..5 号分片会永远留在盘上，下次拼装照样把它们带上。
                for (key in store.all.keys) {
                    if (key.startsWith(pendingPrefix)) {
                        e.remove(key)
                    }
                }
                e.commit()
            }
        }
        return uri
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        ensureAllowed()
        if (uri.lastPathSegment == PATH_STATUS) {
            return statusCursor()
        }
        val book = FriendStore.read(context!!)
        val json = if (book.entries.isEmpty()) "" else {
            val arr = JSONArray()
            for (e in book.entries) {
                arr.put(JSONObject().put("id", e.id).put("name", e.name).put("kind", e.kind))
            }
            JSONObject().put("fetchedAt", book.fetchedAt).put("friends", arr).toString()
        }
        return MatrixCursor(arrayOf(COL_JSON, COL_FETCHED_AT, COL_COUNT)).apply {
            addRow(arrayOf<Any>(json, book.fetchedAt, book.entries.size))
        }
    }

    /**
     * 只读探针：一次查询回答「对端是哪一版、支持到什么程度、上次回传是什么时候」。
     *
     * 全部取自本机已有的落盘状态（prefs 里的快照 + 配置开关），不触发任何抓取，
     * 所以在微信没跑、模块没挂上时也能查 —— 这正是它作为排障入口的价值。
     */
    private fun statusCursor(): Cursor {
        val sp = store()
        val recordsJson = sp.getString(KEY_RECORDS_JSON, "") ?: ""
        val friendsJson = sp.getString(KEY_JSON, "") ?: ""
        val moduleVersion = try {
            context!!.packageManager.getPackageInfo(context!!.packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
        val features = JSONObject()
            .put(ConfigKeys.ENABLED,
                ModulePreferences.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED))
            .put(ConfigKeys.IMG_LABEL,
                ModulePreferences.getBoolean(ConfigKeys.IMG_LABEL, ConfigKeys.DEFAULT_IMG_LABEL))
            .toString()
        return MatrixCursor(
            arrayOf(
                COL_SCHEMA, COL_MODULE, COL_AT, COL_FEATURES,
                COL_LAST_RECORDS_AT, COL_LAST_FRIENDS_AT, COL_RECORDS_CHARS, COL_FRIENDS_COUNT,
            ),
        ).apply {
            addRow(
                arrayOf<Any>(
                    SCHEMA,
                    moduleVersion,
                    System.currentTimeMillis(),
                    features,
                    sp.getLong(KEY_RECORDS_AT, 0L),
                    sp.getLong(KEY_FETCHED_AT, 0L),
                    recordsJson.length,
                    friendCount(friendsJson),
                ),
            )
        }
    }

    /** 好友条数：从快照里数数组长度，不重新解析成 [FriendStore.Entry]。 */
    private fun friendCount(json: String): Int {
        if (json.isEmpty()) {
            return 0
        }
        return try {
            JSONObject(json).optJSONArray("friends")?.length() ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    private fun store() = context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null
}

/**
 * 模块 App 侧的名单读取口（UI 只认它，不直接碰 Provider）。
 *
 * 数据落在本机 `SharedPreferences("wxpiclabel_friends")` —— 名单是**微信进程
 * 上报的运行时快照**，不属于用户配置，所以不走 remote preferences、不进备份。
 */
object FriendStore {

    /** 一条候选会话：`kind` 0 = 好友，1 = 群聊。 */
    data class Entry(val id: String, val name: String, val kind: Int)

    data class Book(val fetchedAt: Long, val entries: List<Entry>)

    fun read(context: Context): Book {
        val sp = context.getSharedPreferences("wxpiclabel_friends", Context.MODE_PRIVATE)
        val json = sp.getString("friends_json", "") ?: ""
        val fetchedAt = sp.getLong("fetched_at", 0L)
        if (json.isEmpty()) {
            return Book(0L, emptyList())
        }
        return try {
            val arr = JSONObject(json).optJSONArray("friends") ?: JSONArray()
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                out.add(Entry(id, o.optString("name", id), o.optInt("kind", 0)))
            }
            Book(fetchedAt, out)
        } catch (t: Throwable) {
            Book(0L, emptyList())
        }
    }
}

/**
 * 模块 App 侧的**发送记录**读取口。
 *
 * 与 [FriendStore] 同一套做法：微信进程把整份文档（聚合计数表 + 逐次台账）经
 * Provider 回传，这里只读本机快照。记录不是用户配置，所以不进 remote preferences、
 * 不进备份。
 *
 * ## 为什么还要聚合计数表
 *
 * 台账只留最近 600 条（按时间淘汰），聚合表才是「这张图一共发过几次」。
 * 概览行的总条数也从台账数，两边口径不同、都要保留。
 */
object RecordStore {

    /**
     * 旧版记录用的「批次」占位值，与 RepeatCounter.BATCH_MIGRATED 同一个字面量。
     *
     * 迁移条目和 app 侧合成条目都带它：**它不是真实批次**，界面分组时必须当成
     * 「没有批次」按时间窗兜底，否则整段历史会挤成一组（看着就像没有分组）。
     */
    const val BATCH_MIGRATED = "m"

    /** 一条聚合记录：发出去的文字、发过几次、最后一次的毫秒时间戳。 */
    data class Row(val label: String, val count: Int, val lastMs: Long)

    /**
     * 一条逐次发送事件。
     *
     * [seq] 为 0 表示这条是从旧版聚合表现拼出来的（老版本回传的文档没有台账），
     * 台账里没有对应条目，改不了也删不掉 —— 界面按只读处理（[editable]）。
     */
    data class Event(
        val seq: Long,
        val at: Long,
        val label: String,
        val n: Int,
        val batch: String,
    ) {
        val editable: Boolean get() = seq > 0L
    }

    /**
     * 一次回传快照。
     *
     * [chars] / [error] / [hasEvents] 是**诊断字段**：记录页拿不到数据时，界面要能
     * 直接说出「一条都没收到」还是「收到了但解析炸了」还是「收到的是旧版文档」，
     * 而不是只给一个空白列表让人猜。
     */
    data class Book(
        val at: Long,
        val edited: String,
        val rows: List<Row>,
        val events: List<Event>,
        /** 收到的 JSON 字符数，0 = 模块 App 这边根本没有 records_json。 */
        val chars: Int = 0,
        /** 解析失败原因；正常为 null。 */
        val error: String? = null,
        /** 文档里带没带 events 字段：false + 有 rows = 老版本模块只回传了聚合表。 */
        val hasEvents: Boolean = false,
        /** 顶层 records 数组里到底有几个元素（可能全被过滤掉）。 */
        val rawRows: Int = 0,
        /** 顶层 events 数组里到底有几个元素。 */
        val rawEvents: Int = 0,
        /** 顶层键名；解析不出来时写日志（界面上早已没有诊断行，见 docs/IMAGE-RECORD.md）。 */
        val topKeys: String = "",
        /** 读到它的路径：strict / lenient / salvage / empty / unsupported。 */
        val path: String = "strict",
        /**
         * 文档里声明的载荷版本（顶层 `"v"`，缺省 1 = 老版本没有版本号）。
         *
         * 大于 [BridgeProtocol.SCHEMA] 说明写这份文档的模块比本 App 还新：
         * 结构可能有新字段，解析走 lenient，[path] 标 `unsupported`。
         */
        val schema: Int = 1,
    ) {
        val isEmpty: Boolean get() = events.isEmpty() && rows.isEmpty()
    }

    fun read(context: Context): Book {
        val sp = context.getSharedPreferences("wxpiclabel_friends", Context.MODE_PRIVATE)
        val json = sp.getString("records_json", "") ?: ""
        val at = sp.getLong("records_at", 0L)
        if (json.isEmpty()) {
            return Book(0L, "", emptyList(), emptyList())
        }
        // 三层读取。第一层按当前格式严格读；第二层换键名读（老版本分支写出来的文档
        // 字段名不一样）；第三层直接在我们自己写出去的两种 JSON 形状上做正则回捞 ——
        // 文档被截断、分片没拼全、整体不是合法 JSON 时，这是唯一还能把条目救回来的路。
        var parseError: String? = null
        var topKeys = ""
        var rawRows = 0
        var rawEvents = 0
        var edited = ""
        var rows: List<Row> = emptyList()
        var events: List<Event> = emptyList()
        var path = "strict"
        var declaredSchema = 1
        try {
            val root = JSONObject(json)
            declaredSchema = root.optInt("v", 1)
            topKeys = keysOf(root)
            edited = root.optString("edited", "")
            val rowsArr = arrayOf(root, "records", "rows")
            val evArr = arrayOf(root, "events", "event", "log")
            rawRows = rowsArr?.length() ?: 0
            rawEvents = evArr?.length() ?: 0
            rows = parseRows(rowsArr)
            events = parseEvents(evArr)
            if (rows.isEmpty() && events.isEmpty() && (rawRows > 0 || rawEvents > 0)) {
                path = "lenient"
            }
        } catch (t: Throwable) {
            parseError = t.toString()
        }
        if (rows.isEmpty() && events.isEmpty()) {
            val sRows = salvageRows(json)
            val sEvents = salvageEvents(json)
            if (sRows.isNotEmpty() || sEvents.isNotEmpty()) {
                rows = sRows
                events = sEvents
                if (edited.isEmpty()) {
                    edited = RE_EDITED.find(json)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
                }
                path = "salvage"
            } else {
                path = "empty"
            }
        }
        // 对端比我们新：能解析多少算多少，但必须让调用方看见「这是新版本写的文档」
        if (declaredSchema > BridgeProtocol.SCHEMA) {
            path = "unsupported"
        }
        val hasEv = events.isNotEmpty()
        val parsedAny = rows.isNotEmpty() || events.isNotEmpty()
        return Book(
            at = at,
            edited = edited,
            rows = rows,
            events = if (hasEv) events else synth(rows),
            chars = json.length,
            error = if (parsedAny) null else parseError,
            hasEvents = hasEv,
            rawRows = rawRows,
            rawEvents = rawEvents,
            topKeys = topKeys,
            path = path,
            schema = declaredSchema,
        )
    }

    /** 顶层键（截断到 120 字）：结构对不上时界面直接把这一串摆出来。 */
    private fun keysOf(o: JSONObject): String {
        val it = o.keys()
        val sb = StringBuilder()
        while (it.hasNext()) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(it.next())
            if (sb.length > 120) break
        }
        return sb.toString()
    }

    /** 依次尝试几个可能的键名，返回第一个是数组的。 */
    private fun arrayOf(root: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            val a = root.optJSONArray(k)
            if (a != null) return a
        }
        return null
    }

    private fun pickStr(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    private fun pickLong(o: JSONObject, vararg keys: String): Long {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.optLong(k, Long.MIN_VALUE)
            if (v != Long.MIN_VALUE) return v
        }
        return 0L
    }

    /** 聚合表：元素可以是对象（label/count/last 及常见别名），也可以是三元数组。 */
    private fun parseRows(arr: JSONArray?): List<Row> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<Row>(arr.length())
        for (i in 0 until arr.length()) {
            val label: String
            var count = 0
            var last = 0L
            val o = arr.optJSONObject(i)
            if (o != null) {
                label = pickStr(o, "label", "name", "l", "text")
                count = pickLong(o, "count", "n", "c", "times").toInt()
                last = pickLong(o, "last", "t", "at", "time")
            } else {
                val a = arr.optJSONArray(i) ?: continue
                label = a.optString(0, "")
                count = a.optInt(1, 0)
                last = a.optLong(2, 0L)
            }
            if (label.isEmpty()) continue
            out.add(Row(label, count, last))
        }
        return out
    }

    /** 台账：元素字段名同样容忍别名。 */
    private fun parseEvents(arr: JSONArray?): List<Event> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val label: String
            var seq = 0L
            var at = 0L
            var n = 0
            var batch = ""
            val o = arr.optJSONObject(i)
            if (o != null) {
                label = pickStr(o, "l", "label", "name", "text")
                seq = pickLong(o, "s", "seq", "id")
                at = pickLong(o, "t", "at", "time", "ts")
                n = pickLong(o, "n", "c", "count", "nth").toInt()
                batch = pickStr(o, "b", "batch", "g")
            } else {
                val a = arr.optJSONArray(i) ?: continue
                seq = a.optLong(0, 0L)
                at = a.optLong(1, 0L)
                label = a.optString(2, "")
                n = a.optInt(3, 0)
                batch = a.optString(4, "")
            }
            if (label.isEmpty()) continue
            out.add(Event(seq, at, label, n, batch))
        }
        return out
    }

    /** 按 {"label":"…","count":N,"last":T} 的形状从原文里捞聚合表。 */
    private fun salvageRows(json: String): List<Row> {
        val out = ArrayList<Row>()
        for (m in RE_ROW.findAll(json)) {
            val label = unescape(m.groupValues[1])
            if (label.isEmpty()) continue
            out.add(Row(label, m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toLongOrNull() ?: 0L))
        }
        return out
    }

    /** 按 {"s":…,"t":…,"l":"…","n":…,"b":"…"} 的形状从原文里捞台账。 */
    private fun salvageEvents(json: String): List<Event> {
        val out = ArrayList<Event>()
        for (m in RE_EV.findAll(json)) {
            val label = unescape(m.groupValues[3])
            if (label.isEmpty()) continue
            out.add(
                Event(
                    seq = m.groupValues[1].toLongOrNull() ?: 0L,
                    at = m.groupValues[2].toLongOrNull() ?: 0L,
                    label = label,
                    n = m.groupValues[4].toIntOrNull() ?: 0,
                    batch = unescape(m.groupValues[5]),
                )
            )
        }
        return out
    }

    private val RE_ROW = Regex("""\{"label":"((?:[^"\\]|\\.)*)","count":(-?\d+),"last":(-?\d+)\}""")
    private val RE_EV = Regex("""\{"s":(-?\d+),"t":(-?\d+),"l":"((?:[^"\\]|\\.)*)","n":(-?\d+),"b":"((?:[^"\\]|\\.)*)"\}""")
    private val RE_EDITED = Regex("\"edited\":\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** JSON 字符串里的转义还原（只处理我们自己会写出去的那几种）。 */
    private fun unescape(raw: String): String {
        if (raw.indexOf('\\') < 0) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                sb.append(ch)
                i++
                continue
            }
            when (val n = raw[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val code = if (i + 5 < raw.length) raw.substring(i + 2, i + 6).toIntOrNull(16) else null
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }
    /** 老文档没有台账：按聚合表拼出占位事件，至少让界面有东西可看（不可编辑）。 */
    private fun synth(rows: List<Row>): List<Event> {
        val out = ArrayList<Event>()
        for (r in rows) {
            for (i in 1..r.count.coerceAtLeast(0)) {
                out.add(Event(0L, r.lastMs, r.label, i, BATCH_MIGRATED))
            }
        }
        out.sortWith(compareBy({ it.at }, { it.label }, { it.n }))
        return out
    }
}

/**
 * 「发送记录」的手工修改：改名字 / 改第几次 / 删除。
 *
 * ## 为什么是单向动作、不是双向同步
 *
 * 台账在微信进程里，模块 App 写不进去；跨进程只有 remote preferences 这一条
 * 「模块 App 写 → 微信进程读」的路，目标进程侧的 `edit()` 直接抛异常。
 * 所以这里只存**动作串**，微信进程轮询到令牌变了就把整组动作重新应用一遍
 * （`RepeatCounter.applyEdits`，幂等）。
 *
 * 动作语法（竖线分隔，都是短串）：
 *
 *  - `d|<seq>`：删掉这条事件
 *  - `l|<seq>|<新名字>`：改名字
 *  - `n|<seq>|<次数>`：改「第几次」
 *
 * ## 同一 seq 只留一条动作
 *
 * 先把同一 seq 的旧动作剔掉再追加 —— StringSet 没有顺序，否则「改名 A」和
 * 「改名 B」谁生效全看哈希顺序。
 *
 * ## 什么时候能丢
 *
 * 动作应用过之后留着是无害的空转（幂等），但集合会慢慢涨。超过 [MAX_OPS] 条时
 * 清理「seq 已经不在快照里」的动作：那种效果已经落进台账，再发也改不动什么了。
 */
object RecordEdits {

    private const val PREFS = "wxpiclabel_record_edits"
    private const val KEY_OPS = "ops"

    /** 动作条数上限；超过就清掉一批已生效的。 */
    private const val MAX_OPS = 200

    fun read(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_OPS, emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    /** 追加一条动作（同 seq 覆盖），并推给微信进程。返回写入后的全集。 */
    fun commit(context: Context, op: String): Set<String> {
        val seq = op.split('|').getOrNull(1)
        val next = HashSet<String>()
        for (cur in read(context)) {
            if (seq != null && cur.split('|').getOrNull(1) == seq) {
                continue
            }
            next.add(cur)
        }
        next.add(op)
        return write(context, next)
    }

    /** 清空全部动作（「清空发送记录」时用；台账本身另走 RESET 令牌清）。 */
    fun clear(context: Context): Set<String> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        return write(context, emptySet())
    }

    /** 把动作套在快照上，供界面先看结果；口径与 `RepeatCounter.applyEdits` 一致。 */
    fun apply(events: List<RecordStore.Event>, ops: Set<String>): List<RecordStore.Event> {
        if (ops.isEmpty()) return events
        val drop = HashSet<Long>()
        val rename = HashMap<Long, String>()
        val nth = HashMap<Long, Int>()
        for (op in ops) {
            val p = op.split('|')
            if (p.size < 2) continue
            val seq = p[1].toLongOrNull() ?: continue
            when (p[0]) {
                "d" -> drop.add(seq)
                "l" -> if (p.size >= 3 && p[2].isNotEmpty()) rename[seq] = p[2]
                "n" -> if (p.size >= 3) p[2].toIntOrNull()?.let { nth[seq] = it }
            }
        }
        if (drop.isEmpty() && rename.isEmpty() && nth.isEmpty()) return events
        val out = ArrayList<RecordStore.Event>(events.size)
        for (ev in events) {
            if (drop.contains(ev.seq)) continue
            val label = rename[ev.seq] ?: ev.label
            val n = nth[ev.seq] ?: ev.n
            out.add(if (label == ev.label && n == ev.n) ev else ev.copy(label = label, n = n))
        }
        return out
    }

    /** 动作太多时丢掉「seq 已不在快照里」的那些（效果已经落在台账上）。 */
    fun trim(context: Context, liveSeqs: Set<Long>) {
        val ops = read(context)
        if (ops.size <= MAX_OPS) return
        val keep = HashSet<String>()
        for (op in ops) {
            val seq = op.split('|').getOrNull(1)?.toLongOrNull()
            if (seq == null || liveSeqs.contains(seq)) keep.add(op)
        }
        if (keep.size != ops.size) write(context, keep)
    }

    /** 落本机副本 + 推给微信进程（写动作集合，再换令牌）。 */
    private fun write(context: Context, ops: Set<String>): Set<String> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_OPS, ops)
            .apply()
        ModulePreferences.edit {
            putStringSet(ConfigKeys.IMG_LABEL_RECORD_EDITS, ops)
            putString(ConfigKeys.IMG_LABEL_RECORD_EDITS_TOKEN, System.currentTimeMillis().toString())
        }
        return ops
    }
}
