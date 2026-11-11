# 构建

> **一句话**：本机怎么把模块编成可安装 APK——版本钉死、缓存全部重定向进工程、产物在哪、怎么全量重建。

## 版本

| 项 | 值 |
| --- | --- |
| JDK | 17（`sourceCompatibility/targetCompatibility = 17`） |
| Gradle | 8.11.1（wrapper） |
| AGP | 8.10.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |
| Xposed API | `io.github.libxposed:api:102.0.0`（compileOnly，不进包） |

## 构建命令

```cmd
cd /d "%~dp0"
build.cmd            :: release（默认），开了 R8，实机与日常测试都装这一个
build.cmd debug      :: debug，只在要 debuggable 抓日志时用，实机上掉帧明显
```

产物：

| 变体 | 路径 | 实测体积 |
| --- | --- | --- |
| debug | `app\build\outputs\apk\debug\app-debug.apk` | 12.2 MB |
| release | `app\build\outputs\apk\release\app-release.apk` | 1.2 MB |

需要分发时另存副本（`dist/` 已被 gitignore，仓库不存 APK）。

第一个词是变体名（`debug` / `release`，不写默认 `release`），其余参数原样转给 Gradle；
不接受 `clean` 之类的额外参数（Gradle 按参数顺序执行，容易把产物清掉）；
要全量重建就删掉 `app\build` 再跑 `build.cmd`。

## 变体与 R8（为什么实机必须装 release）

**同一份 UI 代码，debug 包在实机上左右切页会明显掉帧，release 不会。**
2026-11-06 排查结论（对照仓 GSAuxiliary 的界面代码与本仓逐字节一致，只有品牌名 / 图标不同）：

- 两包在模拟器上帧数据一致（p99 10 ms、0 jank）→ 差异不在代码路径；
- 真正的差异在构建产物：debug 包 `debuggable=true`，ART 不做 AOT、Compose 的 lambda 没被内联，
  每帧的重组 / 绘制调用都完整走一遍；实机屏幕分辨率与刷新率一高，这部分开销直接顶到帧预算外；
- 本仓此前**只出 debug 包**（`build.cmd` 只跑 `assembleDebug`、`release` 里 `isMinifyEnabled = false`），
  而 GSAuxiliary 发的是 release（1.6 MB、单 dex）。

现在 release 开 `isMinifyEnabled` + `isShrinkResources` + `localeFilters = [zh, en]`，
keep 规则在 `app/proguard-rules.pro`（模块入口、Application / Activity / Provider、
libxposed 服务通道、`ConfigKeys`）。**新增运行期反射入口时必须同步补规则**，
否则是「编译通过、装上不生效」这类最难查的问题。

另外 `ui/Routing.kt` 的滑动进度已从组合期移到 `graphicsLayer` 的 lambda 里：
原写法 `val p = subProgress.value` 会让二级页滑动的 300 ms 里每帧重组整页（MainScreen → HomeScaffold → 当前板块整棵树）。
现在组合期只留一个 `derivedStateOf` 的门槛布尔（决定二级页挂不挂），进度本身在图层阶段读，只失效绘制。

## 缓存重定向（为什么这么做）

本机沙箱对 `%TEMP%` 与 `~/.gradle` 的写入受限，`build.cmd` 把三组缓存全部指到工程内，
不需要管理员权限，也不污染用户目录：

| 变量 | 指向 |
| --- | --- |
| `GRADLE_USER_HOME` | `<repo>\.gradle-home` |
| `TMP` / `TEMP` / `TMPDIR` | `<repo>\.tmp` |
| `ANDROID_USER_HOME` | `<repo>\.android-home` |

三个目录都在 `.gitignore` 里，属于本机缓存，不要提交。

## SDK

`build.cmd` **不写死任何 SDK 绝对路径**，按下面顺序解析（前一个命中就用它）：

| 顺序 | 来源 | 说明 |
| --- | --- | --- |
| 1 | `ANDROID_SDK_ROOT` / `ANDROID_HOME` | 调用方环境里已设且目录存在时采用 |
| 2 | `<repo>\.work\android-sdk` | 仓内 SDK（CI 或 `sdkmanager` 自装的都放这里） |
| 3 | `local.properties` 的 `sdk.dir` | 前三者都没有时由 Gradle 自己读 |

- `local.properties` 不进库；模板见 `local.properties.example`（值写成 `<REPLACE-ANDROID-SDK-DIR>`）。
- 仓内 SDK 需要 `platforms;android-37` 与 `build-tools;36.0.0`（`compileSdk = 37`）。

## 依赖 Gradle 发行版

`build.cmd` 直接调用已下载的 Gradle 8.11.1（`%USERPROFILE%\.gradle\wrapper\dists\gradle-8.11.1-bin\...`），
找不到就报错退出。首次环境先跑一次 `gradlew` 让它下载发行版。

## 已验证

2026-11-05（首次构建快照，重建后以实际产物为准）：`BUILD SUCCESSFUL`，APK 20,697 字节；三个 `META-INF/xposed/*` 声明文件与
`WxPicLabelModule` 入口类都在包内，`io.github.libxposed:api` 未被写入包（compileOnly 生效）。

2026-11-06（release 变体首次打通）：`build.cmd release` → `BUILD SUCCESSFUL in 1m 5s`，
APK 1,200,488 B、单 dex；包内 `META-INF/xposed/{java_init.list,module.prop,scope.list}` 齐全，
dex 里 `WxPicLabelModule` / `SettingsActivity` / `WxPicApp` / `DebugConfigProvider` 均在（keep 生效）；
模拟器实装后 `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`（debuggable 已消失），设置页正常渲染、无崩溃日志。
