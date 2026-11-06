package io.github.MVennn.WxPicLabel.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发送设置 → 重命名规则」二级页：一行一条 `原名 -> 新名`。
 *
 * 微信进程按**文件名（去扩展名）**精确匹配，命中就把发出去的文字换成右边那段；
 * 没命中照旧发原名。整表存成一个字符串（[ConfigKeys.IMG_LABEL_RENAME_RULES]），
 * 所以「导入 / 导出」就是把这个文本框的内容整体读进来、整体写出去。
 *
 * 解析在微信进程侧做（`feature/imglabel/RenameRules`），这里只负责编辑与保存。
 */
@Composable
internal fun ImgLabelRenameScreen() {
    val ctx = LocalContext.current
    val saved by rememberStringPref(
        ConfigKeys.IMG_LABEL_RENAME_RULES,
        ConfigKeys.DEFAULT_IMG_LABEL_RENAME_RULES,
    )
    // 只在本页编辑，不逐键写回 —— 保存才落盘，避免打字把 remote preferences 刷爆
    var text by remember { mutableStateOf(saved) }
    var status by remember { mutableStateOf("") }
    val outerScroll = rememberScrollState()
    val innerScroll = rememberScrollState()

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val read = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (read != null) {
                text = read
                status = ctx.getString(R.string.rename_imported)
            }
        } catch (t: Throwable) {
            status = ctx.getString(R.string.rename_failed, t.message ?: "")
        }
    }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            status = ctx.getString(R.string.rename_exported)
        } catch (t: Throwable) {
            status = ctx.getString(R.string.rename_failed, t.message ?: "")
        }
    }

    fun save() {
        ModulePreferences.edit { putString(ConfigKeys.IMG_LABEL_RENAME_RULES, text) }
        status = ctx.getString(R.string.rename_saved)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(outerScroll),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.rename_section_format))
        Card(modifier = Modifier.cardPadding()) {
            CardNote(stringResource(R.string.rename_format_help))
        }

        SmallTitle(stringResource(R.string.rename_section_rules))
        Card(modifier = Modifier.cardPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(innerScroll)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.rename_placeholder),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 14.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }

        SmallTitle(stringResource(R.string.rename_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.rename_save),
                // 状态类副标题留（已保存 / 已导入…），说明类删（docs/NAMING.md §3）
                summary = status.takeIf { it.isNotEmpty() },
                endActions = { ArrowRightIcon() },
                onClick = { save() },
            )
            BasicComponent(
                title = stringResource(R.string.rename_import),
                endActions = { ArrowRightIcon() },
                onClick = { openDoc.launch(arrayOf("text/plain", "text/*")) },
            )
            BasicComponent(
                title = stringResource(R.string.rename_export),
                endActions = { ArrowRightIcon() },
                onClick = { createDoc.launch("wxpiclabel-rename.txt") },
            )
            BasicComponent(
                title = stringResource(R.string.rename_copy),
                endActions = { ArrowRightIcon() },
                onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("wxpiclabel-rename", text))
                    status = ctx.getString(R.string.rename_copied)
                },
            )
            BasicComponent(
                title = stringResource(R.string.rename_paste),
                endActions = { ArrowRightIcon() },
                onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (clip.isNullOrEmpty()) {
                        status = ctx.getString(R.string.rename_clipboard_empty)
                    } else {
                        text = clip
                        status = ctx.getString(R.string.rename_pasted)
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
