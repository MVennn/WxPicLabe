# AGENTS.md —— WxPicLabe 仓级规则

> **一句话**：本文件是本仓的规则入口，只写与全局、组级不同的部分；冲突按 §1 顺序取更靠前者。

## 1. 生效顺序

1. 用户当轮明确指令
2. 本文件（仓级）
3. 组级 `<WORKSPACE>/_merged/LSPosed/AGENTS.md`（junction 合并视图，只读）
4. 全局规则（`$DSH_HOME/AGENTS.md`）

例外：§5 凭据、§3 路径纪律是硬约束，第 2、3 条不得覆盖。

## 2. 语言与交付

- 简体中文；结论先行，先给能跑的东西，再解释。
- 每个结论配命令 + 真实输出；失败贴原始报错原文，不美化、不概括成「报错了」。
- 交付两段：**改了什么 / 怎么验证**。
- 信息缺口标 `[待补: 缺什么]`，继续做能做的部分，不空转、不重复追问同一件事。

## 3. 路径纪律

- 文档、脚本、配置里只写**仓库内相对路径**；不写本机绝对路径（换机器即断）。
- 命令示例从仓根起算：`python tools\check-docs.py`、`docs/DOC-RULES.md`。
- 本仓只写本仓。邻仓 `../GSAuxiliary/` 与组级视图 `../_merged/LSPosed/` 只读，需要跨仓改动先回到用户。

## 4. 变更纪律

- 改前先读完整文件；不做「顺手重构」，不整文件覆盖式重写。
- 一次改动只解决一个主题；无关文件的格式化、重排、改名一律另起一轮。
- 不主动执行 `git init` / `add` / `commit` / `push`；用户说「提交」= commit + push，不发版。
- 文档改动必须同步 `docs/README.md` 索引，索引与正文同一轮改完。

## 5. 凭据

- key / 口令 / token / 签名口令只从环境变量读，不进源码、文档、日志、缓存、提交信息、聊天汇报。
- 公开内容一律脱敏：`<REDACTED-…>`（凭据）、`<REPLACE-…>`（待填值）。
- `local.properties`、`keystore.properties`、`*.keystore`、`*.jks` 只留本机，已在 `.gitignore` 排除。

## 6. 文档门禁

- 仓根执行 `python tools\check-docs.py`，退出码 0 才算文档合格。
- 它查四件事：`docs/*.md` 是否全部被 `docs/README.md` 链接、每篇是否有 `> **一句话**` 块、
  索引里的相对链接是否可解析、文件名是否全大写 + 连字符。
- 门禁红着不收尾；修不了的部分在回报里贴原始输出并标 `[待补]`。

## 7. 检索顺序

1. 仓根有 `.codegraph/` → 先 `codegraph_explore`
2. 否则 `grep` / `glob` / 读文件
3. 都查不到再问人，并说明已经查过什么

## 8. 调试产物

- 临时文件、抓包、反编译输出、日志一律进仓内 `.work/` 或 `%TEMP%`；
  工作区根目录零新建，仓根不落散件。
- `.work/` 不进库，不写进文档当路径示例。

## 9. 当前阶段

- 代码结构（2026-11-06 重整，与 GSAuxiliary 同一套五层）：入口 `WxPicLabelModule.java` 在包根；业务实现在 `feature/imglabel/`（`UiSender` / `RepeatCounter`）；`core/` 放配置与日志；`app/` 是模块自身进程；`ui/` 是 Compose 设置界面；`util/` 是反射工具。目录规范见 `docs/ARCHITECTURE.md`。
- 发送链路已定稿（2026-11-05，模拟器实测）：接管相册批量发送入口 `on5.f#a` 逐张重放，文字走视图层 `UiSender`（`MMEditText` + 「发送」按钮）；内部串行队列，连发不会互相覆盖。
- 兜底：接管不可用时退回「图先字后」，由 `flushFallback` 按间隔补发，不丢图、不重发。
- 已排除：`b3.a`（写死系统提示类型）、自建 `f9`+`g9.Zb`（只写本地库）、拦截入库（与「发送中」互锁）、`xa0.b0#Oj`（相册路径未调用）。
- 分析工具在 `.work/rea/`（dex 解析脚本 + REA MCP 调用脚本 `rea3.mjs`），证据截图在 `.work/evidence/`。
- 排障用自测通道：`setprop debug.wxpiclabel.text <文本>` / `.album a,b,c` / `.takeover 0`，进聊天界面即触发（见 `docs/TROUBLESHOOTING.md` 第 7 节）。
- 设置界面已有：功能页（总开关）+ 发送设置二级页（重复图片计数、清空记录）。

## 10. 构建

- 仓根 `build.cmd`：把 `GRADLE_USER_HOME` / `TMP` / `ANDROID_USER_HOME` 重定向到工程内，不需要管理员权限。
- 产物 `app\build\outputs\apk\debug\app-debug.apk`；`dist/`、`.gradle-home/`、`.android-home/`、`.tmp/` 都是本机产物，不进库。
- SDK 解析顺序：环境 `ANDROID_SDK_ROOT`/`ANDROID_HOME`（须存在）→ 仓内 `.work\android-sdk` → `local.properties` 的 `sdk.dir`。`build.cmd` 里不写死任何绝对路径，且保持纯 ASCII（cmd.exe 按 OEM 代码页读 .cmd，中文注释会被当成命令）。
- 改构建配置后必须实际跑一次 `build.cmd`，贴 `BUILD SUCCESSFUL` 与 APK 大小。

## 11. 门面与文档口径（系列仓基准 = GSAuxiliary）

- README 是**七段式**：`<div align="center">` logo + 标题 + 一行粗体定位 + 标语 + 徽章 `</div>` → 这是什么 / 环境要求 / 安装 / 反馈 / 免责声明 / 开源鸣谢 / 许可证。**禁止「一句话」引言块**，也不链 `docs/README.md` 索引。
- 根目录**文档**只留 `README / CHANGELOG / CONTRIBUTING / SECURITY / LICENSE / NOTICE / AGENTS / PROGRESS`；构建骨架（`build.cmd` / `build.gradle.kts` / `settings.gradle.kts` / `gradlew` / `app/` / `assets/` / `gradle/`）按实际结构保留；开发者文档下沉到 `docs/`。
- `docs/` 命名全大写 + 连字符，单篇 ≤800 行，索引在 `docs/README.md`（孤儿零容忍）。
- `CONTRIBUTING.md` 必须有「一句话」引言块；Issue 模板在 `.github/ISSUE_TEMPLATE/`（bug / feature / config）。
- 门面改动与缺陷修复要**双记账**：`CHANGELOG.md` 一行；修缺陷再在 `docs/PITFALLS.md` 补「现象 → 根因 → 怎么避」。
- 发版才动 `versionName` / `versionCode`（见 `docs/VERSIONING.md`）。
