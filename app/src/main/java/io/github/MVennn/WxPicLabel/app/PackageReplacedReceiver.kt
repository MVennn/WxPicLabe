package io.github.MVennn.WxPicLabel.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 覆盖安装后重放「桌面图标」偏好。
 *
 * `ACTION_MY_PACKAGE_REPLACED` 只发给被替换的本包，属于 Android 8.0（API 26）
 * 隐式广播限制里「sent specifically to them」的豁免场景，静态注册可正常收到；
 * 本仓 minSdk = 27 / targetSdk = 36，manifest 里显式声明 `android:exported="false"`
 * 只约束其它应用向本 receiver 发广播，不影响系统投递。
 *
 * 覆盖安装会把 `PackageManager.setComponentEnabledSetting` 的组件启用记录清掉，
 * alias 回落 manifest 默认（已改为 false）。这里读独立本地镜像
 * `launcher_icon_state` 再幂等 apply 一次，补上「更新后用户没打开 App」的窗口；
 * 用户/系统任何一次拉起模块 App 时，`WxPicApp.onCreate` 里的
 * `LauncherIcon.applyFromLocal` 再兜一层（部分 ROM 可能不投递该广播）。
 *
 * 只做同步的 SharedPreferences commit + 一次组件状态写入，不需要 goAsync()。
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        LauncherIcon.applyFromLocal(context)
    }
}
