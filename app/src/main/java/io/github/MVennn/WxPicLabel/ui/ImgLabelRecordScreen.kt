package io.github.MVennn.WxPicLabel.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.app.RecordStore
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「发送设置 → 发送记录」二级页：同名图片发过几次。
 *
 * 数据在**微信进程**的 SharedPreferences 里（跨 uid 读不到），
 * 由 `WxPicLabelModule#pushRecords` 经 FriendBridgeProvider 的 `/records` 回传，
 * 本页只读本机快照 [RecordStore]。
 *
 * 刷新走单向令牌 [ConfigKeys.IMG_LABEL_RECORD_DUMP]：进页自动写一次，
 * 微信进程轮询到变化就回传一份新的（配置通道按 TTL 重附着，3 秒内可见）。
 */
@Composable
internal fun ImgLabelRecordScreen() {
    val ctx = LocalContext.current
    var book by remember { mutableStateOf(RecordStore.read(ctx)) }
    var tick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val clearedText = stringResource(R.string.img_label_repeat_cleared)

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
        repeat(45) {
            delay(700)
            book = RecordStore.read(ctx)
            if (book.at != baseline) return@repeat
        }
        refreshing = false
    }

    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val updatedAt = remember(book.at) { if (book.at == 0L) "" else fmt.format(Date(book.at)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.record_count, book.rows.size),
                summary = when {
                    refreshing -> stringResource(R.string.record_refreshing)
                    book.at == 0L -> stringResource(R.string.record_empty_hint)
                    else -> stringResource(R.string.record_updated_at, updatedAt)
                },
                endActions = { ArrowRightIcon() },
                onClick = { requestRefresh() },
            )
        }

        SmallTitle(stringResource(R.string.record_section))
        Card(modifier = Modifier.cardPadding()) {
            if (book.rows.isEmpty()) {
                CardNote(stringResource(R.string.record_empty_body))
            } else {
                book.rows.forEach { r ->
                    BasicComponent(
                        title = r.label,
                        summary = stringResource(
                            R.string.record_row_summary,
                            r.count,
                            if (r.lastMs == 0L) "-" else fmt.format(Date(r.lastMs)),
                        ),
                    )
                }
            }
        }

        SmallTitle(stringResource(R.string.record_section_actions))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.img_label_repeat_clear),
                // 破坏性操作是唯一例外：留一行说明「会清掉什么范围」
                summary = stringResource(R.string.img_label_repeat_clear_summary),
                endActions = { ArrowRightIcon() },
                onClick = {
                    ModulePreferences.edit {
                        putString(
                            ConfigKeys.IMG_LABEL_REPEAT_RESET,
                            System.currentTimeMillis().toString(),
                        )
                    }
                    Toast.makeText(ctx, clearedText, Toast.LENGTH_SHORT).show()
                    requestRefresh()
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
