package io.github.MVennn.WxPicLabel.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.MVennn.WxPicLabel.core.GLog

/**
 * 桌面图标开关 —— alias 组件状态的唯一权威是「本地镜像」。
 *
 * 原理：桌面图标挂在 `LauncherAlias`（`activity-alias`）上，而不是 Activity 本体。
 *  - 关掉图标 = 禁用 alias；打开图标 = 显式启用 alias（见 [apply] 的 state 说明）
 *  - SettingsActivity 本体始终 enabled：LSPosed 的 MAIN + MODULE_SETTINGS 入口
 *    永远进得去，关掉桌面图标不会把设置页锁死。
 *
 * ## 为什么要本地镜像
 *
 * 旧实现只靠 `PackageManager.setComponentEnabledSetting` 记状态：覆盖安装会把这条
 * 组件启用记录清掉，alias 回落到 manifest 默认值；旧 manifest 写的是
 * `android:enabled="true"`，于是「已隐藏」每次更新后必然复活。开关值又存在 remote
 * preferences，进程刚起来框架 binder 未连上时先读 LOCAL（缺键 → 出厂默认 true），
 * 即便 Application.onCreate 跑了 apply 也会把图标错误地设回显示。
 *
 * 现在：manifest 把 alias 默认改成 `android:enabled="false"`（新 APK 兜底 = 隐藏）；
 * 独立文件 `launcher_icon_state` 保存用户选择，跨覆盖安装保留、不依赖框架 binder；
 * 镜像键缺失一律按 false 回落并固化写回（老用户从旧版升级不会复活图标）；静态
 * receiver 接 `MY_PACKAGE_REPLACED` 在更新后立刻重放，Application.onCreate 再兜一次。
 *
 * 独立文件是刻意的：绝不写进 `ConfigKeys.GROUP` 那个文件 —— `resetToDefaults()`
 * 会 clear() 整个文件，`migrateLocalToRemote` 会把所有本地键当配置搬到远端
 * preferences（宿主进程会读到镜像键）；写独立文件也不会触发 ModulePreferences 的
 * observe 回调，不形成回路。
 */
object LauncherIcon {

    /** 与 AndroidManifest 里的 `activity-alias` 名字保持一致 */
    private const val ALIAS_CLASS = "io.github.MVennn.WxPicLabel.ui.LauncherAlias"

    /** 独立镜像文件：与 `ConfigKeys.GROUP` 配置仓库完全隔离 */
    private const val MIRROR_PREFS = "launcher_icon_state"

    /** 镜像键：用户最后一次主动选择的桌面图标可见性 */
    private const val KEY_VISIBLE = "desktop_icon_local"

    /**
     * 镜像缺省 = false（隐藏），不分全新安装 / 覆盖安装。
     *
     * 最贴合「关掉后别再冒出来」，且老用户从旧版升级（旧版没写过镜像）时不会复活。
     * `DEFAULT_DESKTOP_ICON = true` 只作为配置字段 / 快照语义保留，不再驱动 alias。
     */
    private const val MIRROR_DEFAULT = false

    private fun mirror(context: Context) =
        context.applicationContext.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)

    /**
     * 读镜像；键不存在时一律按 false（隐藏）回落。
     *
     * 纯读不写：固化由 [apply] 里的 writeLocal 完成（applyFromLocal 必经 apply），
     * 这样 Compose 渲染期调 [isVisible] 不会在主线程多做一次 commit。
     */
    private fun readLocal(context: Context): Boolean = runCatching {
        mirror(context).getBoolean(KEY_VISIBLE, MIRROR_DEFAULT)
    }.getOrElse { t ->
        GLog.w("读取桌面图标镜像失败，按隐藏处理", t)
        MIRROR_DEFAULT
    }

    /** 写镜像（同步 commit，保证更新广播 / Application.onCreate 读到最新值）。 */
    private fun writeLocal(context: Context, visible: Boolean) {
        runCatching {
            mirror(context).edit().putBoolean(KEY_VISIBLE, visible).commit()
        }.onFailure { GLog.w("写入桌面图标镜像失败（visible=$visible）", it) }
    }

    /**
     * 把开关状态同步到组件状态；同时把同一布尔值固化进本地镜像。
     *
     * visible=true 必须用 `COMPONENT_ENABLED_STATE_ENABLED`（显式启用），不能用
     * `DEFAULT` —— manifest 默认已改成 false，DEFAULT 现在指向「隐藏」，会把
     * 「打开图标」变成无效。visible=false 用 `DISABLED`：显式禁用，语义清晰，
     * 与 DEFAULT=false 结果一致但不依赖 manifest 默认值将来被改动。
     */
    fun apply(context: Context, visible: Boolean) {
        writeLocal(context, visible)
        val pm = context.packageManager
        val alias = ComponentName(context.packageName, ALIAS_CLASS)
        val state = if (visible) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }.onFailure { GLog.w("切换桌面图标失败（visible=$visible）", it) }
    }

    /** 按本地镜像幂等重放一次（更新广播 / Application.onCreate 的入口）。 */
    fun applyFromLocal(context: Context) {
        apply(context, readLocal(context))
    }

    /**
     * 当前是否为「显示」——以本地镜像为准。
     *
     * 旧实现用 `getComponentEnabledSetting(alias) != DISABLED`，manifest 默认改成
     * false 后 DEFAULT 会被误判成「显示」，语义撕裂，故不再读组件状态。
     */
    fun isVisible(context: Context): Boolean = readLocal(context)
}
