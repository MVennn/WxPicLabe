package io.github.MVennn.WxPicLabel.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.app.RecordEdits
import io.github.MVennn.WxPicLabel.app.RecordStore
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 记录页的时间范围筛选。
 *
 * 档位刻意做成固定的四档（全部 / 今天 / 近 7 天 / 近 30 天），而不是日期选择器：
 * 这个页面要回答的是「刚才那批发了没」「上周那几张发了几次」，
 * 固定档位一眼能选中，比掏日历快。
 */
private enum class RecordRange { All, Today, Week, Month }

/** 一条记录在列表里的分组：同一批发送的事件折成一组，组头就是这批的时间戳。 */
private class EventGroup(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val migrated: Boolean,
    val events: List<RecordStore.Event>,
)

/** 今天零点（本地时区） */
private fun midnightOf(now: Long): Long {
    val c = Calendar.getInstance()
    c.timeInMillis = now
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

/** 筛选下界；0 表示不限 */
private fun rangeFloor(range: RecordRange, now: Long): Long = when (range) {
    RecordRange.All -> 0L
    RecordRange.Today -> midnightOf(now)
    // 「近 7 天」含今天，所以往回数 6 天
    RecordRange.Week -> midnightOf(now) - 6L * 24L * 3600_000L
    RecordRange.Month -> midnightOf(now) - 29L * 24L * 3600_000L
}

/**
 * 事件所属的组。
 *
 * 正常事件带批次 id（微信进程每批发一个），直接拿来当键。
 * **旧版聚合表迁移出来的条目**（批次恒为 [RecordStore.BATCH_MIGRATED]）和
 * 批次为空的（早期版本、进程重启时批次丢了）按 **10 分钟** 一段兜底分组 ——
 * 用户反馈里的「那个时间段发了一堆图」本身就是这个粒度，而迁移条目带上 "m"
 * 却按批次分组的话，整段历史会挤成一组，看着就像「根本没有分组」。
 */
private fun groupKeyOf(ev: RecordStore.Event): String =
    if (ev.batch.isEmpty() || ev.batch == RecordStore.BATCH_MIGRATED) {
        "t" + (ev.at / 600_000L)
    } else {
        ev.batch
    }

/** 把事件按批折成组，组内按发送顺序正序，组间按最后一次发送倒序（最新的一批在最上面）。 */
private fun groupEvents(events: List<RecordStore.Event>): List<EventGroup> {
    val byKey = LinkedHashMap<String, MutableList<RecordStore.Event>>()
    for (ev in events) {
        byKey.getOrPut(groupKeyOf(ev)) { ArrayList() }.add(ev)
    }
    val out = ArrayList<EventGroup>(byKey.size)
    for ((key, list) in byKey) {
        list.sortWith(compareBy({ it.at }, { it.seq }))
        var start = Long.MAX_VALUE
        var end = Long.MIN_VALUE
        var migrated = true
        for (ev in list) {
            if (ev.at < start) start = ev.at
            if (ev.at > end) end = ev.at
            // 只要有一条是真台账，整组就算可编辑的真记录
            if (ev.seq > 0L && ev.batch != RecordStore.BATCH_MIGRATED) migrated = false
        }
        out.add(EventGroup(key, start, end, migrated, list))
    }
    out.sortWith(compareByDescending { it.endMs })
    return out
}

/**
 * 组头的时间戳文本。
 *
 *  - 只有一条：`MM-dd HH:mm:ss`
 *  - 同一天多条：`MM-dd HH:mm–HH:mm:ss`（前半段省掉重复的日期与秒）
 *  - 跨天：`MM-dd HH:mm – MM-dd HH:mm`
 */
private fun groupTimeText(
    g: EventGroup,
    day: SimpleDateFormat,
    hm: SimpleDateFormat,
    hms: SimpleDateFormat,
): String {
    val d1 = day.format(Date(g.startMs))
    val d2 = day.format(Date(g.endMs))
    return if (d1 == d2) {
        if (g.startMs == g.endMs) {
            d1 + " " + hms.format(Date(g.startMs))
        } else {
            d1 + " " + hm.format(Date(g.startMs)) + "–" + hms.format(Date(g.endMs))
        }
    } else {
        d1 + " " + hm.format(Date(g.startMs)) + " – " + d2 + " " + hm.format(Date(g.endMs))
    }
}

/**
 * 「发送设置 → 发送记录」二级页。
 *
 * 数据在**微信进程**的 SharedPreferences 里（跨 uid 读不到），由
 * `WxPicLabelModule#pushRecords` 经 FriendBridgeProvider 的 `/records` 回传，
 * 本页只读本机快照 [RecordStore]。
 *
 * 页面做三件事：
 *  1. **搜索**（按文件名，忽略大小写）与**时间范围**筛选；
 *  2. 按**批次**把一口气发的那一堆图折成一组，组头写这批的时间戳 ——
 *     点组头展开 / 收起，就是「点时间戳调出那一堆图」；
 *  3. 点单条记录可以**改文件名**、**改第几次**、**删除**，走 [RecordEdits] 的单向动作，
 *     微信进程轮询到令牌变化后重放（幂等），再把新台账回传。
 *
 * 刷新走单向令牌 [ConfigKeys.IMG_LABEL_RECORD_DUMP]：进页自动写一次，
 * 微信进程轮询到变化就回传一份新的（配置通道按 TTL 重附着，3 秒内可见）。
 */
@Composable
internal fun ImgLabelRecordScreen() {
    val ctx = LocalContext.current
    val dialogs = LocalDialogController.current
    var book by remember { mutableStateOf(RecordStore.read(ctx)) }
    // 手工改动的动作集合：界面先按它把结果套上去（乐观显示），不等微信进程回传
    var ops by remember { mutableStateOf(RecordEdits.read(ctx)) }
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(RecordRange.All) }
    // null = 默认态（只展开最新一组）；一旦用户点过任何组头就变成显式集合
    var expanded by remember { mutableStateOf<Set<String>?>(null) }
    var tick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // 时间范围菜单的锚点：ChoiceDialogSpec 必须贴着触发它的那一行弹出，不锚定会退化成全宽居中大列表
    var rangeAnchor by remember { mutableStateOf<Rect?>(null) }
    val scrollState = rememberScrollState()

    fun requestRefresh() {
        ModulePreferences.edit {
            putString(ConfigKeys.IMG_LABEL_RECORD_DUMP, System.currentTimeMillis().toString())
        }
        tick++
    }

    // 进页自动拉一次：记录是微信进程的运行时快照，进来就该是最新的
    LaunchedEffect(Unit) { requestRefresh() }

    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        val baseline = book.at
        refreshing = true
        var tries = 0
        while (tries < 45) {
            delay(400)
            tries++
            val fresh = RecordStore.read(ctx)
            if (fresh.at != baseline) {
                book = fresh
                // 动作太多时丢掉「seq 已不在台账里」的那些（效果已落盘）
                RecordEdits.trim(ctx, fresh.events.map { it.seq }.toSet())
                break
            }
        }
        refreshing = false
    }

    val dayFmt = remember { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    val hmFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val hmsFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val fullFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    // ---- 文案：弹层与行点击的 lambda 里不能调 stringResource，统一先取出来
    val editNameText = stringResource(R.string.record_event_edit_name)
    val editNthText = stringResource(R.string.record_event_edit_nth)
    val deleteText = stringResource(R.string.record_event_delete)
    val deleteTitle = stringResource(R.string.record_delete_title)
    val deleteMessage = stringResource(R.string.record_delete_message)
    val deleteConfirmText = stringResource(R.string.record_delete_confirm)
    val legacyTitle = stringResource(R.string.record_legacy_title)
    val legacyMessage = stringResource(R.string.record_legacy_message)
    val knownText = stringResource(R.string.action_known)
    val nameTitle = stringResource(R.string.record_edit_name_title)
    val nameMessage = stringResource(R.string.record_edit_name_message)
    val nameHint = stringResource(R.string.record_edit_name_hint)
    val nameEmpty = stringResource(R.string.record_name_empty)
    val nthTitle = stringResource(R.string.record_edit_nth_title)
    val nthMessage = stringResource(R.string.record_edit_nth_message)
    val nthHint = stringResource(R.string.record_edit_nth_hint)
    val nthInvalid = stringResource(R.string.record_edit_nth_invalid)
    val confirmText = stringResource(R.string.action_confirm)
    val cancelText = stringResource(R.string.action_cancel)
    val savedText = stringResource(R.string.record_saved)
    val deletedText = stringResource(R.string.record_deleted)
    val clearedText = stringResource(R.string.img_label_repeat_cleared)
    val clearTitle = stringResource(R.string.img_label_repeat_clear)
    val clearMessage = stringResource(R.string.record_clear_message)
    val searchHint = stringResource(R.string.record_search_hint)
    val filterTitle = stringResource(R.string.record_filter_title)
    val rangeLabels = listOf(
        stringResource(R.string.record_filter_all),
        stringResource(R.string.record_filter_today),
        stringResource(R.string.record_filter_week),
        stringResource(R.string.record_filter_month),
    )

    // ---- 数据：先套动作（乐观），再筛选，最后按批分组
    val live = remember(book, ops) { RecordEdits.apply(book.events, ops) }
    val now = remember(book.at, tick) { System.currentTimeMillis() }
    val floor = rangeFloor(range, now)
    val needle = query.trim()
    val filtered = remember(live, needle, floor) {
        if (needle.isEmpty() && floor == 0L) {
            live
        } else {
            live.filter { ev ->
                (floor == 0L || ev.at >= floor) &&
                    (needle.isEmpty() || ev.label.contains(needle, ignoreCase = true))
            }
        }
    }
    val groups = remember(filtered) { groupEvents(filtered) }
    // 默认态：展开最新的一组；用户点过之后就完全按 expanded 走
    val openKeys = expanded ?: groups.take(1).map { it.key }.toSet()

    fun toggle(key: String) {
        expanded = if (key in openKeys) openKeys - key else openKeys + key
    }

    /**
     * 点一条记录：改文件名 / 改第几次 / 删除。
     *
     * [anchor] 是这一行的窗口坐标矩形：动作菜单必须作为**锚定小菜单**贴在该行右下方弹出，
     * 形态与设置页的主题 / 语言选择一致（不传 anchor 会退化成全宽居中大列表）。
     * 删除不可逆，走 [ConfirmDialogSpec] 二次确认，不在菜单里直接执行。
     */
    fun openRowMenu(ev: RecordStore.Event, anchor: Rect?) {
        val ctrl = dialogs ?: return
        ctrl.showChoice(
            ChoiceDialogSpec(
                items = listOf(editNameText, editNthText, deleteText),
                selectedIndex = -1,
                anchor = anchor,
                onSelect = { idx ->
                    when (idx) {
                        0 -> ctrl.showTextInput(
                            TextInputDialogSpec(
                                title = nameTitle,
                                message = nameMessage,
                                initial = ev.label,
                                hint = nameHint,
                                confirmText = confirmText,
                                cancelText = cancelText,
                                onConfirm = { value ->
                                    ops = RecordEdits.commit(ctx, "l|" + ev.seq + "|" + value)
                                    Toast.makeText(ctx, savedText, Toast.LENGTH_SHORT).show()
                                    requestRefresh()
                                },
                                maxChars = 120,
                                validate = { value -> if (value.isEmpty()) nameEmpty else null },
                            ),
                        )
                        1 -> ctrl.showTextInput(
                            TextInputDialogSpec(
                                title = nthTitle,
                                message = nthMessage,
                                initial = ev.n.toString(),
                                hint = nthHint,
                                confirmText = confirmText,
                                cancelText = cancelText,
                                onConfirm = { value ->
                                    val n = value.toIntOrNull()
                                    if (n != null) {
                                        ops = RecordEdits.commit(ctx, "n|" + ev.seq + "|" + n)
                                        Toast.makeText(ctx, savedText, Toast.LENGTH_SHORT).show()
                                        requestRefresh()
                                    }
                                },
                                numeric = true,
                                maxChars = 3,
                                validate = { value ->
                                    val n = value.toIntOrNull()
                                    if (n == null || n < 1 || n > 999) nthInvalid else null
                                },
                            ),
                        )
                        // 菜单收起动画播完后才回调到这里，可以直接接着开确认弹层
                        else -> ctrl.showConfirm(
                            ConfirmDialogSpec(
                                title = deleteTitle,
                                message = deleteMessage,
                                confirmText = deleteConfirmText,
                                cancelText = cancelText,
                                onConfirm = {
                                    ops = RecordEdits.commit(ctx, "d|" + ev.seq)
                                    Toast.makeText(ctx, deletedText, Toast.LENGTH_SHORT).show()
                                    requestRefresh()
                                },
                            ),
                        )
                    }
                },
            ),
        )
    }

    /**
     * 点一条**只读**记录：不是静默无反应，而是说清为什么改不了。
     *
     * 只读 = 回传的文档里没有逐次台账（events），只有旧版聚合表，界面按
     * [RecordStore.Event.editable] 判定。用户看到的「点了没反应」的旧问题是：
     * 只读行既没有箭头也没有弹层，点了像是坏了。现在统一弹这张说明卡。
     */
    fun openLegacyInfo() {
        val ctrl = dialogs ?: return
        ctrl.showConfirm(
            ConfirmDialogSpec(
                title = legacyTitle,
                message = legacyMessage,
                confirmText = knownText,
                // 纯说明，没有反向选项：确认按钮不可省（见 ConfirmDialogSpec），取消位留空即不画
                cancelText = "",
                onConfirm = {},
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 概览：总数 / 更新时间，点一下重新拉
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.record_count, live.size),
                summary = when {
                    refreshing -> stringResource(R.string.record_refreshing)
                    book.at == 0L -> stringResource(R.string.record_empty_hint)
                    else -> stringResource(
                        R.string.record_updated_at,
                        fullFmt.format(Date(book.at)),
                    )
                },
                // 点了就地重新拉一次，没有后续界面 —— 不带箭头（见 docs/UI.md §4 箭头语义）
                onClick = { requestRefresh() },
            )
        }

        // ---- 搜索 + 时间范围
        SmallTitle(stringResource(R.string.record_section_filter))
        Card(modifier = Modifier.cardPadding()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    textStyle = TextStyle(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = searchHint,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    },
                )
            }
            BasicComponent(
                title = filterTitle,
                modifier = Modifier.onGloballyPositioned { rangeAnchor = it.boundsInWindow() },
                summary = rangeLabels[range.ordinal],
                // 原地选值，选完回显在行摘要上 —— 朝下箭头
                endActions = { DownArrowIcon() },
                onClick = {
                    dialogs?.showChoice(
                        ChoiceDialogSpec(
                            items = rangeLabels,
                            selectedIndex = range.ordinal,
                            anchor = rangeAnchor,
                            onSelect = { idx -> range = RecordRange.values()[idx] },
                        ),
                    )
                },
            )
        }

        // ---- 只读条目提示：老版本回传的文档只有聚合表，没有逐次台账
        val readOnlyCount = live.count { !it.editable }
        if (readOnlyCount > 0) {
            Card(modifier = Modifier.cardPadding()) {
                BasicComponent(
                    title = stringResource(R.string.record_legacy_note, readOnlyCount),
                    summary = stringResource(R.string.record_legacy_note_hint),
                    endActions = { DownArrowIcon() },
                    onClick = { openLegacyInfo() },
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- 按时间段分组
        SmallTitle(stringResource(R.string.record_section_groups))
        if (groups.isEmpty()) {
            Card(modifier = Modifier.cardPadding()) {
                CardNote(
                    if (live.isEmpty()) {
                        stringResource(R.string.record_empty_body)
                    } else {
                        stringResource(R.string.record_empty_search)
                    },
                )
            }
        } else {
            for (g in groups) {
                val open = g.key in openKeys
                Card(modifier = Modifier.cardPadding()) {
                    BasicComponent(
                        // 组头**永远是这一批的时间戳** —— 它就是「调出那一堆图」的入口；
                        // 旧版来源只作为摘要后缀，不能顶掉时间（顶掉了这段历史就没有时间入口）
                        title = groupTimeText(g, dayFmt, hmFmt, hmsFmt),
                        summary = if (g.migrated) {
                            stringResource(R.string.record_group_count_migrated, g.events.size)
                        } else {
                            stringResource(R.string.record_group_count, g.events.size)
                        },
                        // 展开 / 收起都是原地动作，朝下箭头不随状态变
                        endActions = { DownArrowIcon() },
                        onClick = { toggle(g.key) },
                    )
                    if (open) {
                        for (ev in g.events) {
                            // 每行自持一个锚点：动作菜单贴着这一行弹出，而不是屏幕正中的全宽列表
                            key(ev.seq) {
                                val rowAnchor = remember { mutableStateOf<Rect?>(null) }
                                BasicComponent(
                                    title = ev.label,
                                    modifier = Modifier.onGloballyPositioned {
                                        rowAnchor.value = it.boundsInWindow()
                                    },
                                    summary = if (ev.editable) {
                                        stringResource(
                                            R.string.record_row_summary,
                                            ev.n,
                                            hmsFmt.format(Date(ev.at)),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.record_row_readonly,
                                            ev.n,
                                            hmsFmt.format(Date(ev.at)),
                                        )
                                    },
                                    // 可编辑 → 原地弹动作菜单；只读 → 原地弹「为什么改不了」的说明卡。
                                    // 两种都是原地弹窗，所以都画朝下箭头（docs/UI.md §4 箭头语义）
                                    endActions = { DownArrowIcon() },
                                    onClick = {
                                        if (ev.editable) {
                                            openRowMenu(ev, rowAnchor.value)
                                        } else {
                                            openLegacyInfo()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // ---- 清空（破坏性操作，二次确认）
        SmallTitle(stringResource(R.string.record_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = clearTitle,
                summary = stringResource(R.string.img_label_repeat_clear_summary),
                endActions = { ArrowRightIcon() },
                onClick = {
                    dialogs?.showConfirm(
                        ConfirmDialogSpec(
                            title = clearTitle,
                            message = clearMessage,
                            confirmText = confirmText,
                            cancelText = cancelText,
                            onConfirm = {
                                ModulePreferences.edit {
                                    putString(
                                        ConfigKeys.IMG_LABEL_REPEAT_RESET,
                                        System.currentTimeMillis().toString(),
                                    )
                                }
                                ops = RecordEdits.clear(ctx)
                                expanded = null
                                Toast.makeText(ctx, clearedText, Toast.LENGTH_SHORT).show()
                                requestRefresh()
                            },
                        ),
                    )
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
