package io.github.MVennn.WxPicLabel.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap

/** 弹窗内容的公共接口：都带一个可选的「触发控件位置」，用来做锚定定位 */
internal sealed interface DialogSpec {

    /**
     * 触发控件在**窗口坐标系**里的矩形；`null` = 不锚定（贴底 / 居中）。
     *
     * 这个字段不是「想给就给」的装饰，它决定弹层是**小菜单**还是**整屏大列表**
     * （见 `AppDialogHost.kt` 的宽度上限分支）：
     *  - 有 anchor → 菜单宽度 `MenuWidth`，右缘对齐该控件，从它旁边弹出 / 缩回；
     *  - 没有 anchor → 全宽居中，观感上就是个「盖住半屏的列表」。
     * 所以「行内选值」必须传 anchor，传漏了不会报错，只会变成另一种弹窗。
     */
    val anchor: Rect?

    /**
     * 是否允许「点空白 / 按返回」关掉。
     *
     * 默认 `true`。**首次启动的免责声明同意弹窗是 `false`** ——
     * 它是硬门槛：没同意就不能用，不能靠点一下旁边溜过去。
     */
    val dismissible: Boolean get() = true
}

/**
 * 一个「单选」弹窗：若干选项 + 当前选中项。**没有标题**（2.9.2 去掉）。
 *
 * 这是**锚定小菜单**，只有一种用法：点某一行，菜单贴着那一行弹出来。
 * 因此 [anchor] 是必填语义 —— 调用方要用 `Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }`
 * 把行的窗口矩形存进 state 再传进来（参见 `SettingsTab` 的主题 / 语言 / 日志等级）。
 *
 * 不传 anchor 不会报错，但会退化成**全宽居中大列表**：那不是这个规格的形态，
 * 需要「贴底 + 标题 + 说明」时用 [ConfirmDialogSpec]（确认）或 [TextInputDialogSpec]（输入）。
 *
 * 动作菜单（一列动词，如「改文件名 / 改第几次 / 删除这条」）同样用它：
 * `selectedIndex = -1` 不画勾，配 [disabledIndices] 表达「点了也没变化」的项。
 */
internal class ChoiceDialogSpec(
    val items: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
    override val anchor: Rect? = null,
    /**
     * 置灰且不可点的项（3.1.1）。
     *
     * 用在「点了也不会有任何变化」的动作菜单项上 —— 例如方案已经是默认时的「设为默认」、
     * 只有一套方案时的「删除方案」、第一项的「上移」。原来这些项点了没反应，用户会以为
     * 菜单坏了。**只是不可点，不会隐藏**：位置固定，菜单高度不会因为状态变化而跳动。
     */
    val disabledIndices: Set<Int> = emptySet(),
) : DialogSpec

/**
 * 一个「确认 / 取消」弹窗：标题 + 说明 + 两个按钮，贴屏幕底部弹出。
 *
 * [anchor] 恒为 `null` —— 贴底 sheet 不跟着控件走（它比控件宽得多，锚定反而错位）。
 * 不可逆动作先过这里：删除单条记录、清空台账、重置模块。
 *
 * **纯说明也能用它**（例如记录页点只读条目的解释）：此时 [cancelText] 传空串，
 * 弹层只画一颗 [confirmText] 按钮 —— 两粒都关弹层的按钮只会让人以为是选择题。
 */
internal class ConfirmDialogSpec(
    val title: String,
    val message: String,
    val confirmText: String,
    /** 取消按钮文字；传空串 = 不画这颗按钮（纯说明卡） */
    val cancelText: String,
    val onConfirm: () -> Unit,
    override val anchor: Rect? = null,
) : DialogSpec

/**
 * 文本输入弹窗：标题 + 说明 + 单行输入框 + 两个按钮（与 [ConfirmDialogSpec] 同一个贴底壳）。
 *
 * 「发送记录」页的改文件名、改「第几次」用它 —— 这两处都需要在弹层里就地编辑，
 * 而不是跳一个二级页（记录条目本身是列表行，跳页会丢掉当前滚动位置与筛选条件）。
 *
 * 形态是**贴底 sheet**（和键盘一起抬起来），所以不给锚定 —— 从行内动作菜单进来，
 * 不要拿它当「行内选值」用（那属于 [ChoiceDialogSpec]）。
 *
 * [validate] 在**弹层内**校验：返回 `null` 表示通过并关闭；返回非空字符串时**不关闭**，
 * 把那句话显示在输入框下方当成错误提示 —— 这样输错一次不用关掉再重开弹窗。
 */
internal class TextInputDialogSpec(
    val title: String,
    val message: String,
    /** 输入框初始值（改现有记录时是原值，新增时是空串） */
    val initial: String,
    /** 空内容时的占位提示 */
    val hint: String,
    val confirmText: String,
    val cancelText: String,
    val onConfirm: (String) -> Unit,
    override val anchor: Rect? = null,
    /** 数字键盘（「第几次」用）；`false` = 普通文本（文件名用） */
    val numeric: Boolean = false,
    /** 长度上限，超出直接不接收输入（文件名 120、次数 6 位够用） */
    val maxChars: Int = 120,
    val validate: (String) -> String? = { null },
) : DialogSpec

/**
 * 图片预览弹窗：贴屏幕底部、从下往上滑入（与「重置模块」确认弹窗同一个壳）。
 *
 * 弹层材质跟随 App 主题 / 液态玻璃设置；**不再按图片深浅给整窗铺底色** ——
 * 浅色模式下弹出一张黑底的图，不应该把标题和关闭按钮也一起变黑。
 */
internal class ImageDialogSpec(
    val image: ImageBitmap,
    val title: String?,
    val closeText: String,
    override val anchor: Rect? = null,
) : DialogSpec

