package io.github.MVennn.WxPicLabel.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
 * 极简路由：一级首页 + 三个二级页（免责声明 / 开源声明 / 发送设置）。
 *
 * 只有两层。本仓的二级页都是**只读长文**，没有编辑态也没有三级页，
 * 所以不保留 GSAuxiliary 那套「二级 / 三级各一条进度」的双层动画 ——
 * 多出来的一层在只有两层页面时是纯开销。
 *
 * 新增二级页 = 在 [Route] 加一个 data object + 在 [RouteSaver] 的 save / restore
 * 各加一个分支（when 是穷尽的，漏写会直接编译失败）+ 在 [MainScreen] 的
 * when (shownSub) 里挂上页面。
 */
internal sealed interface Route {
    data object Home : Route
    data object Disclaimer : Route
    data object Notice : Route
    /** 「发送图片文件名 → 发送设置」：同名图片重复发送时的次数追加与记录清空 */
    data object ImgLabelSend : Route
    /** 「发送设置 → 选择好友与群聊」：会话白名单的勾选页 */
    data object ImgLabelScope : Route
}

/** 二级页按返回时该回哪一层（白名单页是发送设置的子页）。 */
private fun backTarget(r: Route): Route = when (r) {
    Route.ImgLabelScope -> Route.ImgLabelSend
    else -> Route.Home
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
    }

    override fun restore(value: String): Route = when (value) {
        "disclaimer" -> Route.Disclaimer
        "notice" -> Route.Notice
        "img_label_send" -> Route.ImgLabelSend
        "img_label_scope" -> Route.ImgLabelScope
        else -> Route.Home
    }
}

/**
 * 两层叠放：一级首页常驻作底衬，二级页右滑盖上。
 *
 * | 动作 | 二级页进度 |
 * |---|---|
 * | 进二级页 | 0 → 1 |
 * | 二级页返回 | 1 → 0 |
 *
 * 可预测返回手势直接推动这一条进度，滑到哪就是哪。
 */
