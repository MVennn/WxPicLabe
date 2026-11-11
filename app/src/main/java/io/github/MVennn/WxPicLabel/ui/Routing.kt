package io.github.MVennn.WxPicLabel.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 极简路由：一级首页 + 若干二级页 + 三级页（发送设置下面那几页）。
 *
 * 新增二级页 = 在 [Route] 加一个 data object + 在 [RouteSaver] 的 save / restore
 * 各加一个分支（when 是穷尽的，漏写会直接编译失败）+ 在 [MainScreen] 的
 * when (shownSub) 里挂上页面；三级页还要在 [editParent] 里登记它的父页。
 */
internal sealed interface Route {
    data object Home : Route
    data object Disclaimer : Route
    data object Notice : Route
    /** 「增加 文件名 开关 → 发送设置」（二级） */
    data object ImgLabelSend : Route
    /** 三级：会话白名单勾选 */
    data object ImgLabelScope : Route
    /** 三级：重命名规则编辑 */
    data object ImgLabelRename : Route
    /** 三级：发送记录（同名图片计数表） */
    data object ImgLabelRecord : Route
    /** 三级：文字过滤（黑白名单） */
    data object ImgLabelFilter : Route
    /** 三级：重复图片的追加时段（+N 只在时段内累计） */
    data object ImgLabelRepeatWindow : Route
}

/** 三级页 → 它压在下面的那个二级页；不是三级页返回 null。 */
private fun editParent(route: Route): Route? = when (route) {
    Route.ImgLabelScope, Route.ImgLabelRename, Route.ImgLabelRecord,
    Route.ImgLabelFilter, Route.ImgLabelRepeatWindow -> Route.ImgLabelSend
    else -> null
}

/** 页面滑动动画时长 */
private const val RouteAnimMillis = 300

/**
 * [Route] 的存档器（转屏 / 分屏 / 进程被回收后重建靠它把「当前在哪一层」带回来）。
 *
 * 为什么不用 rememberSaveable 的自动存盘：Route 是 sealed interface，
 * 自动存盘只认 Parcelable / Serializable。编成一个短字符串最省事，
 * 也顺手把「新加路由忘了写存盘」变成编译期错误（when 穷尽）。
 */
private object RouteSaver : Saver<Route, String> {
    override fun SaverScope.save(value: Route): String = when (value) {
        Route.Home -> "home"
        Route.Disclaimer -> "disclaimer"
        Route.Notice -> "notice"
        Route.ImgLabelSend -> "img_label_send"
        Route.ImgLabelScope -> "img_label_scope"
        Route.ImgLabelRename -> "img_label_rename"
        Route.ImgLabelRecord -> "img_label_record"
        Route.ImgLabelFilter -> "img_label_filter"
        Route.ImgLabelRepeatWindow -> "img_label_repeat_window"
    }

    override fun restore(value: String): Route = when (value) {
        "disclaimer" -> Route.Disclaimer
        "notice" -> Route.Notice
        "img_label_send" -> Route.ImgLabelSend
        "img_label_scope" -> Route.ImgLabelScope
        "img_label_rename" -> Route.ImgLabelRename
        "img_label_record" -> Route.ImgLabelRecord
        "img_label_filter" -> Route.ImgLabelFilter
        "img_label_repeat_window" -> Route.ImgLabelRepeatWindow
        else -> Route.Home
    }
}

/**
 * 三层叠放 + 两条独立的滑动进度。
 *
 * ## 为什么三级页要单独一层
 *
 * 上一版只有「一级 / 二级」两层，三级页（好友选择）直接顶掉二级页的内容，于是：
 *
 *  - **进三级页没有任何动画**（内容原地替换）；
 *  - **从三级页可预测返回时，动画看着像要回一级页**：手势把整层二级页往右推，
 *    底下露出的是首页，手势提交后又把发送设置推回来 —— 用户看到的是
 *    「退回首页 → 又跳回发送设置」，而不是「选择页滑走、发送设置在下面等着」。
 *
 * 现在二级、三级各有一条进度（`subProgress` / `editProgress`）：
 *
 * | 动作 | 二级页 | 三级页 |
 * |---|---|---|
 * | 进二级页 | 0 → 1 | 保持 0 |
 * | 进三级页 | 保持 1 | 0 → 1 |
 * | 三级页返回 | 保持 1（顺带 0.94 → 1 回正） | 1 → 0 |
 * | 二级页返回 | 1 → 0 | 已为 0 |
 *
 * 可预测返回手势只动**当前最上面那一层**的进度，所以从三级页往回滑，
 * 滑走的是选择页，露出来的是发送设置 —— 与系统的返回预期一致。
 *
 * ## 性能约束（沿用上一版的结论）
 *
 * 两条进度**只在 `graphicsLayer` 的 lambda 里读**，组合期不读：
 * 组合期读动画值会让整页在 300ms 滑动里每帧重组一次（MainScreen → HomeScaffold →
 * 当前板块整棵树），实机上就是「左右滑动卡」的主因。组合期只留
 * `derivedStateOf` 折出来的布尔门槛（决定某一层要不要挂着）。
 */
