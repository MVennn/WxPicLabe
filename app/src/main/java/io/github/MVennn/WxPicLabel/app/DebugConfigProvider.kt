package io.github.MVennn.WxPicLabel.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import io.github.MVennn.WxPicLabel.core.ConfigKeys

/**
 * adb 调试通道（**仅供开发期自动化，不参与运行期配置读取**）。
 *
 * 运行期配置走 libxposed 的 remote preferences，这个 Provider 存在的唯一理由
 * 是让脚本能不改一行代码就读写配置。authority 在 AndroidManifest.xml 里声明为
 * `io.github.MVennn.WxPicLabel.config`：
 *
 * ```bash
 * adb shell content query  --uri content://io.github.MVennn.WxPicLabel.config/config
 * adb shell content update --uri content://io.github.MVennn.WxPicLabel.config/config --bind img_label:i:0
 * ```
 *
 * 写入一律经由 [ModulePreferences]，所以框架已连接时写的就是远端值，
 * 目标进程立刻可见 —— 等价于在设置界面里点了一下。
 *
 * 安全：只接受 root(0) / shell(2000) 调用，其它 UID 一律 [SecurityException]。
 * 单层 UID 白名单是有意设计（不加 signature permission 的原因），详见 [ensurePrivileged]。
 */
class DebugConfigProvider : ContentProvider() {

    companion object {
        const val COL_ENABLED = "enabled"
        const val COL_LOG_LEVEL = "log_level"
        const val COL_BACKEND = "backend"
        const val COL_RAW = "raw"

        // 功能开关：开发期自动化需要可脚本化地开关，否则每次验证都要手工点一遍界面。
        const val COL_IMG_LABEL = "img_label"
        const val COL_IMG_LABEL_REPEAT = "img_label_repeat_count"
        const val COL_IMG_LABEL_RESET = "img_label_repeat_reset"

        // 只读诊断列：会话白名单与记录编辑令牌以前只能靠 raw 那一列自己肉眼切分，
        // 脚本对不上「哪个会话为什么没生效」时就只能猜。只出现在 query 里，update 不认。
        const val COL_IMG_LABEL_SCOPE_ONLY = "img_label_scope_only"
        const val COL_IMG_LABEL_SCOPE_IDS = "img_label_scope_ids"
        const val COL_IMG_LABEL_RECORD_EDITS = "img_label_record_edits"
    }

    override fun onCreate(): Boolean {
        context?.let { ModulePreferences.init(it) }
        return true
    }

    /**
     * 访问控制：单层 UID 白名单（root=0 / shell=2000），是有意设计。
     *
     * 防线模型：
     *  - Provider 本身 exported=true 且**不声明** android:permission ——
     *    自定义 permission（无论 normal/signature）都会把 adb shell（uid 2000，
     *    不具备本应用 signature）也挡掉，破坏 `adb shell content query/update`
     *    调试通道；manifest 层的攻击面由 lint 例外（tools:ignore）显式承认。
     *  - 唯一的防线就是这里的运行时 UID 白名单：query / update 入口都先过本方法，
     *    其它 UID（含普通应用）一律 SecurityException。
     *  - 暴露面收敛为「root / shell 可读写本模块配置」—— 与 adb 本身的能力
     *    等价（adb shell 本就能 run-as / 读写 debuggable 应用数据），不额外放大风险。
     *  - 该 Provider 仅供开发期自动化，不参与运行期配置读取；不再需要 adb
     *    调试时可直接整体下线。
     */
    private fun ensurePrivileged() {
        val uid = Binder.getCallingUid()
        if (uid != 0 && uid != Process.SHELL_UID) {
            throw SecurityException("调试通道仅允许 root/shell 访问 (uid=$uid)")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        ensurePrivileged()

        val raw = ModulePreferences.all().entries.joinToString(";") { "${it.key}=${it.value}" }

        // ⚠️ **列与值必须同源**：`MatrixCursor.addRow` 要求两者长度严格相等，
        // 分开手写两遍必然出现「补了列忘了补值」。下面一份表出两处。
        val columns = arrayOf(
            COL_ENABLED, COL_LOG_LEVEL, COL_BACKEND, COL_RAW,
            COL_IMG_LABEL, COL_IMG_LABEL_REPEAT, COL_IMG_LABEL_RESET,
            COL_IMG_LABEL_SCOPE_ONLY, COL_IMG_LABEL_SCOPE_IDS, COL_IMG_LABEL_RECORD_EDITS,
        )
        val values = arrayOf<Any>(
            if (ModulePreferences.getBoolean(ConfigKeys.ENABLED, ConfigKeys.DEFAULT_ENABLED)) 1 else 0,
            ModulePreferences.getInt(ConfigKeys.LOG_LEVEL, ConfigKeys.DEFAULT_LOG_LEVEL),
            ModulePreferences.backend().name,
            if (raw.isEmpty()) "(empty)" else raw,
            if (ModulePreferences.getBoolean(ConfigKeys.IMG_LABEL, ConfigKeys.DEFAULT_IMG_LABEL)) 1 else 0,
            if (ModulePreferences.getBoolean(ConfigKeys.IMG_LABEL_REPEAT_COUNT, ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_COUNT)) 1 else 0,
            ModulePreferences.getString(ConfigKeys.IMG_LABEL_REPEAT_RESET, ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_RESET),
            if (ModulePreferences.getBoolean(ConfigKeys.IMG_LABEL_SCOPE_ONLY_SELECTED, ConfigKeys.DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED)) 1 else 0,
            ModulePreferences.getStringSet(ConfigKeys.IMG_LABEL_SCOPE_IDS, emptySet()).joinToString(","),
            ModulePreferences.getStringSet(ConfigKeys.IMG_LABEL_RECORD_EDITS, ConfigKeys.DEFAULT_IMG_LABEL_RECORD_EDITS).joinToString(","),
        )
        if (columns.size != values.size) {
            throw IllegalStateException(
                "调试通道列数(${columns.size})与值数(${values.size})不等 —— 补的时候要两边一起补"
            )
        }
        return MatrixCursor(columns).apply { addRow(values) }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        ensurePrivileged()
        if (values == null) return 0

        // getAsInteger 可能返回 null（值缺失/类型不对），直接 unboxing 会 NPE；
        // 计数只在实际发生写入时增加，异常时返回值才不会虚报
        fun intOf(col: String): Int? = if (values.containsKey(col)) values.getAsInteger(col) else null

        var n = 0
        ModulePreferences.edit {
            intOf(COL_ENABLED)?.let { putBoolean(ConfigKeys.ENABLED, it != 0); n++ }
            intOf(COL_LOG_LEVEL)?.let { putInt(ConfigKeys.LOG_LEVEL, it); n++ }
            intOf(COL_IMG_LABEL)?.let { putBoolean(ConfigKeys.IMG_LABEL, it != 0); n++ }
            intOf(COL_IMG_LABEL_REPEAT)?.let { putBoolean(ConfigKeys.IMG_LABEL_REPEAT_COUNT, it != 0); n++ }
            if (values.containsKey(COL_IMG_LABEL_RESET)) {
                putString(ConfigKeys.IMG_LABEL_REPEAT_RESET, values.getAsString(COL_IMG_LABEL_RESET))
                n++
            }
        }
        return n
    }
}
