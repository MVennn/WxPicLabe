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
cd /d F:\Project\WxPicLabe
build.cmd
```

产物：

- Gradle 原始产物 `app\build\outputs\apk\debug\app-debug.apk`
- 需要分发时另存副本（`dist/` 已被 gitignore，仓库不存 APK）

脚本不接受 `clean` 之类的额外参数（Gradle 按参数顺序执行，容易把产物清掉）；
要全量重建就删掉 `app\build` 再跑 `build.cmd`。

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

2026-11-05：`BUILD SUCCESSFUL`，APK 20,697 字节；三个 `META-INF/xposed/*` 声明文件与
`WxPicLabelModule` 入口类都在包内，`io.github.libxposed:api` 未被写入包（compileOnly 生效）。
