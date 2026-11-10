package io.github.MVennn.WxPicLabel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 唯一的搜索框（规范正本：docs/UI.md §9）。
 *
 * ## 为什么必须只有这一个
 *
 * 之前「发送记录」和「选择聊天」各写了一份 BasicTextField + decorationBox：
 * 一个竖向内边距 10dp、另一个 12dp；一个传了 keyboardOptions、另一个漏了；
 * 两处都没传 cursorBrush —— BasicTextField 的默认光标色是纯黑，
 * 深色主题下那个输入框是「看不见光标」的。各写各的，差异只会越攒越多。
 *
 * ## 形态（改这里之前先读 §9）
 *
 *  - 壳：12dp 圆角 + surfaceContainerHighest（0.9，聚焦时满色），**不画描边** ——
 *    和弹层输入框同一个判断（宿主已是卡片，再套一圈框会显脏）。
 *  - 高度固定 44dp：卡片里每一行都该等高，输入框不该随字号浮动。
 *  - 左内边距 12dp，于是调用方在卡内只给 4dp，4 + 12 = 16dp，
 *    输入文字与同卡其它行的标题落在同一条竖线上（§9.2）。
 *  - 清空按钮 40dp 触控区、图标用 [AppIcons.Close] 的细描边 ✕、中性色，
 *    尺寸交给图标自身（与行尾 ✕ / ＋ 同一套几何，docs/UI.md 图标表）。
 *
 * @param hint 占位文案，同时兼作无障碍标签（读屏不会只念「编辑框」）
 * @param clearLabel 清空按钮的无障碍文案，与 onClickLabel 共用同一句
 */
@Composable
internal fun AppSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                MiuixTheme.colorScheme.surfaceContainerHighest.copy(
                    alpha = if (focused) 1f else 0.9f,
                ),
            ),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            // 不传就是纯黑，深色主题下看不见 —— 这一行是硬约束，不是可选装饰
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            // 键盘上的「搜索」只负责收键盘：结果是边打边筛，不需要再点一次
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            textStyle = TextStyle(
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 15.sp,
            ),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .padding(start = 12.dp, end = if (value.isEmpty()) 12.dp else 44.dp)
                .semantics { contentDescription = hint },
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 15.sp,
                    )
                }
                inner()
            },
        )

        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = clearLabel) { onValueChange("") },
                contentAlignment = Alignment.Center,
            ) {
                TintedIcon(
                    imageVector = AppIcons.Close,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    contentDescription = clearLabel,
                )
            }
        }
    }
}
