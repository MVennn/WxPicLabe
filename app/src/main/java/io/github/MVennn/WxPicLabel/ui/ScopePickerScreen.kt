package io.github.MVennn.WxPicLabel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    /** 名单刷新计数：>0 表示第几次请求，拿它当 LaunchedEffect 的 key 才能重复触发。 */
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    /** 「已选名单」展开态：默认收起，点一下展开看勾了谁。 */
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    fun requestRefresh() {
        ModulePreferences.edit {
            putString(ConfigKeys.IMG_LABEL_SCOPE_REFRESH, System.currentTimeMillis().toString())
        }
        refreshTick++
    }

    // 进页自动刷一次：名单是微信进程的运行时快照，进来就该拿最新的，不再依赖手动点。
    LaunchedEffect(Unit) { requestRefresh() }

    // 刷新轮询：等微信进程回传，fetchedAt 变了就停（最多 ~30 s；微信没在跑就等下次进页再刷）
    LaunchedEffect(refreshTick) {
        if (refreshTick == 0) return@LaunchedEffect
        val baseline = book.fetchedAt
        refreshing = true
        repeat(45) {
            delay(700)
            book = FriendStore.read(ctx)
            if (book.fetchedAt != baseline) return@repeat
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

    // 「已选名单」按名单顺序展示；名单里查不到的（改名 / 已删除的会话）原样显示 id
    val selectedEntries = remember(selected, book) {
        val known = book.entries.filter { selected.contains(it.id) }
        val unknown = selected
            .filter { id -> book.entries.none { it.id == id } }
            .map { FriendStore.Entry(it, it, 0) }
        known + unknown
    }
    val updatedAt = remember(book.fetchedAt) {
        if (book.fetchedAt == 0L) {
            ""
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(book.fetchedAt))
        }
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
                    stringResource(R.string.scope_total_count, book.entries.size) +
                        " · " + stringResource(R.string.scope_updated_at, updatedAt)
                },
                // 原地展开 / 收起选中列表 —— 朝下箭头
                endActions = { DownArrowIcon() },
                onClick = { expanded = !expanded },
            )
            AnimatedVisibility(expanded) {
                Column {
                    if (selectedEntries.isEmpty()) {
                        CardNote(stringResource(R.string.scope_selected_empty))
                    } else {
                        selectedEntries.forEach { e ->
                            BasicComponent(
                                title = e.name,
                                summary = e.id,
                                endActions = { SelectedMark() },
                                onClick = { toggle(e.id) },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.scope_refresh_entry),
                // 说明类副标题删；只留「正在刷新名单…」这个状态（docs/NAMING.md §3）
                summary = if (refreshing) stringResource(R.string.scope_refreshing) else null,
                // 点了就地重新拉名单，没有后续界面 —— 不带箭头
                onClick = { requestRefresh() },
            )
        }

        Spacer(Modifier.height(8.dp))
        Card(modifier = Modifier.cardPadding()) {
            // 卡内只给 4dp：搜索框自带 12dp 内边距，4 + 12 = 16dp（docs/UI.md §9.2）
            AppSearchField(
                value = query,
                onValueChange = { query = it },
                hint = stringResource(R.string.scope_search_hint),
                clearLabel = stringResource(R.string.search_clear),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }

        if (book.entries.isEmpty()) {
            SmallTitle(stringResource(R.string.scope_section_empty))
            Card(modifier = Modifier.cardPadding()) {
                CardNote(stringResource(R.string.scope_empty_body))
            }
        } else if (filtered.isEmpty()) {
            // 名单本身有内容、只是被搜索筛空了 —— 说明「是搜不到」而不是「没有会话」，
            // 否则用户会以为名单被清掉了（docs/UI.md §9 空结果三件套）
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.cardPadding()) {
                CardNote(stringResource(R.string.scope_empty_search))
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
