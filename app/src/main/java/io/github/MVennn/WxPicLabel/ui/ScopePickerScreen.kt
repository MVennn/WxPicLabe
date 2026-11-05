package io.github.MVennn.WxPicLabel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.MVennn.WxPicLabel.app.FriendStore
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「发送设置 → 选择好友与群聊」二级页。
 *
 * 名单来自 [FriendStore]（微信进程抓取后经 FriendBridgeProvider 回传的本机快照），
 * 勾选结果写进 [ConfigKeys.IMG_LABEL_SCOPE_IDS]，由微信进程在发送时读取判定。
 *
 * 刷新走单向令牌：写一个新的 [ConfigKeys.IMG_LABEL_SCOPE_REFRESH]，微信进程轮询到变化
 * 就重新抓一遍联系人再回传 —— 与「清空发送记录」同一套机制。
 */
@Composable
internal fun ScopePickerScreen() {
    val ctx = LocalContext.current
    val selected by rememberStringSetPref(ConfigKeys.IMG_LABEL_SCOPE_IDS, emptySet())
    var book by remember { mutableStateOf(FriendStore.read(ctx)) }
    var query by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // 点了刷新后轮询几次，等微信进程把新名单回传过来
    LaunchedEffect(refreshing) {
        if (!refreshing) return@LaunchedEffect
        repeat(12) {
            delay(700)
            book = FriendStore.read(ctx)
        }
        refreshing = false
    }

    val filtered = remember(book, query) {
        val q = query.trim()
        if (q.isEmpty()) book.entries else book.entries.filter {
            it.name.contains(q, true) || it.id.contains(q, true)
        }
    }
    val rooms = filtered.filter { it.kind == 1 }
    val friends = filtered.filter { it.kind != 1 }

    fun toggle(id: String) {
        val next = if (selected.contains(id)) selected - id else selected + id
        ModulePreferences.edit { putStringSet(ConfigKeys.IMG_LABEL_SCOPE_IDS, next) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.scope_selected_count, selected.size),
                summary = if (book.fetchedAt == 0L) {
                    stringResource(R.string.scope_empty_hint)
                } else {
                    stringResource(R.string.scope_total_count, book.entries.size)
                },
                endActions = { ArrowRightIcon() },
                onClick = {
                    ModulePreferences.edit {
                        putString(ConfigKeys.IMG_LABEL_SCOPE_REFRESH, System.currentTimeMillis().toString())
                    }
                    refreshing = true
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.scope_search_hint),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    },
                )
            }
        }

        if (book.entries.isEmpty()) {
            SmallTitle(stringResource(R.string.scope_section_empty))
            Card(modifier = Modifier.cardPadding()) {
                CardNote(stringResource(R.string.scope_empty_body))
            }
        }

        if (rooms.isNotEmpty()) {
            SmallTitle(stringResource(R.string.scope_section_rooms, rooms.size))
            Card(modifier = Modifier.cardPadding()) {
                rooms.forEach { e ->
                    BasicComponent(
                        title = e.name,
                        summary = e.id,
                        endActions = { if (selected.contains(e.id)) SelectedMark() },
                        onClick = { toggle(e.id) },
                    )
                }
            }
        }

        if (friends.isNotEmpty()) {
            SmallTitle(stringResource(R.string.scope_section_friends, friends.size))
            Card(modifier = Modifier.cardPadding()) {
                friends.forEach { e ->
                    BasicComponent(
                        title = e.name,
                        summary = e.id,
                        endActions = { if (selected.contains(e.id)) SelectedMark() },
                        onClick = { toggle(e.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}

/** 行尾的选中标记 */
@Composable
private fun SelectedMark() {
    TintedIcon(
        imageVector = AppIcons.Check,
        tint = MiuixTheme.colorScheme.primary,
    )
}