/**
 * 「首次启动必须同意免责声明」弹窗。
 *
 * 与 [ConfirmDialogSpec] 的区别（也是它必须单独一个类的原因）：
 *  - **没有取消按钮**，只有一颗「确定」，且**未勾选时置灰点不动**；
 *  - 勾选行的文字里嵌一个**可点击的链接**（「免责声明」），点了跳二级页全文；
 *  - [dismissible] = `false`，点空白 / 按返回都关不掉。
 *
 * 说明文字 [message] 是**纯文本**（免责声明入口不在副标题里），链接挪到勾选行：
 * 勾选行拆成「前导 + 链接 + 后接」三段，链接文字由调用方给（便于本地化，
 * 而且链接在句子里的位置各语言不一样，不能写死拼接顺序）。
 */
internal class AgreementDialogSpec(
    val title: String,
    /** 说明文字（纯文本，不含任何链接） */
    val message: String,
    /** 勾选行：前导文字（自带尾随空格） */
    val checkPrefix: String,
    /** 勾选行：可点击的「免责声明」链接文字 */
    val checkLinkText: String,
    /** 勾选行：后接文字（自带前导空格） */
    val checkSuffix: String,
    val confirmText: String,
    val onOpenDisclaimer: () -> Unit,
    val onConfirm: () -> Unit,
) : DialogSpec {

    override val anchor: Rect? = null

    override val dismissible: Boolean = false
}

/**
 * 弹窗宿主状态。
 *
 * 放在 `HomeScaffold` 里 remember 一份，通过 [LocalDialogController] 下发给各 Tab
 * （设置页的三个选择项、重置确认都从这里弹）。
 */
internal class DialogController {

    /** 当前要显示的弹窗；`null` = 不显示 */
    var spec by mutableStateOf<DialogSpec?>(null)
        private set

    /**
     * 是否正在播放「收起」动画。
     *
     * 为 `true` 时 **spec 仍然保留** —— 退出动画期间内容必须还在，
     * 否则会渲染出一张没有内容的空卡片（用户反馈过的「关闭时先出现空白弹窗」）。
     * 动画跑完由 [finishDismiss] 真正清空。
     */
    var closing by mutableStateOf(false)
        private set

    /**
     * 「关闭之后再执行」的动作（见 [dismiss]）。
     *
     * 弹层里的点击**不能**顺手把配置写掉：`commit()` 会同步回调
     * `OnSharedPreferenceChangeListener` → 订阅方改写 Compose 状态 →
     * 正跑着收起动画的弹层被换掉入参 → 崩溃（3.1.x 的 ⋮ 菜单「删除方案」
     * 踩的正是它：删掉一行 → 列表当场重组，而弹窗的收起动画还没播完）。
     * 现在统一排到这里，等 [finishDismiss] 播完动画再落配置 ——
     * 此刻弹窗已彻底关闭，同步写入是安全的（动作必须同步，不能走延迟写入：
     * 那 360ms 正是「副标题顿一会才切换」的根因）。
     */
    private var afterDismiss: (() -> Unit)? = null

    fun showChoice(s: ChoiceDialogSpec) {
        // 新弹窗开启意味着旧弹窗的上下文已作废：如果旧弹窗正在播退出动画
        // （afterDismiss 已排队），那个动作不能再落到新弹窗关闭时执行。
        afterDismiss = null
        closing = false
        spec = s
    }

    fun showConfirm(s: ConfirmDialogSpec) {
        afterDismiss = null
        closing = false
        spec = s
    }

    /** 弹出文本输入（贴底、从下往上滑入） */
    fun showTextInput(s: TextInputDialogSpec) {
        afterDismiss = null
        closing = false
        spec = s
    }

    /** 弹出图片预览（贴底、从下往上滑入） */
    fun showImage(s: ImageDialogSpec) {
        afterDismiss = null
        closing = false
        spec = s
    }

    /** 弹出「首次启动必须同意免责声明」（不可关闭） */
    fun showAgreement(s: AgreementDialogSpec) {
        // 已经在显示同一个同意弹窗时不要再赋值：`remember(spec)` 的勾选状态会被重置，
        // 用户勾了之后如果外层恰好重组一次，勾就会莫名其妙掉回未勾选。
        if (spec is AgreementDialogSpec && !closing) return
        afterDismiss = null
        closing = false
        spec = s
    }

    /**
     * 关闭弹窗 —— **两种形态都走退出动画**（2.9.3 起）。
     *
     * 标记 [closing]，由宿主播完「缩回 / 下滑」动画后再 [finishDismiss] 清空。
     * 之所以不再让选择类弹窗「立即置空」：那样只有展开动画、没有缩回动画，
     * 用户反馈「点掉之后弹窗是凭空消失的」，缺少收束感。
     *
     * 退出期间 [spec] 仍然保留，所以不会渲染出空壳卡片。
     */
    fun dismiss(afterClose: (() -> Unit)? = null) {
        if (spec == null) return
        // 只在传了新动作时覆盖：菜单项已经点过（动作排着队）、随后又点了遮罩 /
        // 按了返回，不能把排队中的那次选择丢掉。
        if (afterClose != null) afterDismiss = afterClose
        closing = true
    }

    /** 退出动画播完，真正清空（只由宿主调用），随后执行「关闭之后再做」的动作 */
    fun finishDismiss() {
        spec = null
        closing = false
        val action = afterDismiss
        afterDismiss = null
        action?.invoke()
    }
}

/** 下发给各 Tab 的弹窗控制器；没有宿主时为 null（调用点做空判断） */
internal val LocalDialogController = compositionLocalOf<DialogController?> { null }
