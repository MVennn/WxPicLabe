package io.github.MVennn.WxPicLabel.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * 好友名单回传通道：**微信进程 → 模块 App**。
 *
 * remote preferences 的方向是反的（目标进程侧 edit() 直接抛
 * `UnsupportedOperationException("Read only implementation")`，上游 javadoc 原文
 * "read-only in hooked apps"），所以名单只能走这条 Provider。
 *
 * 协议（`insert content://<pkg>.bridge/friends`）：
 *
 *  - `chunk`：一批条目的 JSON 片段（`{"id":..,"name":..,"kind":0},` 尾带逗号）
 *  - `index` / `total`：分片序号与总数（每批 ≤200 条，避开 Binder ~1MB 上限）
 *  - 收到 `index == total - 1` 时按序拼接完整 JSON 全量落盘
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
        if (values == null || uri.lastPathSegment != PATH_FRIENDS) {
            return null
        }
        val chunk = values.getAsString(COL_CHUNK) ?: return null
        val index = values.getAsInteger(COL_INDEX) ?: return null
        val total = values.getAsInteger(COL_TOTAL) ?: return null
        if (total <= 0 || index < 0 || index >= total) {
            return null
        }
        val store = store()
        synchronized(store) {
            // 每个分片按 index 落位；最后一片到达时按序拼接成完整 JSON 全量覆盖
            store.edit().putString("pending_$index", chunk).commit()
            if (index == total - 1) {
                val sb = StringBuilder()
                for (i in 0 until total) {
                    sb.append(store.getString("pending_$i", ""))
                }
                val now = System.currentTimeMillis()
                val json = "{\"fetchedAt\":$now,\"friends\":[$sb]}"
                val e = store.edit()
                    .putString(KEY_JSON, json)
                    .putLong(KEY_FETCHED_AT, now)
                for (i in 0 until total) {
                    e.remove("pending_$i")
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
