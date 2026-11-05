package io.github.MVennn.WxPicLabel.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.MVennn.WxPicLabel.R
import io.github.MVennn.WxPicLabel.app.ModulePreferences
import io.github.MVennn.WxPicLabel.core.ConfigKeys
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * 「发送图片文件名 → 发送设置」二级页。
 *
 * 只放**总开关之外**的参数（一级页只有开关，约定见 [FeatureTab]）：
 *
 *  - 「重复图片」：同名图片再次发送时，在文字后追加 ` +2`、` +3`……
 *  - 「记录」：清空发送记录。
 *
 * ## 为什么这里只有开关、没有记录列表
 *
 * 计数存在**微信进程自己**的 SharedPreferences 里（`/data/data/com.tencent.mm/`）——
 * remote preferences 在目标进程侧只读，模块 App 的目录跨 uid 不可写，
 * 这是唯一能落盘的地方。代价就是模块 App **读不到**这份记录，
 * 所以这一页只能给「清空」这一个动作，列表留到以后（要显示得让模块 App
 * 开一个 exported Provider 给微信进程回调）。
 *
 * ## 「清空」怎么生效
 *
 * 不直接删文件（跨 uid 删不到），而是往配置里写一个毫秒时间戳
 * （[ConfigKeys.IMG_LABEL_REPEAT_RESET]）。微信进程每次计数前比对令牌，
 * 发现变了就把历史整体清掉 —— 单向信号，不需要跨进程监听器。
 */
@Composable
internal fun ImgLabelSendScreen() {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    val repeat by rememberBoolPref(
        ConfigKeys.IMG_LABEL_REPEAT_COUNT,
        ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_COUNT,
    )
    val clearedText = stringResource(R.string.img_label_repeat_cleared)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        Spacer(Modifier.height(8.dp))

        SmallTitle(stringResource(R.string.img_label_section_repeat))
        Card(modifier = Modifier.cardPadding()) {
            GsSwitchPreference(
                checked = repeat,
                onCheckedChange = { v ->
                    ModulePreferences.edit { putBoolean(ConfigKeys.IMG_LABEL_REPEAT_COUNT, v) }
                },
                title = stringResource(R.string.img_label_repeat_switch),
                summary = stringResource(R.string.img_label_repeat_summary),
            )
        }

        SmallTitle(stringResource(R.string.img_label_section_record))
        Card(modifier = Modifier.cardPadding()) {
            BasicComponent(
                title = stringResource(R.string.img_label_repeat_clear),
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
                },
            )
        }

        Spacer(Modifier.height(24.dp + LocalNavBarInset.current))
    }
}