@Composable
internal fun MainScreen(initialRoute: Route = Route.Home) {
    val scope = rememberCoroutineScope()

    // Tab 与当前路由必须**跨 Activity 重建**存活：转屏 / 分屏 / 折叠屏都会重建 Activity。
    var currentTab by rememberSaveable { mutableStateOf(Tab.MODULE) }
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(initialRoute) }

    // 首次启动的免责声明同意门槛：没同意就必须先同意。
    //
    // 条件是「未同意 **且** 在首页」：点弹窗里的链接会跳到免责声明二级页，
    // 那一页要能正常看；回到首页时条件重新成立，弹窗自然又出来。
    // 用 local 版：同意状态是**本机界面状态**，不进 remote preferences。
    val agreedDisclaimer by rememberLocalBoolPref(
        ConfigKeys.DISCLAIMER_ACCEPTED,
        ConfigKeys.DEFAULT_DISCLAIMER_ACCEPTED,
    )
    val showAgreement = !agreedDisclaimer && route == Route.Home

    // 「这两层此刻该画哪一页」在**组合期**就算出来，不放进 LaunchedEffect：
    // 进入时当帧就位（放 effect 里会晚一帧，看着像「打开慢」）；
    // 退出动画期间 route 已经不是它了，但那一层还得继续画完 —— 靠 shownSub / shownEdit 记住。
    val editRouteNow: Route? = route.takeIf { editParent(it) != null }
    val subRouteNow = editParent(route) ?: route
    var shownSub by remember { mutableStateOf(subRouteNow) }
    var shownEdit by remember { mutableStateOf(editRouteNow) }
    if (subRouteNow != Route.Home && subRouteNow != shownSub) shownSub = subRouteNow
    if (editRouteNow != null && editRouteNow != shownEdit) shownEdit = editRouteNow

    val predictiveBack = rememberPredictiveBack()
    // 二级 / 三级各自的滑动进度：0 = 完全滑出屏幕右侧，1 = 完全盖住下面那层
    val subProgress = remember { Animatable(if (route == Route.Home) 0f else 1f) }
    val editProgress = remember { Animatable(if (editParent(route) != null) 1f else 0f) }

    // 返回目标：三级页回它自己的二级页，其余二级页回首页
    fun backTarget(): Route = editParent(route) ?: Route.Home

    LaunchedEffect(route) {
        when {
            route == Route.Home -> {
                // 三级页（若在）先滑走，再滑走二级页
                if (shownEdit != null) {
                    editProgress.animateTo(0f, tween(RouteAnimMillis))
                    shownEdit = null
                }
                subProgress.animateTo(0f, tween(RouteAnimMillis))
            }

            editParent(route) != null -> {
                subProgress.animateTo(1f, tween(RouteAnimMillis))
                editProgress.animateTo(1f, tween(RouteAnimMillis))
            }

            else -> {
                // 从三级页回到二级页：只动三级页，二级页留在原地。
                // 判据是「三级层还在不在」，不是「进度 > 0」—— 入场动画刚起步时按返回，
                // 进度还是 0，按进度判断会把那一层直接销毁，表现是「页面瞬没」。
                if (shownEdit != null) {
                    editProgress.animateTo(0f, tween(RouteAnimMillis))
                    shownEdit = null
                }
                subProgress.animateTo(1f, tween(RouteAnimMillis))
            }
        }
    }

    if (predictiveBack) {
        PredictiveBackHandler(enabled = route != Route.Home) { flow ->
            // 手势只推动最上面那一层：三级页返回时底下是它自己的二级页，不是首页
            val onEdit = editParent(route) != null
            try {
                flow.collect { (if (onEdit) editProgress else subProgress).snapTo(1f - it.progress) }
                route = backTarget()
            } catch (e: CancellationException) {
                // 手势取消：滑回去
                scope.launch { (if (onEdit) editProgress else subProgress).animateTo(1f, tween(200)) }
                throw e
            }
        }
    } else {
        BackHandler(enabled = route != Route.Home) { route = backTarget() }
    }

    val subMounted by remember { derivedStateOf { subProgress.value > 0.001f } }
    val editMounted by remember { derivedStateOf { editProgress.value > 0.001f } }
    // 页面底色 = colorScheme.surface，**不是** background：
    // Miuix 里 background 与 surfaceContainer（Card 默认色）完全同色，卡片会糊成一片。
    val background = MiuixTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 第 0 层：底色层（不参与任何手势动画）----
        // 可预测返回会把一级页缩到 0.94，缩下去以后四周露出的是这一层。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(background),
        )

        // ---- 第 1 层：一级首页（常驻底衬）----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 图层阶段读进度：只重绘、不重组
                    val p = subProgress.value
                    val s = 1f - 0.06f * p
                    scaleX = s
                    scaleY = s
                    alpha = 1f - 0.35f * p
                },
        ) {
            HomeScaffold(
                currentTab = currentTab,
                onSelectTab = { currentTab = it },
                onOpenDisclaimer = { route = Route.Disclaimer },
                onOpenNotice = { route = Route.Notice },
                onOpenFeatureDetail = { id ->
                    // 功能 id → 二级页。新增带二级页的功能时在这里加一行。
                    route = when (id) {
                        "img_label" -> Route.ImgLabelSend
                        else -> Route.Home
                    }
                },
                showAgreement = showAgreement,
                onAgreeDisclaimer = {
                    ModulePreferences.setLocalBoolean(ConfigKeys.DISCLAIMER_ACCEPTED, true)
                },
            )
        }

        // ---- 第 2 层：二级页（右滑进出）----
        if (route != Route.Home || subMounted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = size.width * (1f - subProgress.value) }
                    .background(background),
            ) {
                // 二级页本体：被三级页盖住时缩一点（可预测返回时随手势回正）。
                // 缩放只挂这一层，不能挂到外层 Box —— 否则三级页会跟着一起缩。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (editMounted) {
                                Modifier.graphicsLayer {
                                    val s = 1f - 0.06f * editProgress.value
                                    scaleX = s
                                    scaleY = s
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    when (shownSub) {
                        Route.Disclaimer -> SubScreen(
                            title = stringResource(R.string.disclaimer_detail_title),
                            onBack = { route = Route.Home },
                        ) {
                            DisclaimerScreen()
                        }

                        Route.Notice -> SubScreen(
                            title = stringResource(R.string.notice_detail_title),
                            onBack = { route = Route.Home },
                        ) {
                            NoticeScreen()
                        }

                        Route.ImgLabelSend -> SubScreen(
                            title = stringResource(R.string.img_label_send_title),
                            onBack = { route = Route.Home },
                        ) {
                            ImgLabelSendScreen(
                                onOpenScope = { route = Route.ImgLabelScope },
                                onOpenRecord = { route = Route.ImgLabelRecord },
                                onOpenRename = { route = Route.ImgLabelRename },
                                onOpenFilter = { route = Route.ImgLabelFilter },
                                onOpenRepeatWindow = { route = Route.ImgLabelRepeatWindow },
                            )
                        }

                        else -> Unit
                    }
                }

                // ---- 第 3 层：三级页（叠在二级页之上，只自己滑）----
                val editRoute = shownEdit
                if (editRoute != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = size.width * (1f - editProgress.value) }
                            .background(background),
                    ) {
                        when (editRoute) {
                            Route.ImgLabelScope -> SubScreen(
                                title = stringResource(R.string.img_label_scope_entry),
                                onBack = { route = Route.ImgLabelSend },
                            ) {
                                ScopePickerScreen()
                            }

                            Route.ImgLabelRename -> SubScreen(
                                title = stringResource(R.string.rename_title),
                                onBack = { route = Route.ImgLabelSend },
                            ) {
                                ImgLabelRenameScreen()
                            }

                            Route.ImgLabelRecord -> SubScreen(
                                title = stringResource(R.string.record_title),
                                onBack = { route = Route.ImgLabelSend },
                            ) {
                                ImgLabelRecordScreen()
                            }

                            Route.ImgLabelFilter -> SubScreen(
                                title = stringResource(R.string.img_label_filter_title),
                                onBack = { route = Route.ImgLabelSend },
                            ) {
                                ImgLabelFilterScreen()
                            }

                            Route.ImgLabelRepeatWindow -> SubScreen(
                                title = stringResource(R.string.repeat_window_title),
                                onBack = { route = Route.ImgLabelSend },
                            ) {
                                ImgLabelRepeatWindowScreen()
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
