package io.github.MVennn.WxPicLabel.core

/**
 * 配置键与默认值。
 *
 * **刻意不引用任何 Xposed / Android 框架类**：目标进程侧与模块 App 侧都引用它，
 * 保证模块 App 不会因为加载它而意外触发 Xposed 类的加载。
 *
 * 配置分两类：
 *  - **功能开关**（[ENABLED] / [IMG_LABEL]）：目标进程要读，必须登记进 [SNAPSHOT_KEYS]，
 *    并由 `WxPicLabelModule` 的 `cfgBool()` 直读。本仓功能数少，**没有**独立的
 *    快照层（`ConfigSnapshot` / `ConfigSource`）—— 那是 GSAuxiliary 有 20+ 个功能时
 *    才值得的一层；判定口径与将来接入时机见 `docs/CONFIG-MEMORY.md` §7。
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
     * 「发送范围」：是否**只**对勾选的好友 / 群聊生效。
     *
     * 关 = 所有会话都带文件名（默认，装上就能用）；开 = 只对 [IMG_LABEL_SCOPE_IDS]
     * 里勾选的会话生效，其余会话退回微信原生发送（图照发、不带文件名）。
     */
    const val IMG_LABEL_SCOPE_ONLY_SELECTED = "img_label_scope_only_selected"

    /**
     * 勾选的会话 username（`wxid_xxx` / `xxx@chatroom`）。
     *
     * 名单存在 remote preferences 里由微信进程读取；好友**名字**不走这里 ——
     * 名字由微信进程抓取后经 FriendBridgeProvider 回传给设置页显示。
     */
    const val IMG_LABEL_SCOPE_IDS = "img_label_scope_ids"

    /**
     * 「刷新好友名单」的**单向令牌**（毫秒时间戳字符串）。
     *
     * 与 [IMG_LABEL_REPEAT_RESET] 同一套模式：设置页每点一次「刷新」就写一个新值，
     * 微信进程轮询比对，发现变了就重新抓一遍联系人并经 Provider 回传。
     */
    const val IMG_LABEL_SCOPE_REFRESH = "img_label_scope_refresh"

    /**
     * 发送期间用系统 Toast 显示进度（`正在发送 3/12`），让用户知道还剩多少张。
     *
     * 目标进程要读，登记进 [SNAPSHOT_KEYS]；子参数，默认开 —— 开了总开关就是完整形态。
     */
    const val IMG_LABEL_PROGRESS_TOAST = "img_label_progress_toast"

    /**
     * 发送期间给当前前台 Activity 加 `FLAG_KEEP_SCREEN_ON`，整批发完自动解除。
     *
     * 不用 WakeLock：不需要宿主权限，Activity 销毁时系统自己回收。
     */
    const val IMG_LABEL_KEEP_SCREEN_ON = "img_label_keep_screen_on"

    /**
     * 重命名规则表：一行一条 `原名 -> 新名`（也接受 `=>` / `=` / 制表符）。
     *
     * 发图时按**文件名（去扩展名）**精确匹配，命中就用右边那段文字发出去；
     * 没命中仍然发原名。整表存成一个字符串 —— 规则通常就几十行，
     * 存成 String 比 StringSet 好导入导出（一行一条就是文件的全部内容）。
     */
    const val IMG_LABEL_RENAME_RULES = "img_label_rename_rules"

    /**
     * 「文字过滤」模式，取值见 [FILTER_MODE_VALUES]：关闭 / 白名单 / 黑名单。
     *
     * 白名单 = 只有命中关键字的图才带文字发出去（其余只发图）；黑名单 = 命中关键字的图只发图
     * （其余照常带文字）。关闭时完全不做判断，与没有这个功能等价。
     *
     * 判定发生在**计数之前**（见 WxPicLabelModule#textFor）：被过滤掉的图既不 +N、也不进发送记录，
     * 否则下次发同一张图会凭空跳号。
     */
    const val IMG_LABEL_FILTER_MODE = "img_label_filter_mode"

    /**
     * 关键字表：一行一条，井号开头是注释。
     *
     * 不含通配符按**子串包含**匹配；含星号 / 问号按整段通配匹配。整表存成一个字符串，
     * 理由同 [IMG_LABEL_RENAME_RULES]：一行一条就是文件的全部内容，导入导出最省事。
     */
    const val IMG_LABEL_FILTER_RULES = "img_label_filter_rules"

    /** 关键字匹配是否忽略大小写（默认开；中文不受影响，英文截图名常见全大写） */
    const val IMG_LABEL_FILTER_IGNORE_CASE = "img_label_filter_ignore_case"

    /**
     * 是否连**原始文件名**一起参与匹配（默认开）。
     *
     * 关掉就只看重命名后的最终文字 —— 适合「规则表已经把名字改成人话、
     * 不想让 IMG_20260101_… 这种机器名影响判断」的场景。
     */
    const val IMG_LABEL_FILTER_MATCH_ORIGINAL = "img_label_filter_match_original"

    /**
     * 「发送记录」回传令牌（毫秒时间戳）。
     *
     * 与清空令牌同一套单向通知：设置页每点一次「刷新」写一个新值，
     * 微信进程轮询到变化就把计数表经 FriendBridgeProvider 推回模块 App。
     */
    const val IMG_LABEL_RECORD_DUMP = "img_label_record_dump"

    /**
     * 「发送记录」的手工修改动作（StringSet），一条一个动作，三种语法：
     *
     *  - `d|<seq>` 删除这条记录；
     *  - `l|<seq>|<新名字>` 改这条记录发出去的文字；
     *  - `n|<seq>|<次数>` 改这条记录是「第几次」。
     *
     * `<seq>` 是微信进程给每次发送分配的序号（见 RepeatCounter 的 Event）。
     * 微信进程读到新令牌后把**整套动作重新应用一遍**（三种动作都幂等），再回传改完的台账 ——
     * 于是「改第几次 / 删一条」会真的影响下一次发送的 +N 编号，而不是只改界面显示。
     */
    const val IMG_LABEL_RECORD_EDITS = "img_label_record_edits"

    /** [IMG_LABEL_RECORD_EDITS] 的变更令牌（毫秒时间戳）；变了才重新应用一次。 */
    const val IMG_LABEL_RECORD_EDITS_TOKEN = "img_label_record_edits_token"


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

    // ---------------------------------------------------------------- 文字过滤取值

    /** 关闭：不做任何判断，每张图都照常发文字 */
    const val FILTER_MODE_OFF = "off"

    /** 白名单：命中关键字的图才发文字 */
    const val FILTER_MODE_WHITELIST = "white"

    /** 黑名单：命中关键字的图只发图 */
    const val FILTER_MODE_BLACKLIST = "black"

    /**
     * 文字过滤的取值集合：关闭 / 白名单 / 黑名单。
     *
     * 设置页那行开关只用白名单与黑名单两档（关 = 白名单、开 = 黑名单），
     * 「关闭」留作老版本配置的兼容读写 —— 判定与解析都不认它以外的新值时按关闭处理。
     */
    val FILTER_MODE_VALUES = arrayOf(FILTER_MODE_OFF, FILTER_MODE_WHITELIST, FILTER_MODE_BLACKLIST)

    // ---------------------------------------------------------------- 默认值

    /**
     * 功能开关的出厂默认值。
     *
     * **所有功能开关一律默认关闭** —— 口径与 GSAuxiliary 2.11.2 起完全一致：
     * 装完模块、什么都不改，目标 App 的行为与原生一模一样；用户想要哪个功能自己去开。
     * 三条理由，都是踩过的：
     *  1. 装完就悄悄改掉别人的行为，用户第一反应是「微信怎么怪怪的」，而不是「模块真好用」；
     *  2. 「重置模块」的语义是「回到原生」—— 默认值若不是关，重置就等于没关
     *     （GSAuxiliary 2.3.1 修过这个 bug）；
     *  3. 默认值必须与 [FEATURE_KEYS] 的「重置 = 显式写 false」一致，否则重置结果随默认值漂移。
     *
     * ⚠️ **子参数不适用本条**：[DEFAULT_IMG_LABEL_REPEAT_COUNT] 这类只在总开关打开后才有意义，
     * 默认值保持 true，等于「开了总开关就是完整形态」，用户不必再进二级页开一遍。
     */
    const val DEFAULT_ENABLED = false

    const val DEFAULT_IMG_LABEL = false

    const val DEFAULT_IMG_LABEL_REPEAT_COUNT = true

    const val DEFAULT_IMG_LABEL_REPEAT_RESET = ""

    const val DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED = false

    const val DEFAULT_IMG_LABEL_PROGRESS_TOAST = true

    const val DEFAULT_IMG_LABEL_KEEP_SCREEN_ON = true

    const val DEFAULT_IMG_LABEL_RENAME_RULES = ""

    /** 默认关闭：装了不动，行为与原生一致 */
    const val DEFAULT_IMG_LABEL_FILTER_MODE = FILTER_MODE_OFF

    const val DEFAULT_IMG_LABEL_FILTER_RULES = ""

    const val DEFAULT_IMG_LABEL_FILTER_IGNORE_CASE = true

    const val DEFAULT_IMG_LABEL_FILTER_MATCH_ORIGINAL = true

    const val DEFAULT_IMG_LABEL_RECORD_DUMP = ""

    /** 默认没有任何手工修改动作（空表） */
    val DEFAULT_IMG_LABEL_RECORD_EDITS: Set<String> = emptySet()

    const val DEFAULT_IMG_LABEL_RECORD_EDITS_TOKEN = ""

    const val DEFAULT_DESKTOP_ICON = true

    /** 默认 INFO：既能看到关键链路，又不至于刷屏 */
    const val DEFAULT_LOG_LEVEL = LogLevel.INFO

    const val DEFAULT_LIQUID_GLASS = true

    const val DEFAULT_GLASS_EFFECT = true

    const val DEFAULT_LANGUAGE = LANGUAGE_SYSTEM

    const val DEFAULT_THEME = THEME_SYSTEM

    /** 默认开启可预测返回（Android 13+ 跟手预览；低版本系统会自动退化为普通返回） */
    const val DEFAULT_PREDICTIVE_BACK = true

    /**
     * 免责声明同意态的回落值 —— **不在 [DEFAULTS] 里**，也不是模块配置。
     *
     * 它只做 `rememberLocalBoolPref(DISCLAIMER_ACCEPTED, DEFAULT_DISCLAIMER_ACCEPTED)`
     * 的缺键回落值：本地文件没有这个键 = 还没同意过 = 弹不可关闭的门槛弹窗。
     * 为什么不进 [ALL_KEYS] / [DEFAULTS]，见 [ALL_KEYS] 的注释。
     */
    const val DEFAULT_DISCLAIMER_ACCEPTED = false

    // ---------------------------------------------------------------- 数据管理

    /**
     * 会被备份 / 恢复 / 重置覆盖的全部配置键。
     *
     * ⚠️ **[DISCLAIMER_ACCEPTED] 不在其中**（GSAuxiliary 同口径）：它是**本机界面状态**，
     * 不是模块配置。放进来会有两个坏结果 —— 备份文件带着「已同意」跑到另一台机器上
     * （换机即绕过免责声明）；「重置模块」把它一起清掉，用户每重置一次就要再点一次同意。
     * 它走 [io.github.MVennn.WxPicLabel.app.ModulePreferences.getLocalBoolean] 的本地文件。
     */
    val ALL_KEYS = arrayOf(
        ENABLED, IMG_LABEL, IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET,
        IMG_LABEL_SCOPE_ONLY_SELECTED, IMG_LABEL_SCOPE_IDS, IMG_LABEL_SCOPE_REFRESH,
        IMG_LABEL_PROGRESS_TOAST, IMG_LABEL_KEEP_SCREEN_ON,
        IMG_LABEL_RENAME_RULES,
        IMG_LABEL_FILTER_MODE, IMG_LABEL_FILTER_RULES,
        IMG_LABEL_FILTER_IGNORE_CASE, IMG_LABEL_FILTER_MATCH_ORIGINAL,
        IMG_LABEL_RECORD_DUMP,
        IMG_LABEL_RECORD_EDITS, IMG_LABEL_RECORD_EDITS_TOKEN,
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
    val FEATURE_PARAM_KEYS = arrayOf(
        IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET,
        IMG_LABEL_SCOPE_ONLY_SELECTED, IMG_LABEL_SCOPE_IDS, IMG_LABEL_SCOPE_REFRESH,
        IMG_LABEL_PROGRESS_TOAST, IMG_LABEL_KEEP_SCREEN_ON,
        IMG_LABEL_RENAME_RULES,
        IMG_LABEL_FILTER_MODE, IMG_LABEL_FILTER_RULES,
        IMG_LABEL_FILTER_IGNORE_CASE, IMG_LABEL_FILTER_MATCH_ORIGINAL,
        IMG_LABEL_RECORD_DUMP,
        IMG_LABEL_RECORD_EDITS, IMG_LABEL_RECORD_EDITS_TOKEN,
    )

    /**
     * **进配置快照的键** —— 目标进程真正会读的那些。
     *
     * 与 [ALL_KEYS] 的差集是「只属于模块 App 自己进程」的键（桌面图标 / 液态玻璃 /
     * 玻璃效果 / 语言 / 深色模式 / 可预测返回）：它们走同一个 remote preferences 分组，
     * 改一下也会给目标进程推一条变更通知，而目标进程**根本不需要知道**。
     *
     * 用途：目标进程侧若将来接入变更监听，收到通知时先用它挡一道，省掉一次全量读。
     * 本仓目前没有监听层（`WxPicLabelModule` 是每次用时直读），这一组是**预留登记口径**：
     * 现在就登记好，接入时才不会漏。
     *
     * ⚠️ **只增不减**：新增配置键时，若目标进程要读它，必须同时加进这里，
     * 否则将来接入变更监听后**收不到**它的变更。
     */
    val SNAPSHOT_KEYS: Set<String> = setOf(
        ENABLED, IMG_LABEL, IMG_LABEL_REPEAT_COUNT, IMG_LABEL_REPEAT_RESET,
        IMG_LABEL_SCOPE_ONLY_SELECTED, IMG_LABEL_SCOPE_IDS, IMG_LABEL_SCOPE_REFRESH,
        IMG_LABEL_PROGRESS_TOAST, IMG_LABEL_KEEP_SCREEN_ON,
        IMG_LABEL_RENAME_RULES, IMG_LABEL_RECORD_DUMP,
        IMG_LABEL_FILTER_MODE, IMG_LABEL_FILTER_RULES,
        IMG_LABEL_FILTER_IGNORE_CASE, IMG_LABEL_FILTER_MATCH_ORIGINAL,
        IMG_LABEL_RECORD_EDITS, IMG_LABEL_RECORD_EDITS_TOKEN,
        LOG_LEVEL,
    )

    /**
     * 全部配置键的出厂默认值；备份文件的 `defaults` 段、`resetToDefaults()`、
     * 以及首次连接时补齐远端缺失键（`ModulePreferences.ensureRemoteDefaults`）都以此为准。
     *
     * **不含 [DISCLAIMER_ACCEPTED]**，理由见 [ALL_KEYS]。
     */
    val DEFAULTS: Map<String, Any> = mapOf(
        ENABLED to DEFAULT_ENABLED,
        IMG_LABEL to DEFAULT_IMG_LABEL,
        IMG_LABEL_REPEAT_COUNT to DEFAULT_IMG_LABEL_REPEAT_COUNT,
        IMG_LABEL_REPEAT_RESET to DEFAULT_IMG_LABEL_REPEAT_RESET,
        IMG_LABEL_SCOPE_ONLY_SELECTED to DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED,
        IMG_LABEL_SCOPE_IDS to emptySet<String>(),
        IMG_LABEL_SCOPE_REFRESH to "",
        IMG_LABEL_PROGRESS_TOAST to DEFAULT_IMG_LABEL_PROGRESS_TOAST,
        IMG_LABEL_KEEP_SCREEN_ON to DEFAULT_IMG_LABEL_KEEP_SCREEN_ON,
        IMG_LABEL_RENAME_RULES to DEFAULT_IMG_LABEL_RENAME_RULES,
        IMG_LABEL_FILTER_MODE to DEFAULT_IMG_LABEL_FILTER_MODE,
        IMG_LABEL_FILTER_RULES to DEFAULT_IMG_LABEL_FILTER_RULES,
        IMG_LABEL_FILTER_IGNORE_CASE to DEFAULT_IMG_LABEL_FILTER_IGNORE_CASE,
        IMG_LABEL_FILTER_MATCH_ORIGINAL to DEFAULT_IMG_LABEL_FILTER_MATCH_ORIGINAL,
        IMG_LABEL_RECORD_DUMP to DEFAULT_IMG_LABEL_RECORD_DUMP,
        IMG_LABEL_RECORD_EDITS to DEFAULT_IMG_LABEL_RECORD_EDITS,
        IMG_LABEL_RECORD_EDITS_TOKEN to DEFAULT_IMG_LABEL_RECORD_EDITS_TOKEN,
        DESKTOP_ICON to DEFAULT_DESKTOP_ICON,
        LIQUID_GLASS to DEFAULT_LIQUID_GLASS,
        GLASS_EFFECT to DEFAULT_GLASS_EFFECT,
        LANGUAGE to DEFAULT_LANGUAGE,
        THEME to DEFAULT_THEME,
        PREDICTIVE_BACK to DEFAULT_PREDICTIVE_BACK,
        LOG_LEVEL to DEFAULT_LOG_LEVEL,
    )
}
