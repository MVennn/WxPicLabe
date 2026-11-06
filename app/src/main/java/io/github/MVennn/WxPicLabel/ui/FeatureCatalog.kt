package io.github.MVennn.WxPicLabel.ui

import androidx.annotation.StringRes
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.core.ConfigKeys

/**
 * ## 功能目录（展示用元数据）
 *
 * 功能列表按 [FeatureSpec.categoryRes] 分组，**组内顺序 = [FEATURE_CATALOG] 里的出现顺序**
 * （`FeatureTab` 用 `groupBy` 分组，首次出现的分组排在最前）。本仓目前只有一个分区：
 *
 * | 分区 | 一句话判据 | 归属范围 |
 * |---|---|---|
 * | **发送** `category_img_label` | 改的是「把图片发出去」这件事本身 | 增加 文件名 开关、重复图片追加次数 |
 *
 * 新增分区之前先想清楚：只有当一个功能**不属于任何一个已有分区**时才新开分区，
 * 并且要同时更新上表、`values/strings.xml` / `values-en/strings.xml` 里的 `category_*`
 * （引一个不存在的 `category_*` 会直接编译失败）。分区名只是**展示文案**，改名字不影响逻辑。
 *
 * ## 为什么不直接引用目标进程侧的实现类
 *
 * 设置界面跑在模块**自己的进程**，那个进程的 APK 里没有 `io.github.libxposed.api`
 * （依赖是 `compileOnly`）。所以界面只能读这份**纯元数据**，不能碰任何钩子实现 ——
 * 判据是「这个类会不会被模块自身进程加载」，与它放在哪个目录无关（见 `docs/ARCHITECTURE.md`）。
 */

/**
 * 功能目录（**展示用元数据**）。
 *
 * 新增功能时：在 [FEATURE_CATALOG] 加一条，并在 `core/ConfigKeys` 里登记它的开关键
 * 与默认值；实现放在 `feature/<name>/`，且**不得**被 `ui/` 引用。
 */
internal data class FeatureSpec(
    /** 与 `Feature.id` 一致，用于路由二级菜单 */
    val id: String,
    @StringRes val categoryRes: Int,
    /**
     * 功能开关的标题 / 说明 —— **用户唯一可见的功能名**，起名遵循 `docs/NAMING.md`：
     * 名称里不写「启用 / 增强」（开关本身就是启用），新增类元素写成「增加 + 内容 + 控件类型」。
     */
    @StringRes val switchTitleRes: Int,
    /**
     * 开关下方那一行说明 —— **可空**。
     *
     * 副标题的核心职责是**说清功能塞到了哪里**（底栏、右上角三点菜单、多选栏 …）
     * —— 标题只说「加了 / 隐藏了什么」，没说「在哪」；位置说不清的功能必须补。
     * 主标题连同分组已经把话说全时不要写（传 null，卡片就只显示标题）：
     * 「隐藏新股申购提醒」下面再写「新股申购提醒不再弹出」是同一句话说了两遍。
     * 一行说完、能不用逗号就不用 —— 详见 `docs/NAMING.md` §「副标题」。
     */
    @StringRes val switchSummaryRes: Int? = null,
    /** 开关对应的配置键与默认值 */
    val enableKey: String,
    val defaultEnabled: Boolean,
    /** 二级菜单：标题、入口名、入口说明；为 null 表示该功能没有可调项 */
    @StringRes val detailTitleRes: Int? = null,
    @StringRes val detailEntryRes: Int? = null,
    @StringRes val detailEntrySummaryRes: Int? = null,
    /**
     * **第二个**二级入口（可空）。目前「自选分组复制」用：
     * 一个功能下同时挂「复制规则」与「复制按钮设置」两个页面。
     */
    @StringRes val detailEntry2Res: Int? = null,
) {
    /** 是否有可调节的具体设置（决定是否出现「开启后展开的二级入口」） */
    val hasDetail: Boolean get() = detailTitleRes != null && detailEntryRes != null
}

/** 全部功能的展示元数据；顺序即界面顺序 */internal val FEATURE_CATALOG: List<FeatureSpec> = listOf(
    FeatureSpec(
        id = "img_label",
        categoryRes = R.string.category_img_label,
        switchTitleRes = R.string.feature_img_label_switch,
        // 新增类元素（相册底栏多出来的那个开关）→ 标题三段式；
        // 三段式只说了「加了什么」，位置说不清 → 副标题只写位置（docs/NAMING.md 规则三 / 规则四）。
        switchSummaryRes = R.string.feature_img_label_summary,
        enableKey = ConfigKeys.IMG_LABEL,
        defaultEnabled = ConfigKeys.DEFAULT_IMG_LABEL,
        detailTitleRes = R.string.img_label_send_title,
        detailEntryRes = R.string.img_label_send_entry,
        // 入口名已说清进去做什么 → 不写（docs/NAMING.md §3）
        detailEntrySummaryRes = null,
    ),
)
