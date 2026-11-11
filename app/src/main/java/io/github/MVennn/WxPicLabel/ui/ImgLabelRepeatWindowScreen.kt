package io.github.MVennn.WxPicLabel.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import io.github.MVennn.WxPicLabel.feature.imglabel.RepeatWindow
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发送设置 → 重复图片 → 追加次数设置」三级页。
 *
 * ## 这一个设置管什么
 *
 * 重复图片的 ` +N` 只在**落在时段里发出的那几张**上累计：时段外发的图既不进
 * 计数表、也不占号。所以「今天三点半前发过一次、三点半后再发」不会变成 +2 ——
 * 三点半前那次根本没被记过，三点半后那次是第 1 次，照发原文。
 *
 * ## 形态与重命名页一致
 *
 * 第一张卡每行一个时段（左边 `15:30-17:30`、右边时长），行尾 ✕ 删掉这一段；
 * 第二张卡（与第一张隔 12dp）是「新建时段」，点整行从屏幕底部弹出**上下滑动的
 * 时/分滚轮**（iOS 闹钟那种），确认即落盘 —— 既不打字、也不会出现「格式不对」。
 * 落盘用的仍是 [RepeatWindow] 那一份区间语义，页面不另写一套判定。
 *
 * **一段都没有 = 全天追加**，这是默认值，也是「恢复全天」的结果：删掉最后一段，
 * 页面自然回到「全天」那一行。
 *
 * 增、删、恢复全天都是即时生效，没有「保存」按钮 —— 写的就是
 * [ConfigKeys.IMG_LABEL_REPEAT_WINDOW] 这一条字符串（规范写法
 * `15:30-17:30,20:00-21:00`，全天是空串）。
 */
