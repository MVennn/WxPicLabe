package io.github.MVennn.WxPicLabel.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
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
        const val AUTHORITY = "io.github.MVennn.WxPicLabel.bridge"
        const val PATH_FRIENDS = "friends"
        /** 发送记录（同名图片计数表）回传通道。 */
        const val PATH_RECORDS = "records"
        const val COL_CHUNK = "chunk"
        const val COL_INDEX = "index"
        const val COL_TOTAL = "total"
        const val COL_JSON = "friends_json"
        const val COL_FETCHED_AT = "fetched_at"
        const val COL_COUNT = "count"

        /** 每批条数上限：Binder 事务 ~1MB，留足余量。 */
        const val CHUNK_SIZE = 200

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
                    "{\"fetchedAt\":$now,\"friends\":[$sb]}"
                } else {
                    sb.toString()
                }
                val e = store.edit()
                    .putString(targetKey, json)
                    .putLong(stampKey, now)
                for (i in 0 until total) {
                    e.remove(pendingPrefix + i)
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

    data class Book(
        val at: Long,
        val edited: String,
        val rows: List<Row>,
        val events: List<Event>,
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
        return try {
            val root = JSONObject(json)
            val edited = root.optString("edited", "")
            val rowsArr = root.optJSONArray("records") ?: JSONArray()
            val rows = ArrayList<Row>(rowsArr.length())
            for (i in 0 until rowsArr.length()) {
                val o = rowsArr.optJSONObject(i) ?: continue
                val label = o.optString("label", "")
                if (label.isEmpty()) continue
                rows.add(Row(label, o.optInt("count", 0), o.optLong("last", 0L)))
            }
            val evArr = root.optJSONArray("events")
            val events = ArrayList<Event>()
            if (evArr != null) {
                for (i in 0 until evArr.length()) {
                    val o = evArr.optJSONObject(i) ?: continue
                    val label = o.optString("l", "")
                    if (label.isEmpty()) continue
                    events.add(
                        Event(
                            seq = o.optLong("s", 0L),
                            at = o.optLong("t", 0L),
                            label = label,
                            n = o.optInt("n", 0),
                            batch = o.optString("b", ""),
                        )
                    )
                }
            }
            Book(at, edited, rows, if (events.isEmpty()) synth(rows) else events)
        } catch (t: Throwable) {
            Book(0L, "", emptyList(), emptyList())
        }
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
