package io.github.MVennn.WxPicLabel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.MVennn.WxPicLabel.feature.imglabel.RenameRules
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发送设置 → 重命名规则」三级页：一行一条 `原名 -> 新名`。
 *
 * ## 形态与黑白名单页一致
 *
 * 规则是**一条一条攒出来的**：第一张卡每行一条（左边原名、右边会发出去的文字），
 * 行尾 ✕ 删掉这一条；第二张卡（与第一张之间 12dp）是「新建规则」——
 * 点整行从屏幕底部弹输入弹层，确定即落盘；规则非空时第二张卡多一行「清空规则」。
 * 没有整块文本编辑区，也就没有「保存」这一步：增、删、清空都是即时生效。
 *
 * 为什么不再给一个大文本框：文本框里的人看不见「到底几条、哪条写错了」，
 * 而且整表只有一个保存点，改一条要滚回去按保存；逐条列表把「当前生效的规则」
 * 直接摆出来，微信进程侧解析的就是这些行。
 *
 * ## 解析只有一份
 *
 * 列表用的就是微信进程侧那份 [RenameRules.parse]（分隔符 `->` / `=>` / `=` / 制表符，
 * `#` 注释与空行忽略），所以页面上看到几条、分别是什么，就是发图时真正生效的那些；
 * 页面里不另写一套解析，避免「显示的和生效的不一致」。
 *
 * 新建时统一落成规范写法 `原名 -> 新名`，用户输入里的 `=`、制表符、`=>` 都会被归一化。
 */