@Composable
internal fun ImgLabelRepeatWindowScreen() {
    val ctx = LocalContext.current
    val dialogs = LocalDialogController.current

    val saved by rememberStringPref(
        ConfigKeys.IMG_LABEL_REPEAT_WINDOW,
        ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_WINDOW,
    )
    // 微信进程侧解析的就是这份字符串：页面显示几段，发图时就按几段判定
    val window = remember(saved) { RepeatWindow.parse(saved) }
    val spans = window.spans()

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun write(raw: String) {
        ModulePreferences.edit { putString(ConfigKeys.IMG_LABEL_REPEAT_WINDOW, raw) }
    }

    /** 时段的显示副标题：持续多久；跨零点的段额外标一句，否则用户会以为只生效一小时。 */
    fun durationOf(span: RepeatWindow.Span): String {
        val minutes = span.length()
        val hours = minutes / 60
        val rest = minutes % 60
        val duration = when {
            hours > 0 && rest > 0 -> ctx.getString(R.string.repeat_window_span_hm, hours, rest)
            hours > 0 -> ctx.getString(R.string.repeat_window_span_h, hours)
            else -> ctx.getString(R.string.repeat_window_span_m, rest)
        }
        return if (span.start > span.end) {
            duration + ctx.getString(R.string.repeat_window_cross_midnight)
        } else {
            duration
        }
    }

    fun addSpan(startMinute: Int, endMinute: Int) {
        val span = RepeatWindow.Span.of(startMinute, endMinute) ?: return
        write(window.plus(span).raw())
        toast(ctx.getString(R.string.repeat_window_added, span.text()))
    }

    fun removeSpan(index: Int) {
        write(window.minus(index).raw())
        toast(ctx.getString(R.string.repeat_window_deleted))
    }

    /** 滚轮不会给出「格式不对」，只剩两种错：起止落在同一分钟、与已有段重叠。 */
    fun validateSpan(startMinute: Int, endMinute: Int): String? {
        val span = RepeatWindow.Span.of(startMinute, endMinute)
            ?: return ctx.getString(R.string.repeat_window_same_time)
        if (spans.any { overlaps(it, span) }) {
            return ctx.getString(R.string.repeat_window_conflict)
        }
        return null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.repeat_window_section))
        val deleteLabel = stringResource(R.string.repeat_window_delete)
        Card(modifier = Modifier.cardPadding()) {
            if (spans.isEmpty()) {
                // 空列表不是「没加载出来」，而是「全天都算」—— 这句话必须写在卡里
                BasicComponent(
                    title = stringResource(R.string.repeat_window_all_day),
                    summary = stringResource(R.string.repeat_window_all_day_summary),
                )
            }
            spans.forEachIndexed { index, span ->
                BasicComponent(
                    title = span.text(),
                    summary = durationOf(span),
                    endActions = {
                        // 行尾 ✕：40dp 触控区 + 贴右缘 + 中性色，与重命名页同口径
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = deleteLabel) { removeSpan(index) },
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            TintedIcon(
                                imageVector = AppIcons.Close,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                contentDescription = deleteLabel,
                            )
                        }
                    },
                )
            }
        }

        // 列表与动作行分两张卡：相邻卡之间固定 12dp（docs/UI.md §7 第 9 条）
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.repeat_window_new),
                endActions = {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        TintedIcon(
                            imageVector = AppIcons.PlusThin,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = {
                    // 初始值取「现在所在的整点 + 2 小时」：最常见的用法是圈一段时间，
                    // 滑一格就到位 —— 从 00:00 起划两整圈反而费劲。
                    val nowMinute = Calendar.getInstance().let {
                        it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
                    }
                    val startMinute = nowMinute / 60 * 60
                    val endMinute = (startMinute + 120) % RepeatWindow.DAY_MINUTES
                    dialogs?.showTimeRange(
                        TimeRangeDialogSpec(
                            title = ctx.getString(R.string.repeat_window_new),
                            message = ctx.getString(R.string.repeat_window_new_tip),
                            startMinute = startMinute,
                            endMinute = endMinute,
                            startLabel = ctx.getString(R.string.repeat_window_start),
                            endLabel = ctx.getString(R.string.repeat_window_end),
                            confirmText = ctx.getString(R.string.action_confirm),
                            cancelText = ctx.getString(R.string.action_cancel),
                            validate = { s, e -> validateSpan(s, e) },
                            onConfirm = { s, e -> addSpan(s, e) },
                        ),
                    )
                },
            )
            if (spans.isNotEmpty()) {
                BasicComponent(
                    title = stringResource(R.string.repeat_window_restore_all_day),
                    // 清空是批量动作，先过贴底确认弹层
                    onClick = {
                        dialogs?.showConfirm(
                            ConfirmDialogSpec(
                                title = ctx.getString(R.string.repeat_window_restore_title),
                                message = ctx.getString(R.string.repeat_window_restore_message),
                                confirmText = ctx.getString(R.string.action_confirm),
                                cancelText = ctx.getString(R.string.action_cancel),
                                onConfirm = {
                                    write("")
                                    toast(ctx.getString(R.string.repeat_window_restored))
                                },
                            ),
                        )
                    },
                )
            }
        }

        SmallTitle(stringResource(R.string.repeat_window_note_section))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.repeat_window_note_title),
                summary = stringResource(R.string.repeat_window_note_body),
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/**
 * 两段时段是否重叠（用于新建时拦一次明显写错的输入）。
 *
 * 跨零点的段（如 `23:00-01:00`）先拆成 `23:00-24:00` 与 `00:00-01:00`
 * 两截再比，否则「今天 23:30 写到明天 00:30」与「00:00-00:30」这种重叠会漏判。
 */
private fun overlaps(a: RepeatWindow.Span, b: RepeatWindow.Span): Boolean {
    val left = splitAtMidnight(a)
    val right = splitAtMidnight(b)
    return left.any { x -> right.any { y -> x[0] < y[1] && y[0] < x[1] } }
}

/** 拆成当天分钟数区间；端点是 [start, end)，正好拼成 24 小时。 */
private fun splitAtMidnight(span: RepeatWindow.Span): List<IntArray> =
    if (span.start < span.end) {
        listOf(intArrayOf(span.start, span.end))
    } else {
        listOf(
            intArrayOf(span.start, RepeatWindow.DAY_MINUTES),
            intArrayOf(0, span.end),
        )
    }
