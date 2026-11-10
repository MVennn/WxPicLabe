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
import androidx.compose.runtime.LaunchedEffect
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
import io.github.MVennn.WxPicLabel.feature.imglabel.TextFilter
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发送设置 → 黑白名单」三级页：决定哪张图带文字发、哪张图只发图。
 *
 * 名单是**一个开关**：关 = 白名单（命中才发文字），开 = 黑名单（命中只发图）。
 * 副标题跟着开关换一句话，说清此刻会怎么发，不再单开说明卡。
 *
 * 关键字是**一条一条攒出来的**：列表里每行一条，右端 ✕ 删掉这一条；
 * 「新建关键字」从屏幕底部弹出输入弹层（输入框 + 取消 + 确定），确定即落盘。
 * 不再有整块文本编辑区，也就没有「保存」这一步 —— 增删都是即时生效。
 *
 * 判定逻辑全在 [TextFilter] 里，与微信进程侧调用同一份代码；本页只改文字，
 * 被筛掉的图照样发出去，只是不带那行文件名。
 */
@Composable
internal fun ImgLabelFilterScreen() {
    val ctx = LocalContext.current
    val dialogs = LocalDialogController.current

    val savedMode by rememberStringPref(
        ConfigKeys.IMG_LABEL_FILTER_MODE,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_MODE,
    )
    val savedRules by rememberStringPref(
        ConfigKeys.IMG_LABEL_FILTER_RULES,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_RULES,
    )
    val ignoreCase by rememberBoolPref(
        ConfigKeys.IMG_LABEL_FILTER_IGNORE_CASE,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_IGNORE_CASE,
    )
    val matchOriginal by rememberBoolPref(
        ConfigKeys.IMG_LABEL_FILTER_MATCH_ORIGINAL,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_MATCH_ORIGINAL,
    )

    // 名单只有两档，旧版本那个「关闭」在这一页没有位置：进来就升成白名单。
    // 空表本来就全部放行，升级不改变任何一张图的发法；不下这一手，
    // 开关会显示「关」（= 白名单）而配置还是 off —— 用户加了关键字却不生效。
    LaunchedEffect(Unit) {
        if (TextFilter.parseMode(savedMode) == TextFilter.MODE_OFF) {
            ModulePreferences.edit {
                putString(ConfigKeys.IMG_LABEL_FILTER_MODE, ConfigKeys.FILTER_MODE_WHITELIST)
            }
        }
    }

    val black = TextFilter.parseMode(savedMode) == TextFilter.MODE_BLACKLIST
    val rules = remember(savedRules, ignoreCase) { TextFilter.parseRules(savedRules, ignoreCase) }

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun writeRules(text: String) {
        ModulePreferences.edit { putString(ConfigKeys.IMG_LABEL_FILTER_RULES, text) }
    }

    fun addRule(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        if (rules.any { it.source.equals(value, ignoreCase) }) {
            toast(ctx.getString(R.string.filter_new_rule_exists))
            return
        }
        // 追加到表尾：新加的在最下面，与列表顺序一致
        val base = savedRules.trimEnd('\n', '\r')
        writeRules(if (base.isEmpty()) value else base + "\n" + value)
        toast(ctx.getString(R.string.filter_new_rule_added))
    }

    fun removeRule(rule: TextFilter.Rule) {
        // 只摘这一行：表里别的行（含 # 注释行与空行）原样留着
        val lines = savedRules.lines().toMutableList()
        val index = lines.indexOfFirst { it.trim().equals(rule.source, ignoreCase) }
        if (index < 0) return
        lines.removeAt(index)
        writeRules(lines.joinToString("\n").trimEnd('\n'))
        toast(ctx.getString(R.string.filter_rule_deleted))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.filter_section_mode))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = black,
                onCheckedChange = { on ->
                    ModulePreferences.edit {
                        putString(
                            ConfigKeys.IMG_LABEL_FILTER_MODE,
                            if (on) ConfigKeys.FILTER_MODE_BLACKLIST else ConfigKeys.FILTER_MODE_WHITELIST,
                        )
                    }
                },
                title = stringResource(R.string.filter_switch_title),
                summary = stringResource(
                    if (black) R.string.filter_switch_summary_black else R.string.filter_switch_summary_white,
                ),
            )
        }

        SmallTitle(stringResource(R.string.filter_section_match))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = ignoreCase,
                onCheckedChange = { v ->
                    ModulePreferences.edit {
                        putBoolean(ConfigKeys.IMG_LABEL_FILTER_IGNORE_CASE, v)
                    }
                },
                title = stringResource(R.string.filter_ignore_case),
            )
            GsSwitchPreference(
                checked = matchOriginal,
                onCheckedChange = { v ->
                    ModulePreferences.edit {
                        putBoolean(ConfigKeys.IMG_LABEL_FILTER_MATCH_ORIGINAL, v)
                    }
                },
                title = stringResource(R.string.filter_match_original),
                summary = stringResource(R.string.filter_match_original_summary),
            )
        }

        SmallTitle(stringResource(R.string.filter_rules_title))
        val deleteLabel = stringResource(R.string.filter_rule_delete)
        Card(modifier = Modifier.cardPadding()) {
            if (rules.isEmpty()) {
                // 空表 = 没有一条命中，两种模式此时都等于「全部照常发文字」
                BasicComponent(
                    title = stringResource(R.string.filter_rules_empty),
                    summary = stringResource(R.string.filter_rules_empty_summary),
                )
            }
            rules.forEach { rule ->
                BasicComponent(
                    title = rule.source,
                    endActions = {
                        // 行尾 ✕ 按 docs/UI.md 的图标规范：40dp 触控区 + 贴右缘 + 中性色，
                        // 尺寸交给图标自身（10×10.7dp），与行尾箭头同宽同列，不在这里塞 size。
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = deleteLabel) { removeRule(rule) },
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

        // 规则列表与动作行分两张卡：相邻卡之间固定 12dp，与记录页同口径（docs/UI.md §7 第 9 条）
        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.filter_new_rule),
                endActions = {
                    // 与规则行的 ✕ 同一套几何（40dp 触控区、贴右缘、同画布同笔画），只换颜色。
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
                    dialogs?.showTextInput(
                        TextInputDialogSpec(
                            title = ctx.getString(R.string.filter_new_rule),
                            message = "",
                            initial = "",
                            hint = ctx.getString(R.string.filter_new_rule_hint),
                            confirmText = ctx.getString(R.string.action_confirm),
                            cancelText = ctx.getString(R.string.action_cancel),
                            onConfirm = { addRule(it) },
                            validate = { v ->
                                if (v.isEmpty()) {
                                    ctx.getString(R.string.filter_new_rule_empty)
                                } else {
                                    null
                                }
                            },
                        ),
                    )
                },
            )
            if (rules.isNotEmpty()) {
                BasicComponent(
                    title = stringResource(R.string.filter_clear_rules),
                    // 清空是批量动作，先过贴底确认弹层
                    onClick = {
                        dialogs?.showConfirm(
                            ConfirmDialogSpec(
                                title = ctx.getString(R.string.filter_clear_confirm_title),
                                message = ctx.getString(R.string.filter_clear_confirm_message),
                                confirmText = ctx.getString(R.string.action_confirm),
                                cancelText = ctx.getString(R.string.action_cancel),
                                onConfirm = {
                                    writeRules("")
                                    toast(ctx.getString(R.string.filter_cleared))
                                },
                            ),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