@Composable
internal fun MainScreen(initialRoute: Route = Route.Home) {
    val scope = rememberCoroutineScope()

    // Tab 与当前路由必须**跨 Activity 重建**存活：转屏 / 分屏 / 折叠屏都会重建 Activity，
    // 用 remember 的话「在免责声明页转个屏」就直接回到首页。Tab 是 enum（Serializable），自动存盘即可。
    var currentTab by rememberSaveable { mutableStateOf(Tab.MODULE) }
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(initialRoute) }

    // 首次启动的免责声明同意门槛：没同意就必须先同意。
    //
    // 条件是「未同意 **且** 在首页」：点弹窗里的链接会跳到免责声明二级页，
    // 那一页要能正常看；回到首页时条件重新成立，弹窗自然又出来 ——
    // 不用额外的「暂不显示」标志位，状态越少越不容易出错。
    // 用 local 版：同意状态是**本机界面状态**，不进 remote preferences ——
    // 否则后端异步切换会让它在启动时先 false 后 true，弹窗「闪一下就没」。
    // 详见 ModulePreferences.getLocalBoolean 的注释。
    val agreedDisclaimer by rememberLocalBoolPref(
        ConfigKeys.DISCLAIMER_ACCEPTED,
        ConfigKeys.DEFAULT_DISCLAIMER_ACCEPTED,
    )
    val showAgreement = !agreedDisclaimer && route == Route.Home

    // 「这一层此刻该画哪一页」在**组合期**就算出来，不放进 LaunchedEffect：
    // 进入时当帧就位 —— 放 effect 里会晚一帧（先空一帧再开始滑，看着像打开慢）；
    // 退出动画期间 route 已经不是它了，但那一层还得继续画完 —— 靠 shownSub 记住。
    var shownSub by remember { mutableStateOf(route) }
    if (route != Route.Home && route != shownSub) shownSub = route

    val predictiveBack = rememberPredictiveBack()
    // 二级页滑动进度：0 = 完全滑出屏幕右侧，1 = 完全盖住首页
    val subProgress = remember { Animatable(if (route == Route.Home) 0f else 1f) }

    LaunchedEffect(route) {
        subProgress.animateTo(
            if (route == Route.Home) 0f else 1f,
            tween(RouteAnimMillis),
        )
    }

    if (predictiveBack) {
        PredictiveBackHandler(enabled = route != Route.Home) { flow ->
            try {
                flow.collect { subProgress.snapTo(1f - it.progress) }
                route = backTarget(route)
            } catch (e: CancellationException) {
                // 手势取消：滑回去
                scope.launch { subProgress.animateTo(1f, tween(200)) }
                throw e
            }
        }
    } else {
        BackHandler(enabled = route != Route.Home) { route = backTarget(route) }
    }

    // 滑动进度**只在图层块里读**，组合期不读。
    //
    // 组合期读动画值（原来的 `val p = subProgress.value`）会让整页在 300ms 滑动里
    // 每帧重组一次：MainScreen → HomeScaffold → 当前板块整棵树，全部跟着跑一遍。
    // 实机上这就是「左右滑动卡」的主因之一；模拟器分辨率低、CPU 富余，看不出来。
    // 现在组合期只留一个折叠成布尔的门槛判断（决定二级页要不要挂着），
    // 进度本身在 graphicsLayer 的 lambda 里读 —— 那是图层阶段，只失效绘制，不重组。
    val subMounted by remember { derivedStateOf { subProgress.value > 0.001f } }
    // 页面底色 = colorScheme.surface，**不是** colorScheme.background。
    //
    // Miuix 的取值很反直觉（0.9.3 实测）：
    //   background 与 surfaceContainer（Card 默认色）完全同色（深色都是 #242424）。
    // 页面一旦用 background，卡片就和页面糊成一片、整页看不出分组。
    // 正确的页面底色是 surface：深色纯黑 / 浅色浅灰，卡片才会跳出来。
    val background = MiuixTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- 第 0 层：底色层（不参与任何手势动画）----
        //
        // 可预测返回会把一级页整体缩到 0.94 并淡到 0.65，缩下去以后四周会露出这一层。
        // 它必须**在最底层、且不挂任何 graphicsLayer 变换**，否则露出的是窗口底色，
        // 看着就是「四周一圈灰边」。
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
                    // 图层阶段读进度：只重绘、不重组（见上方 subMounted 的注释）
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
                    // p = 1 时完全覆盖（位移 0），p = 0 时滑到屏幕右侧之外
                    .graphicsLayer { translationX = size.width * (1f - subProgress.value) }
                    .background(background),
            ) {
                // 同一层内的页间切换（发送设置 ↔ 好友选择）用左右滑动过渡；
                // 首次从首页盖上时整层已经在滑，这里不再叠加内容动画。
                AnimatedContent(
                    targetState = shownSub,
                    transitionSpec = {
                        when {
                            initialState == Route.Home || targetState == Route.Home ->
                                EnterTransition.None togetherWith ExitTransition.None

                            backTarget(initialState) == targetState ->
                                // 返回：旧页滑出向右，新页从左侧跟进
                                (slideInHorizontally(tween(RouteAnimMillis)) { -it / 3 }
                                        + fadeIn(tween(RouteAnimMillis))) togetherWith
                                        (slideOutHorizontally(tween(RouteAnimMillis)) { it }
                                                + fadeOut(tween(RouteAnimMillis)))

                            else ->
                                // 进入更深一层：新页从右侧滑入，旧页向左退 1/3
                                (slideInHorizontally(tween(RouteAnimMillis)) { it }
                                        + fadeIn(tween(RouteAnimMillis))) togetherWith
                                        (slideOutHorizontally(tween(RouteAnimMillis)) { -it / 3 }
                                                + fadeOut(tween(RouteAnimMillis)))
                        }
                    },
                    label = "subPage",
                ) { sub ->
                    when (sub) {
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
                            ImgLabelSendScreen(onOpenScope = { route = Route.ImgLabelScope })
                        }

                        Route.ImgLabelScope -> SubScreen(
                            title = stringResource(R.string.img_label_scope_entry),
                            onBack = { route = Route.ImgLabelSend },
                        ) {
                            ScopePickerScreen()
                        }

                        Route.Home -> Unit
                    }
                }
            }
        }
    }
}
