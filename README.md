<div align="center">

<img src="https://raw.githubusercontent.com/MVennn/WxPicLabe/main/assets/logo.png" width="128" alt="WxPicLabe">

# WxPicLabe

**微信 · 发图功能扩展模块**（非官方，与腾讯及其关联方无任何隶属、合作或授权关系）

Make WeChat Picture Great Again！

![版本](https://img.shields.io/github/v/release/MVennn/WxPicLabe) ![CI](https://github.com/MVennn/WxPicLabe/actions/workflows/ci.yml/badge.svg) ![源码仓库](https://img.shields.io/badge/源码仓库-GitHub-181717?logo=github&logoColor=white) ![问题反馈](https://img.shields.io/badge/问题反馈-Issues-181717?logo=github&logoColor=white) ![Android](https://img.shields.io/badge/Android-8.1%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0) ![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

扩展 微信 发图能力的 LSPosed 模块。

微信一次选多张图时，默认是「一堆图先落地、字再补」，或者干脆没有字。模块接管相册的批量发送入口，改成**按选图顺序逐张发**：每张图真正发出去之后紧跟一条文字 —— 默认就是这张图的文件名，也可以用「重命名规则」改写成任意文字 —— 形成 图-文-图-文 交替；哪些图补文字、哪些只发图，由「文字过滤」的黑白名单决定。

功能以模块 App「功能」页为准：

| 功能 | 说明 |
| --- | --- |
| 增加 文件名 开关 | 功能页总开关（出厂关闭）；相册底栏原图右边还有同款「文件名」开关，不勾就退回微信原生发送 |
| 重命名规则 | 一行一条 `原名 -> 新名`，可导入导出、可复制粘贴；导入时按原名就地合并，同名的跳过 |
| 重复图片追加次数 | 同名图片再次发送时在文字后追加 `+2`、`+3`…；只累计设定时段内发出的图，默认全天 |
| 发送记录 | 逐条台账可搜索、按时间段分组；能改文件名、改「第几次」、删条目，清空时连 `+N` 计数一起清零 |
| 发送提示 | 发送期间用系统 Toast 显示「正在发送 3/12」、屏幕保持常亮，整批发完自动解除；进度与常亮各一个开关 |
| 发送范围 | 仅勾选的会话生效（默认关闭 = 所有会话都带文件名），名单外的会话退回微信原生发送 |
| 文字过滤 | 黑白名单：命中与否决定这张图带不带文字，图片照原顺序发；关键字支持子串与 `*` `?` 通配 |

不联网、不上传、不申请任何 Android 权限，只改变发送时的消息组织方式，不修改聊天记录与账号数据。

## 环境要求

- 安卓 8.1+（minSdk 27）
- **较新的 LSPosed**（现代 Xposed API 102，`minApiVersion` 101）。过旧的 LSPosed、太极 / LSPatch / EdXposed 等实现会**静默不加载**（已启用但零日志、零生效）
- 微信**不锁版本**：模块按类名与字段特征定位，宿主升级后可能需要校准 Hook 点（见 Issues 与 [排错手册](https://github.com/MVennn/WxPicLabe/blob/main/docs/TROUBLESHOOTING.md)）

## 安装

1. [Releases](https://github.com/MVennn/WxPicLabe/releases) 下载最新 APK 并安装
2. LSPosed 管理器 → 模块，勾选「WxPicLabel」
3. 作用域里勾上**微信**（模块已静态声明默认作用域，一般会自动勾好）
4. 强制停止微信，再打开一次
5. 打开模块 App → 功能，打开「增加 文件名 开关」（出厂默认关闭）
6. 相册选图发送；LSPosed 日志过滤 tag `WxPicLabel`，看到 `takeover: intercepted` 说明接管生效

### 从源码构建

在 Windows 仓根执行 `build.cmd`，**默认出 release 包**（开 R8，实机与日常测试都装这一个）；要 debug 包必须显式带上变体词：

```cmd
build.cmd          :: release（默认）
build.cmd debug    :: debug，只有要 debuggable 抓日志时才用
```

产物分别是 `app\build\outputs\apk\release\app-release.apk` 与 `app\build\outputs\apk\debug\app-debug.apk`；缓存重定向到工程内、SDK 解析顺序与版本钉死见 [docs/BUILD.md](https://github.com/MVennn/WxPicLabe/blob/main/docs/BUILD.md)。

## 反馈

遇到问题、想让某个页面更顺手，到 [Issues](https://github.com/MVennn/WxPicLabe/issues) 反馈。请带上：

- 微信版本号（微信 → 我 → 设置 → 关于）
- 模块版本号、安卓版本、机型
- 复现步骤，以及预期为什么样、实际什么样
- 日志：`adb shell su -c "grep WxPicLabel /data/adb/lspd/log/modules_*.log | tail -50"`

## 免责声明

本项目仅供学习与个人使用。

- 不收集、不上传、不存储任何个人信息，不含统计分析、广告或数据回传组件
- 不修改微信安装包、不绕过任何鉴权，只改变发送时的消息组织方式
- 使用第三方模块可能违反微信用户协议并带来账号风险，相关后果由使用者自行承担
- 完全免费开源，不直接或间接获取经济利益

「微信」「WeChat」商标与标识的权利归腾讯及其关联方所有。本项目与之不存在任何隶属、合作、授权、认可或支持关系。

完整声明见 [docs/DISCLAIMER.md](https://github.com/MVennn/WxPicLabe/blob/main/docs/DISCLAIMER.md)。

## 开源鸣谢

本模块建立在这些开源项目之上：

| 项目 | 用途 |
| --- | --- |
| [**LSPosed**](https://github.com/LSPosed/LSPosed) · [**libxposed**](https://central.sonatype.com/artifact/io.github.libxposed/api) | 模块框架与现代 Xposed API（API 102） |
| [**Miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | 设置界面的 UI 组件库（小米 HyperOS 风格 Compose） |
| [**AndroidLiquidGlass**](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop`） | 液态玻璃顶栏 / 悬浮底栏 / 弹窗的折射与模糊效果 |
| [**Jetpack Compose**](https://developer.android.com/jetpack/compose) · [**AndroidX**](https://developer.android.com/jetpack/androidx) | 全部界面实现的基础设施 |
| [**Kotlin**](https://github.com/JetBrains/kotlin) | 模块与设置界面的实现语言 |
| [**Material Icons**](https://github.com/google/material-design-icons) | 界面图标的矢量路径数据来源（按 path 自绘，不引入图标库） |
| [**HyperIsland**](https://github.com/1812z/HyperIsland) | 关于页的布局与动效来源（MIT，非代码依赖，实现为本项目自写） |

## 许可证

本项目以 [Apache License 2.0](https://github.com/MVennn/WxPicLabe/blob/main/LICENSE) 发布。
第三方组件的版权与许可证声明见 [NOTICE](https://github.com/MVennn/WxPicLabe/blob/main/NOTICE)（随 APK 分发，模块 App「关于 → 第三方组件声明」为同一份内容）。