@Composable
internal fun ImgLabelRenameScreen() {
    val ctx = LocalContext.current
    val dialogs = LocalDialogController.current

    val saved by rememberStringPref(
        ConfigKeys.IMG_LABEL_RENAME_RULES,
        ConfigKeys.DEFAULT_IMG_LABEL_RENAME_RULES,
    )
    // 表里的行序就是列表顺序（parse 用 LinkedHashMap）
    val rules = remember(saved) { RenameRules.parse(saved) }

    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    fun write(text: String) {
        ModulePreferences.edit { putString(ConfigKeys.IMG_LABEL_RENAME_RULES, text) }
    }

    /** 追加到表尾：新加的在最下面，与列表顺序一致。 */
    fun addRule(raw: String) {
        val entry = RenameRules.parse(raw.trim()).entries.firstOrNull() ?: return
        val line = entry.key + " -> " + entry.value
        val base = saved.trimEnd('\n', '\r')
        write(if (base.isEmpty()) line else base + "\n" + line)
        toast(ctx.getString(R.string.rename_rule_added))
    }

    /** 只摘这一行：注释行、空行、别的规则原样留着。 */
    fun removeRule(from: String) {
        val lines = saved.lines().toMutableList()
        val index = lines.indexOfFirst { line ->
            val s = line.trim()
            s.isNotEmpty() && !s.startsWith("#") && RenameRules.parse(s).keys.firstOrNull() == from
        }
        if (index < 0) return
        lines.removeAt(index)
        write(lines.joinToString("\n").trimEnd('\n'))
        toast(ctx.getString(R.string.rename_rule_deleted))
    }

    /**
     * 导入一律「就地合并」：已有的原名跳过，只把新的追加到表尾。
     *
     * 不用整体替换 —— 那会把用户攒了很久的规则一次抹掉，而「导入」原本只是想加点新的。
     * 返回 (追加条数, 跳过条数) 用于回显。
     */
    fun mergeRows(raw: String): Pair<Int, Int> {
        val incoming = RenameRules.parse(raw)
        if (incoming.isEmpty()) return 0 to 0
        val base = saved.trimEnd('\n', '\r')
        val existing = RenameRules.parse(base).keys.toMutableSet()
        val sb = StringBuilder(base)
        var added = 0
        var skipped = 0
        incoming.forEach { (from, to) ->
            if (!existing.add(from)) {
                skipped++
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(from).append(" -> ").append(to)
                added++
            }
        }
        if (added > 0) write(sb.toString())
        return added to skipped
    }

    fun toastImport(result: Pair<Int, Int>) {
        val added = result.first
        val skipped = result.second
        toast(
            when {
                added > 0 && skipped > 0 ->
                    ctx.getString(R.string.rename_imported_counts, added, skipped)
                added > 0 -> ctx.getString(R.string.rename_imported_count, added)
                else -> ctx.getString(R.string.rename_imported_none)
            },
        )
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val read = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            toastImport(if (read.isNullOrEmpty()) 0 to 0 else mergeRows(read))
        } catch (t: Throwable) {
            toast(ctx.getString(R.string.rename_failed, t.message ?: ""))
        }
    }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(saved.toByteArray()) }
            toast(ctx.getString(R.string.rename_exported))
        } catch (t: Throwable) {
            toast(ctx.getString(R.string.rename_failed, t.message ?: ""))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.rename_section_rules))
        val deleteLabel = stringResource(R.string.rename_rule_delete)
        Card(modifier = Modifier.cardPadding()) {
            if (rules.isEmpty()) {
                // 空表 = 一律发原文件名，这句话必须说清，否则空卡看着像没加载出来
                BasicComponent(
                    title = stringResource(R.string.rename_empty),
                    summary = stringResource(R.string.rename_empty_summary),
                )
            }
            rules.forEach { (from, to) ->
                BasicComponent(
                    title = from,
                    summary = to,
                    endActions = {
                        // 行尾 ✕ 按 docs/UI.md 的图标规范：40dp 触控区 + 贴右缘 + 中性色，
                        // 尺寸交给图标自身（10x10.7dp），与行尾箭头同宽同列，不在这里塞 size。
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(onClickLabel = deleteLabel) { removeRule(from) },
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
                title = stringResource(R.string.rename_new_rule),
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
                            title = ctx.getString(R.string.rename_new_rule),
                            message = ctx.getString(R.string.rename_new_rule_tip),
                            initial = "",
                            hint = ctx.getString(R.string.rename_new_rule_hint),
                            confirmText = ctx.getString(R.string.action_confirm),
                            cancelText = ctx.getString(R.string.action_cancel),
                            onConfirm = { addRule(it) },
                            validate = { v ->
                                val text = v.trim()
                                when {
                                    text.isEmpty() -> ctx.getString(R.string.rename_new_rule_empty)
                                    text.lines().count { it.trim().isNotEmpty() } > 1 ->
                                        ctx.getString(R.string.rename_new_rule_single)
                                    else -> {
                                        val key = RenameRules.parse(text).keys.firstOrNull()
                                        when {
                                            key == null -> ctx.getString(R.string.rename_new_rule_format)
                                            rules.containsKey(key) ->
                                                ctx.getString(R.string.rename_new_rule_exists)
                                            else -> null
                                        }
                                    }
                                }
                            },
                        ),
                    )
                },
            )
            if (rules.isNotEmpty()) {
                BasicComponent(
                    title = stringResource(R.string.rename_clear_rules),
                    // 清空是批量动作，先过贴底确认弹层
                    onClick = {
                        dialogs?.showConfirm(
                            ConfirmDialogSpec(
                                title = ctx.getString(R.string.rename_clear_confirm_title),
                                message = ctx.getString(R.string.rename_clear_confirm_message),
                                confirmText = ctx.getString(R.string.action_confirm),
                                cancelText = ctx.getString(R.string.action_cancel),
                                onConfirm = {
                                    write("")
                                    toast(ctx.getString(R.string.rename_cleared))
                                },
                            ),
                        )
                    },
                )
            }
        }

        SmallTitle(stringResource(R.string.rename_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.rename_import),
                summary = stringResource(R.string.rename_import_summary),
                endActions = { ArrowRightIcon() },
                onClick = { openDoc.launch(arrayOf("text/plain", "text/*")) },
            )
            BasicComponent(
                title = stringResource(R.string.rename_paste),
                summary = stringResource(R.string.rename_paste_summary),
                onClick = {
                    val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (clip.isNullOrEmpty()) {
                        toast(ctx.getString(R.string.rename_clipboard_empty))
                    } else {
                        toastImport(mergeRows(clip))
                    }
                },
            )
            BasicComponent(
                title = stringResource(R.string.rename_export),
                endActions = { ArrowRightIcon() },
                onClick = { createDoc.launch("wxpiclabel-rename.txt") },
            )
            BasicComponent(
                title = stringResource(R.string.rename_copy),
                // 复制是就地生效，没有后续界面 —— 不带箭头
                onClick = {
                    cm.setPrimaryClip(ClipData.newPlainText("wxpiclabel-rename", saved))
                    toast(ctx.getString(R.string.rename_copied))
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
