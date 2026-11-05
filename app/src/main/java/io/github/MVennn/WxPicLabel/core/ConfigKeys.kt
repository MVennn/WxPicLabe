package io.github.MVennn.WxPicLabel.core

/**
 * 配置键与默认值。
 *
 * **刻意不引用任何 Xposed / Android 框架类**：目标进程侧与模块 App 侧都引用它，
 * 保证模块 App 不会因为加载它而意外触发 Xposed 类的加载。
 *
 * 配置分两类：
 *  - **功能开关**（[ENABLED] / [IMG_LABEL]）：目标进程要读，必须同步进 [ConfigSnapshot]，
 *    并通过 `ConfigSource.read()` 读取。
 *  - **模块设置**（[LOG_LEVEL] / [DESKTOP_ICON] / [LIQUID_GLASS] / [GLASS_EFFECT] /
 *    [LANGUAGE] / [THEME] / [PREDICTIVE_BACK]）：只有模块 App 自己的进程消费，
 *    **不进 ConfigSnapshot** —— 目标进程不需要知道界面长什么样。
 *    （[LOG_LEVEL] 是例外：它同时被目标进程用于控制日志等级，已在快照中。）
 */
object ConfigKeys {

    /**
     * remote preferences 的分组名。
     *
     * 现代 API 的 `getRemotePreferences(group)` 与模块 App 侧的
     * `XposedService.getRemotePreferences(group)` 用同一个名字，
     * 框架负责在两个进程之间同步（含变更推送）。
     */
    const val GROUP = "config"

    // ---------------------------------------------------------------- 功能开关

    /** 模块总开关（预留：目前所有功能各自有自己的开关） */
    const val ENABLED = "enabled"

    /**
     * 「发图带文件名」总开关。
     *
     * 关掉后模块**完全不接管**相册的批量发送，退回微信原生行为 ——
     * 不是「界面上的开关」，是真正的行为开关（接管入口每次先问它）。
     */
    const val IMG_LABEL = "feature_img_label"

    /**
     * 同名图片重复发送时，在文字后追加 ` +2`、` +3`……
     *
     * 计数在微信进程侧完成（[io.github.MVennn.WxPicLabel.feature.imglabel.RepeatCounter]），
     * 这个键只控制「要不要追加」。
     */
    const val IMG_LABEL_REPEAT_COUNT = "img_label_repeat_count"

    /**
     * 清空发送记录的**单向令牌**（毫秒时间戳字符串）。
     *
     * 设置页每点一次「清空发送记录」就写一个新值；微信进程每次计数前比对，
     * 发现变了就把历史整体清掉。remote preferences 在目标进程侧只读，
     * 这是唯一不需要跨进程监听器的通知方式。
     */
    const val IMG_LABEL_REPEAT_RESET = "img_label_repeat_reset"

    /**
     * 是否已阅读并同意免责声明。
     *
     * 只用于模块自己的界面（首次启动的同意弹窗），且走**本地**存储：
     * 走 remote preferences 的话，后端异步切换会让它在启动时先 false 后 true，
     * 弹窗「闪一下就没」。
     */
    const val DISCLAIMER_ACCEPTED = "disclaimer_accepted"

    // ---------------------------------------------------------------- 模块设置

    /** 日志等级，取值见 [LogLevel] */
    const val LOG_LEVEL = "log_level"

    /** 桌面图标开关：是否在桌面显示本模块的入口图标 */
    const val DESKTOP_ICON = "desktop_icon"

    /**
     * 液态玻璃总开关：true = 顶栏与悬浮底栏都是液态玻璃，false = 两处都是纯色。
     *
     * **键值仍是 `dock_liquid`** —— 改键值会让老用户的配置失效（读不到就回落默认值）。
     */
    const val LIQUID_GLASS = "dock_liquid"

    /**
     * 液态玻璃的光效：模糊 / 折射 / 投影 / 内阴影，顶栏与底栏共用同一个值。
     * 键值沿用 `dock_effect`，理由同上。
     */
    const val GLASS_EFFECT = "dock_effect"

    /** 界面语言，取值见 [LANGUAGE_VALUES] */
    const val LANGUAGE = "language"

    /** 深色模式，取值见 [THEME_VALUES] */
    const val THEME = "theme"

    /**
     * 可预测返回（Android 13+ Predictive Back）。
     * 关闭时二级页只用普通返回动画，不跟随手势实时预览。
     */
    const val PREDICTIVE_BACK = "predictive_back"

    // ---------------------------------------------------------------- 语言取值

    /** 跟随系统 */
    const val LANGUAGE_SYSTEM = "system"

    /** 简体中文（BCP 47 标签，交给 Locale.forLanguageTag） */
    const val LANGUAGE_ZH = "zh-CN"

    /** English */
    const val LANGUAGE_EN = "en"

    /**
     * 语言下拉框的取值顺序，**必须与 `R.array.language_labels` 的条目一一对应**：
     * 跟随系统 / 简体中文 / English。改一边就要改另一边，否则选项会错位。
     */
    val LANGUAGE_VALUES = arrayOf(LANGUAGE_SYSTEM, LANGUAGE_ZH, LANGUAGE_EN)

