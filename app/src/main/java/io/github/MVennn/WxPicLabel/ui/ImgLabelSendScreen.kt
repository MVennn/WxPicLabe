package io.github.MVennn.WxPicLabel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import io.github.MVennn.WxPicLabel.feature.imglabel.RenameRules
import io.github.MVennn.WxPicLabel.feature.imglabel.RepeatWindow
import io.github.MVennn.WxPicLabel.feature.imglabel.TextFilter
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「增加 文件名 开关 → 发送设置」二级页。
 *
 * 只放**总开关之外**的参数（一级页只有开关，约定见 [FeatureTab]）：
 *
 *  - 「发送范围」：是否只对勾选的会话生效 + 名单入口
 *  - 「发送提示」：进度 Toast 与发送期间屏幕常亮
 *  - 「重复图片」：同名图片再次发送时，在文字后追加 ` +2`、` +3`……
 *  - 「重命名」「记录」「追加次数设置」：三个三级页入口（规则编辑 / 记录查看与清空 / 时段编辑）
 *
 * 记录列表与「清空」都在三级页 `ImgLabelRecordScreen` 里 —— 计数存在微信进程
 * 自己的 SharedPreferences，模块 App 读不到，列表靠 `/records` 回传通道拿。
 */
@Composable
internal fun ImgLabelSendScreen(
    onOpenScope: () -> Unit = {},
    onOpenRecord: () -> Unit = {},
    onOpenRename: () -> Unit = {},
    onOpenFilter: () -> Unit = {},
    onOpenRepeatWindow: () -> Unit = {},
) {
    val scrollState = rememberScrollState()

    val onlySelected by rememberBoolPref(
        ConfigKeys.IMG_LABEL_SCOPE_ONLY_SELECTED,
        ConfigKeys.DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED,
    )
    val scopeIds by rememberStringSetPref(ConfigKeys.IMG_LABEL_SCOPE_IDS, emptySet())

    val repeat by rememberBoolPref(
        ConfigKeys.IMG_LABEL_REPEAT_COUNT,
        ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_COUNT,
    )
    val repeatWindowRaw by rememberStringPref(
        ConfigKeys.IMG_LABEL_REPEAT_WINDOW,
        ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_WINDOW,
    )
    // 入口行按微信进程侧同一份解析报时段，避免「显示的和生效的不一致」
    val repeatWindow = remember(repeatWindowRaw) { RepeatWindow.parse(repeatWindowRaw) }

    val hudToast by rememberBoolPref(
        ConfigKeys.IMG_LABEL_PROGRESS_TOAST,
        ConfigKeys.DEFAULT_IMG_LABEL_PROGRESS_TOAST,
    )
    val hudKeepOn by rememberBoolPref(
        ConfigKeys.IMG_LABEL_KEEP_SCREEN_ON,
        ConfigKeys.DEFAULT_IMG_LABEL_KEEP_SCREEN_ON,
    )

    val filterMode by rememberStringPref(
        ConfigKeys.IMG_LABEL_FILTER_MODE,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_MODE,
    )
    val filterRules by rememberStringPref(
        ConfigKeys.IMG_LABEL_FILTER_RULES,
        ConfigKeys.DEFAULT_IMG_LABEL_FILTER_RULES,
    )
    // 条数只看关键字表，与大小写开关无关；模式为「关闭」时那一行直接显示「关闭」
    val filterRuleCount = TextFilter.parseRules(filterRules, true).size

    val renameRules by rememberStringPref(
        ConfigKeys.IMG_LABEL_RENAME_RULES,
        ConfigKeys.DEFAULT_IMG_LABEL_RENAME_RULES,
    )
    // 入口行按「生效的规则条数」报数：与重命名页列表同一份解析
    val renameCount = RenameRules.parse(renameRules).size


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.img_label_section_scope))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = onlySelected,
                onCheckedChange = { v ->
                    ModulePreferences.edit {
                        putBoolean(ConfigKeys.IMG_LABEL_SCOPE_ONLY_SELECTED, v)
                    }
                },
                title = stringResource(R.string.img_label_scope_switch),
            )
            BasicComponent(
                title = stringResource(R.string.img_label_scope_entry),
                summary = stringResource(R.string.img_label_scope_entry_summary, scopeIds.size),
                endActions = { ArrowRightIcon() },
                onClick = onOpenScope,
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_hud))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = hudToast,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.IMG_LABEL_PROGRESS_TOAST, v) }
                },
                title = stringResource(R.string.img_label_hud_toast_switch),
            )
            GsSwitchPreference(
                checked = hudKeepOn,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.IMG_LABEL_KEEP_SCREEN_ON, v) }
                },
                title = stringResource(R.string.img_label_hud_keepon_switch),
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_repeat))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = repeat,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.IMG_LABEL_REPEAT_COUNT, v) }
                },
                title = stringResource(R.string.img_label_repeat_switch),
            )
            BasicComponent(
                title = stringResource(R.string.repeat_window_entry),
                summary = if (repeatWindow.isAllDay) {
                    stringResource(R.string.repeat_window_entry_all_day)
                } else {
                    stringResource(
                        R.string.repeat_window_entry_summary,
                        repeatWindow.spans().joinToString("、") { it.text() },
                    )
                },
                endActions = { ArrowRightIcon() },
                onClick = onOpenRepeatWindow,
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_rename))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.rename_entry),
                summary = if (renameCount == 0) {
                    stringResource(R.string.rename_entry_summary_empty)
                } else {
                    stringResource(R.string.rename_entry_summary, renameCount)
                },
                endActions = { ArrowRightIcon() },
                onClick = onOpenRename,
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_filter))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.img_label_filter_entry),
                summary = when (TextFilter.parseMode(filterMode)) {
                    TextFilter.MODE_WHITELIST ->
                        stringResource(R.string.img_label_filter_summary_whitelist, filterRuleCount)
                    TextFilter.MODE_BLACKLIST ->
                        stringResource(R.string.img_label_filter_summary_blacklist, filterRuleCount)
                    else -> stringResource(R.string.img_label_filter_summary_off)
                },
                endActions = { ArrowRightIcon() },
                onClick = onOpenFilter,
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_record))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.record_entry),
                endActions = { ArrowRightIcon() },
                onClick = onOpenRecord,
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