    // ---------------------------------------------------------------- 深色模式取值

    /** 跟随系统（[io.github.MVennn.WxPicLabel.ui.rememberDarkTheme] 的 else 分支就是它） */
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** 深色模式下拉框的取值顺序，与 `R.array.theme_labels` 一一对应：跟随系统 / 亮色 / 暗色 */
    val THEME_VALUES = arrayOf(THEME_SYSTEM, THEME_LIGHT, THEME_DARK)

    // ---------------------------------------------------------------- 默认值

    /**
     * 功能开关的出厂默认值。
     *
     * 「发图带文件名」默认**开**：模块装完就是这个功能本身，用户装它就是为了这个。
     */
    const val DEFAULT_ENABLED = false

    const val DEFAULT_IMG_LABEL = true

    const val DEFAULT_IMG_LABEL_REPEAT_COUNT = true

    const val DEFAULT_IMG_LABEL_REPEAT_RESET = ""

    const val DEFAULT_DESKTOP_ICON = true

    /** 默认 INFO：既能看到关键链路，又不至于刷屏 */
    const val DEFAULT_LOG_LEVEL = LogLevel.INFO

    const val DEFAULT_LIQUID_GLASS = true

    const val DEFAULT_GLASS_EFFECT = true

    const val DEFAULT_LANGUAGE = LANGUAGE_SYSTEM

    const val DEFAULT_THEME = THEME_SYSTEM

    /** 默认开启可预测返回（Android 13+ 跟手预览；低版本系统会自动退化为普通返回） */
    const val DEFAULT_PREDICTIVE_BACK = true

    const val DEFAULT_DISCLAIMER_ACCEPTED = false

    // ---------------------------------------------------------------- 数据管理

    /** 会被备份 / 恢复 / 重置覆盖的全部配置键 */
    val ALL_KEYS = arrayOf(
        ENABLED, IMG_LABEL, IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET,
        DISCLAIMER_ACCEPTED,
        DESKTOP_ICON, LIQUID_GLASS, GLASS_EFFECT, LANGUAGE, THEME, PREDICTIVE_BACK,
        LOG_LEVEL,
    )

    /**
     * **功能开关键** —— 只属于「功能」这个范畴，不属于模块设置。
     *
     * 「重置模块」只关闭这些开关（外加把功能参数恢复默认），
     * 不动桌面图标 / 底栏 / 语言 / 深色模式这类模块级设置。
     */
    val FEATURE_KEYS = arrayOf(ENABLED, IMG_LABEL)

    /**
     * 功能自己的参数（不属于开关，但「重置模块」一并恢复默认）。
     *
     * [IMG_LABEL_REPEAT_COUNT] 是「重复图片追加次数」的子选项：重置时回到默认值
     * true，而不是被当成开关关掉。
     */
    val FEATURE_PARAM_KEYS = arrayOf(IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET)

    /**
     * **进配置快照的键** —— 目标进程真正会读的那些。
     *
     * 与 [ALL_KEYS] 的差集是「只属于模块 App 自己进程」的键（桌面图标 / 液态玻璃 /
     * 玻璃效果 / 语言 / 深色模式 / 可预测返回）：它们走同一个 remote preferences 分组，
     * 改一下也会给目标进程推一条变更通知，而目标进程**根本不需要知道**。
     *
     * 用途：`ConfigSource` 收到变更通知时先用它挡一道，省掉一次全量读 + 构造快照 + 算指纹。
     *
     * ⚠️ **只增不减**：新增配置键时，若它要进 [ConfigSnapshot]，必须同时加进这里，
     * 否则目标进程**收不到**它的变更。
     */
    val SNAPSHOT_KEYS: Set<String> = setOf(
        ENABLED, IMG_LABEL, IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET, LOG_LEVEL,
    )

    /** 全部配置键的出厂默认值；备份文件的 `defaults` 与「重置模块」都以此为准 */
    val DEFAULTS: Map<String, Any> = mapOf(
        ENABLED to DEFAULT_ENABLED,
        IMG_LABEL to DEFAULT_IMG_LABEL,
        IMG_LABEL_REPEAT_COUNT to DEFAULT_IMG_LABEL_REPEAT_COUNT,
        IMG_LABEL_REPEAT_RESET to DEFAULT_IMG_LABEL_REPEAT_RESET,
        DISCLAIMER_ACCEPTED to DEFAULT_DISCLAIMER_ACCEPTED,
        DESKTOP_ICON to DEFAULT_DESKTOP_ICON,
        LIQUID_GLASS to DEFAULT_LIQUID_GLASS,
        GLASS_EFFECT to DEFAULT_GLASS_EFFECT,
        LANGUAGE to DEFAULT_LANGUAGE,
        THEME to DEFAULT_THEME,
        PREDICTIVE_BACK to DEFAULT_PREDICTIVE_BACK,
        LOG_LEVEL to DEFAULT_LOG_LEVEL,
    )
}
