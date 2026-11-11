# PROGRESS —— WxPicLabe

> **一句话**：本仓任务与验收记录，按批次追加。

## 2026-11-11 · 0.8.0 追加次数设置：时段闸门 + 滚轮选时间（已发版）

**需求（用户）**：` +N` 只累计在设定时段内发出的图 —— 时段外发的连号都不占，「今天三点半前发过、三点半后再发」也不能变 +2；时段选择要 iOS 闹钟那种上下滑动的滚轮，不要打字。

**改动**
- `feature/imglabel/RepeatWindow.java`（新）：多段 `15:30-17:30` 解析、跨零点、满串宽容（全角分隔符）、`Span.of` 按分钟直接造段；空串 = 全天
- 计数闸在 `draftFor`：时段外不调 `bump`，计数表不动；序号算在文字生成那一刻，随文字进兜底队列
- `RepeatCounter.record`：`n = 0` 落成「明确未累计」，不再回查计数表补次数；`rebuildCounters` 取 max 不丢数
- 发送记录：未累计条目显示「未累计 · 时间」，仍可查看与编辑
- UI：发送设置「重复图片」卡加入口 → 三级页 `ImgLabelRepeatWindowScreen`；新建时段走新 spec `TimeRangeDialogSpec`（四根 `NumberPicker`，`wrapAround`，值存在弹层那层）
- 文案 30 条 ×2 语言；文档 `IMG-LABEL-SEND-SETTINGS` §12、`DESIGN-SYSTEM` §4.1/§5.1、`UI` §8、`PITFALLS` §23-§25

**验证**
- `build.cmd release` → BUILD SUCCESSFUL in 1m 12s；APK 1,419,160 B（debug 11.03 MB）；单 dex，`META-INF/xposed/*` 三件齐，keep 生效（mapping 里 `ImgLabelRepeatWindowScreenKt` / `TimeRangeDialogSpec` 均在）
- `:app:testDebugUnitTest` 43 项全过：ConfigKeysTest 6 / RenameRulesTest 14 / RepeatWindowTest 11 / TextFilterTest 12
- `python tools/check-docs.py` exit 0（20 篇，无断链无孤儿）
- 资产 sha256 `fe02a4cc293da2a6040786c4d882795f0f446fa7aae6491a77e127338e1fa0e0`（与 GitHub Release 的 digest 逐字节一致；首轮算出的 `bc19c2…` 是读到了写了一半的包，见 `docs/PITFALLS.md` §26）

**发版**：versionCode 10 / versionName 0.8.0，tag `10-0.8.0`；`build.cmd` 默认变体改为 release（debug 包实机掉帧，仍可 `build.cmd debug` 显式出）

## 2026-11-06 · 命名规范收敛 + 发版口径对齐（完成）

**用户报**：功能主标题与副标题没按 GSAuxiliary 的规范；Release 标题多带了中文描述。

**根因**
- `docs/NAMING.md` 是 GS 那份的精简副本、规则一致，但我只对着 §1 规则四删了副标题，没走 §2 类型判定与 §3 三段式 —— 卡片名形态本身就错
- `docs/VERSIONING.md` 只写了「tag 用 v 前缀」，对 Release 标题一个字没提，于是自己加了「· 微信发图功能扩展」；GS 的口径是 tag = `{versionCode}-{versionName}`、标题只写 `v{versionName}`

**改动**
- 卡片：`发送图片文件名` → `增加 文件名 开关`（§2 判为新增 → §3 三段式），副标题 `相册底栏原图右边`（规则四：只写位置）
- 发送设置页 6 处复述型副标题全删，只留「已选 N 个」状态与「清空」的范围说明；strings 按引用集裁掉 14 条死文案（296→282 / 290→276）
- `docs/NAMING.md` §5 补二级页清单；`docs/VERSIONING.md` 新增 §1.1「Release 三件命名」与说明三段结构
- 全仓旧名清理：CONFIG-MEMORY / TROUBLESHOOTING 与 5 处代码注释统一为新名
- Release 迁移：`v0.4.0` → `4-0.4.0`（标题 `v0.4.0`、资产 `WxPicLabel_4-0.4.0.apk`），`v0.3.0` 一并迁到 `3-0.3.0`

**验证**
- 实机（MuMu Android 12）：功能页显示 `增加 文件名 开关` + `相册底栏原图右边`；发送设置页只剩裸标题 + `已选 1 个`
- `build.cmd release` 51 s；`check-docs.py` 16 篇全绿；`check-secrets.py` 0 命中
- Release 资产 md5 `028f20fa…` 与本地一致；GS 3.6.0 资产 sha256 与 GitHub digest 逐字节一致

## 2026-11-06 · 重命名规则 + 发送记录 + 三层路由（完成）

**需求（用户）**：记录要能看；重命名要能「一行一条」自己加载；要能导入导出；三级页回二级页的可预测返回动画有问题。

**改动**
- `feature/imglabel/RenameRules.java`（新）：一行一条 `原名 -> 新名`，接受 `=>` / `=` / 制表符，`#` 注释；按文件名精确匹配，解析失败发原名
- `WxPicLabelModule.java`：`textFor` 在计数前套规则；`renameRules()` 按原始字符串缓存解析结果；`pushRecords()` + `checkRecordDump()`（令牌轮询）+ 每批发完推一份；拦截入口的三个判定加留痕日志（原来被 PROTECTIVE 静默吞掉）
- `RepeatCounter.java`：新增 `Row` 与 `snapshot()`（按最后发送时间倒序）
- `feature/imglabel/RecordExporter.java`（新）：计数表分片回传 `content://…bridge/records`
- `app/FriendBridgeProvider.kt`：insert 支持 `friends` / `records` 两条通道（分片键前缀分开）；新增 `RecordStore`
- `ui/ImgLabelRenameScreen.kt`（新）：多行编辑器 + 保存 / 从文件导入（SAF OpenDocument）/ 导出到文件（SAF CreateDocument）/ 复制 / 粘贴
- `ui/ImgLabelRecordScreen.kt`（新）：记录数 + 更新时间 + 列表（文字 / 第 N 次 / 时间）+ 清空
- `ui/ImgLabelSendScreen.kt`：新增「重命名」「发送记录」两个入口，清空动作挪进记录页
- `ui/Routing.kt`：重写为**三层 + 两条独立进度**（`subProgress` / `editProgress`），可预测返回只推最上面那一层；进度仍在 `graphicsLayer` lambda 里读，保住上一版的滑动性能
- `core/ConfigKeys.kt`：`img_label_rename_rules` / `img_label_record_dump`（进 ALL_KEYS / FEATURE_PARAM_KEYS / SNAPSHOT_KEYS / DEFAULTS）

**验证（MuMu Android 12 / 微信 8.0.80 / release 包）**
- 重命名端到端：规则 `RENAMED_OK->RENAMED_TEST_OK` → 发该图 → 日志 `repeat: label=RENAMED_TEST_OK count=1` → `takeover: text#1/1 label=RENAMED_TEST_OK`，聊天里收到的就是改名后的文字
- 记录页：`共 8 条记录`，列出 `RENAMED_TEST_OK 第 1 次 · 11-06 14:03`、`臻宝科技 688797 第 13 次` 等（uiautomator dump 实证）
- 三级页导航：发送设置 → 重命名规则 / 发送记录 进出正常（Android 12 无 predictive back，手势动画按 GSAuxiliary 已验证的实现移植，待 Android 13+ 真机确认）
- `build.cmd testDebugUnitTest` 6/6 绿；`check-docs.py` / `check-secrets.py` 全绿

## 2026-11-06 · 选择好友与群聊交互修整 + 配置通道修复（完成）

**现象（用户报）**：`已选 X 个` 点了没反应；好友名单要手动点才刷新。

**真因（两处，第二处比表象深）**
1. `ScopePickerScreen` 顶卡把「刷新」当成点击动作，没有任何可见反馈
2. **目标进程侧 remote preferences 是附着那一刻的快照**：模块 App 写入的刷新令牌，微信进程读一万次都是旧值 —— 「刷新名单」按钮自上线起就没有在运行期生效过（唯一生效时机是微信重启后的第一次轮询，`lastScopeRefresh` 初始为空）

**改动**
- `ui/ScopePickerScreen.kt`：顶卡点击展开**已选名单**（显示勾了谁、点行即取消）；进页自动写令牌并轮询回传（fetchedAt 变了就停，上限 ~30 s）；新增独立「刷新名单」行（刷新中 / 常态两种摘要）；摘要加「名单更新于 MM-dd HH:mm」
- `WxPicLabelModule.java`：`cfgPrefs()` 按 3 s TTL 重附着拿新快照；重取失败沿用上一份（fail-closed 的 `feature_img_label` 不能因为一次异常被关掉）
- 中英文案 5 条（`scope_refresh_entry` / `scope_refresh_summary` / `scope_refreshing` / `scope_selected_empty` / `scope_updated_at`）

**验证（MuMu Android 12 / 微信 8.0.80 / release 包）**
- 进页自动刷新：微信进程 **1 秒内** `friends: refresh token changed, re-sync` → `fetched 55`（修复前写令牌后 3 分钟零反应）
- 选择页摘要显示 `共 55 个会话 · 名单更新于 11-06 13:21`；点顶卡展开已选名单（uiautomator dump：`Adam.` / `wxid_fvun2vlk6x9n22` 出现在卡片内，y=252/282），点行即取消勾选
- `刷新名单` 行刷新中显示「正在刷新名单…」，完成后回到常态摘要
- `build.cmd release` 45 s 成功；`check-docs.py` 16 篇全绿

**连带收益**：配置改动不再需要重启微信 —— 所有开关（发送提示 / 常亮 / 重复计数 / 白名单）3 s 内在微信进程生效

## 2026-11-06 · 实机发图体验优化 + 发送提示（完成）

**现象（用户报）**：图文一组发完接下一组慢、图越多越明显；发完图片任务后手动发文字还在转圈；要进度 Toast 与屏幕常亮。

**根因（真机日志实证）**
- `sentMisses` 只在等待成功时清零、`startReplay` 不重置 → 两张 miss 后整批不再等事件，**此后每一批都退化且永不自愈**（PITFALLS 16）
- `waitImageSent` 的计数快照取在 `replayOne()` **之后**，事件在同步段落地就白等满 12 s（PITFALLS 17）
- `armFromParams` 先 bump 计数再由兜底轮询做白名单判定 → 名单外会话白涨 +N（PITFALLS 18）
- `UiSender` 每条文字走 3~4 遍全树递归（主线程 O(视图数)），且死等 preclick 120 ms + postclick 150 ms

**改动**
- `UiSender.java`：输入框/发送键按 Activity 实例缓存（fast 路径）；点击自适应（内容落框就点、点完等输入框被清空就走）；日志加 `fast=` 与耗时
- `WxPicLabelModule.java`：每批 `sentMisses=0`；快照前置到 `replayOne()` 前；`intogap` 默认 0→800 ms；`misses>0` 收尾 `drainInFlight`；接 `UiHud`（仅主进程）；`armFromParams` 先过白名单；恢复自测 `text`、新增 `hud`；时序属性每批只读一次
- `UiHud.java`：Toast 复用实例原地更新「正在发送 k/N」+ `FLAG_KEEP_SCREEN_ON`，双开关，结束自动解除
- `core/ConfigKeys.kt`：新增 `img_label_progress_toast` / `img_label_keep_screen_on`（默认开，登记四张表）；顺带把仍在 `ALL_KEYS` 里的 `disclaimer_accepted` 移出（PITFALLS 15 声称已移除，实际漏了一处，单测红灯抓出来的）
- `ui/ImgLabelSendScreen.kt` + 中英文案：「发送提示」分组两个开关

**验证（MuMu Android 12 / 微信 8.0.80，adb 127.0.0.1:16416）**
- `build.cmd testDebugUnitTest` 6/6 绿（改前 2 红：`disclaimer_accepted` 还在 ALL_KEYS）
- 6 张图文 34.79→41.80 = **7.0 s（1.17 s/组）**，`misses=0`，每条文字 `click=true in 9~13ms fast=true`；截图 `.work/run-2.png` 图/字严格交替 + Toast「正在发送 4/6」
- 批次结束 0.98 s 后自测发文字 `click=true in 9ms`，聊天里无转圈（`.work/after-batch.png`）
- HUD 自测 `setprop debug.wxpiclabel.hud 12`：Toast 上屏（`.work/hud-e.png`），`dumpsys notification` Toast Queue 单条且文本 1/20→13/20 原地更新；`keep-screen-on/off` 成对出现
- 白名单外会话发 2 张：只有 `not in scope, let wechat send`，无 `repeat:` 行（修复前会白涨 6 次计数）
- 模块日志：`takeover: done, sent=6 failed=0 misses=0`

## 2026-11-06 · 相册「文件名」开关（完成，随 v0.3.0 发版）

**需求**：微信相册选图页「原图」右边加一个同款开关「文件名」，勾着才补文件名、不勾就微信原生发送；显隐跟随功能总开关与会话白名单；退出相册 / 重启微信不复位。

**踩点（静态 + 运行时双证）**
- 静态（子代理，`.work/evidence/agent-album-ui.md`）：「原图」不是 CheckBox，是 `RelativeLayout(原图行) [WeImageView 圆点 + TextView "原图"]`，勾选态靠换 `res/raw/radio_checked.svg` ↔ `radio_off_white.svg`；底栏是 `RelativeLayout`，原图行 `CENTER_IN_PARENT`
- 运行时（`debug.wxpiclabel.uidump=1` 打视图树）：底栏 `900x72`，原图行 `412,0,487,72`；勾上「原图」后底栏变 `900x140`（「发送后合并展示」行由 GONE 转 INVISIBLE 占位 68px）

**踩的两个坑（都写在 `docs/ALBUM-FILENAME-BUTTON.md`）**
- 第一版照抄原图行 LayoutParams 插到同一个父容器 → 两个视图叠在一起（父容器是 `CENTER_IN_PARENT`）
- 第二版改成 `CENTER_IN_PARENT` 居中 → 勾一下「原图」底栏变高，按钮整体上跳 34px
- 定稿：自绘圆点图标 + 文字行，`RIGHT_OF/ALIGN_TOP/ALIGN_BOTTOM` 全部对齐原图行，布局后按实测宽度算 `dx` 让「原图 + 文件名」整体居中；只改 `translationX`，不碰微信的 LayoutParams

**实现**
- `feature/imglabel/AlbumMemoryToggle.java`（新）：构造器钩子 + 6 次重试注入、找「原图」文本、造行、点击翻转、状态落 `SharedPreferences("wxpiclabel_state")` 的 `album_memory_on`
- `WxPicLabelModule.java`：`hookAlbumToggle` / `scheduleAlbumInject` / `albumScopeAllows` / `memoryOn`；接管入口、`flushFallback`、`armFromParams` 三处加「文件名」闸

**验证（模拟器 emulator-5554 / Android 12 / 微信 8.0.80）**
- 注入后底栏：`预览(1) | ○原图 ○文件名 | 发送(1)`，几何日志 `src=412,0,487,72 mine=502,0,600,72 dx=-56`
- 勾原图后底栏 72→140，两行 box 同步（`src=…68,487,140` / `mine=…68,600,140`）—— 不再上跳
- 不勾「文件名」发图：`fallback: memory off, drop 1`，聊天里只有图、没有文字
- 每次点击一条 `filename toggle: true/false`；`debug.wxpiclabel.albumtoggle=0` 时不注入

**发版**：versionCode 3 / versionName 0.3.0，release 包走 `build.cmd release`

## 2026-11-06 · 实机左右切页卡顿排查与优化

**现象**：模块界面左右切页在实机上明显掉帧，同 UI 的 GSAuxiliary 不卡；模拟器上两者都看不出问题。

**排查（先证伪「代码问题」）**
- 归一化包名 / 品牌名后全量对比两仓 35 vs 141 个 Kotlin 文件：同名文件 13 个有差异，**全部是品牌串、图标路径、日志文件名、tag**；UI 目录（Screens / Dock / Glass / LiquidPhysics / Widgets / ModuleTab / SettingsTab / AboutTab / PrefState）**0 处逻辑差异**
- 同一模拟器（1600×900）同脚本实测：debug 包 1331 帧 / legacy janky 70.70%；release 包 1255 帧 / 45.26% —— 两者肉眼都不卡，说明代码路径不是差异源
- 关键证据：GSAuxiliary 分发包 `.work/GSAuxiliary_57-3.6.0.apk` = **1.59 MB、单 dex**（R8 裁剪过的 release）；本仓此前只有 debug 包（12.2 MB、debuggable）

**结论**：不是 UI 代码问题，是构建产物问题 —— debug 包 `debuggable=true`，ART 不做 AOT、Compose lambda 未内联，每帧重组 / 绘制完整走一遍；模拟器分辨率低、CPU 富余看不出来，实机高分辨率高刷直接顶穿帧预算。

**改动**
- `app/build.gradle.kts`：release 开 `isMinifyEnabled` / `isShrinkResources` / `localeFilters`，`lint.checkReleaseBuilds = false`（lintVital 对 `internal` 的 SettingsActivity 误报 Instantiatable）
- 新增 `app/proguard-rules.pro`：`WxPicLabelModule`（`java_init.list` 反射实例化）、`WxPicApp` / `SettingsActivity` / `DebugConfigProvider`、`io.github.libxposed.service.**`、`ConfigKeys`
- `build.cmd`：支持 `build.cmd release`（`%*` 不受 shift 影响，改为逐个收集剩余参数）
- `ui/Routing.kt`：`val p = subProgress.value` 从组合期移到 `graphicsLayer` lambda，组合期只留 `derivedStateOf` 门槛

**验证**
- `build.cmd release` → `BUILD SUCCESSFUL in 1m 5s`，APK 1,200,488 B（debug 12,197,748 B）
- 包内：单 dex、`META-INF/xposed/*` 三个声明文件齐全；dex 里 `WxPicLabelModule` / `SettingsActivity` / `WxPicApp` / `DebugConfigProvider` 均在
- 模拟器实装：`flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`（debuggable 消失），设置页渲染正常（截图 `.work/shot-rel-1.png`），`logcat -b crash` 空
- `tools/check-docs.py` 15 篇全绿

**待实机确认**：把 `app/build/outputs/apk/release/app-release.apk` 装到真机，左右切页是否已顺（模拟器分辨不出差异）

## 2026-11-06 · 清除 GSAuxiliary 残留 + 目录按五层重整（完成）

- [x] 可达性分析：143 个源文件里 85 个从真实入口（WxPicLabelModule / WxPicApp / SettingsActivity / DebugConfigProvider / LauncherIcon）不可达 —— 全是 GSAuxiliary 的证券业务代码
- [x] 第一刀：移出 85 个文件 / 22,870 行（.work/purged/ 留底），构建仍绿
- [x] 第二刀：Routing.kt 从「二级 + 三级双层动画」砍到单层，路由只留首页 / 免责声明 / 开源声明；SettingsActivity 去掉宿主拉起「一键配置」的 extra 解析
- [x] 第三刀：重算可达面，连带清掉 core/zxcopy、QuickConfig*、QuoteModels、ui/ZxCopy*、ui/WatchlistDetail 等 13 个文件 / 3,674 行
- [x] 包重整：UiSender / RepeatCounter → feature/imglabel/，Reflect → util/（三处包名 + 可见性调整），入口 WxPicLabelModule 留在包根
- [x] 资源瘦身：strings.xml / values-en 各 305 → 113 条（按引用集裁剪），ids.xml 17 个 wxpl_* 证券视图 tag 清零
- [x] build.cmd 去邻仓硬编码 SDK 路径，改三级解析；脚本改纯 ASCII（中文注释曾被 cmd 当命令执行）
- [x] local.properties.example 改占位符 <REPLACE-ANDROID-SDK-DIR>
- [x] docs/ARCHITECTURE.md 重写：目录结构 + 分层判据 + 接管链路 + 兜底 + 配置计数 + 自测通道
- [x] 验收：build.cmd → BUILD SUCCESSFUL；python tools/check-docs.py 10 篇 0 失败；python tools/check-secrets.py 100 文件 0 命中
- [x] 回滚点：`.work/purged/`（98 个被移出的源文件）；重构前整仓快照目录已在 2026-11-06 文档清理时按「重构前完整快照冗余」删除，重构结论见 `CHANGELOG.md` 与本文件后续批次
- [ ] 入口类仍是单体（约 900 行）：等 feature/imglabel 稳定后再拆 ImgLabelFeature / LabelResolver，拆分口径见 docs/ARCHITECTURE.md

## 2026-11-05 · 从临时工程落成标准仓（完成）

- [x] 源码从 `DeepseekHarness/.work/wxpiclabel` 迁入本仓根（app、gradle wrapper、构建脚本、Gradle 配置）
- [x] 丢弃构建缓存与产物：`.gradle`、`.gradle-home`、`.android-home`、`.tmp`、`app/build`、`apkout`、`dist`
- [x] 文档标准化：`README.md` + `docs/{README,ARCHITECTURE,BUILD,DOC-RULES}.md`，索引同步
- [x] 仓级文件：`AGENTS.md`（补构建与当前阶段）、`CHANGELOG.md`、`.gitattributes`、`local.properties.example`
- [x] 门禁：`tools/check-docs.py`（文档）、`tools/check-secrets.py`（凭据）
- [x] 复验构建：`build.cmd` 出 APK

## 2026-11-05 · 门面对齐系列仓通用规则（完成）

- [x] README 改七段式（居中 logo/标题/粗体定位/标语/徽章 → 这是什么/环境要求/安装/反馈/免责声明/开源鸣谢/许可证），去掉「一句话」引言块
- [x] 新增 CONTRIBUTING（带引言块）/ SECURITY / LICENSE（Apache-2.0）/ NOTICE / `.github`（bug、feature 模板 + config + ci.yml）
- [x] 新增 `assets/icon.svg`（108×108 源）与 `assets/logo.png`（512，Pillow 生成）
- [x] docs 按四类重组：ARCHITECTURE / TROUBLESHOOTING / BUILD / VERSIONING / PITFALLS / DOC-RULES / DISCLAIMER + 索引
- [x] tools/README.md（脚本总表），CHANGELOG 增加 [未发布] 段
- [x] 验收：check-docs 8 篇 0 失败、check-secrets 46 文件 0 命中、`.github/**/*.yml` 通过 `yaml.safe_load`

## 2026-11-05 · 模拟器实测与方案重定（完成）

- [x] MuMu Android 12 实例（adb 127.0.0.1:16384）上装通：Magisk 30.7 + 内置 Zygisk + LSPosed 1.9.2 (7024)
- [x] 选图 Hook 实测命中：`AlbumPreviewUI#onActivityResult`；补挂 `MMActivity`/`BaseActivity`/`android.app.Activity` 三路兜底
- [x] 排除四条不可行路径：按类名找 `MsgInfoStorage`（混淆）、扫壳 dex（仅 1 个）、Tinker loader（仅增量类）、合并 18 dex（`MsgInfo` 加载不到）
- [x] 排除 Java 层 SQLite：`insert`/`insertWithOnConflict`/`executeInsert` 三路 Hook 只抓到 WorkManager 表，微信消息落库走 WCDB native
- [x] 方案改为「选图后延迟补发文件名」：记录顺序 + 文件名 → 缓存 talker（hook `com.tencent.mm.storage.a1` 构造器）→ 3 秒后按序 `WeChatSender.sendText`
- [x] 历史尝试已推翻：上述「3 秒后按序 `WeChatSender.sendText`」与待确认项，已由 2026-11-05 批次「8.0.80 无 modelmulti 包、旧 `WeChatSender` 四组候选全部失效、新增 `UiSender`」结论取代

## 2026-11-05 · 发送链路定型和模拟器实测（完成）

- [x] 实测确认 8.0.80 无 `com.tencent.mm.modelmulti` 包（18 dex / 182 MB 全量解析，0 命中），旧 `WeChatSender` 四组候选类全部失效
- [x] 新增 `UiSender`：`ActivityThread.mActivities` → decor view → `MMEditText` → setText → 点「发送」，纯框架 API，零微信内部类依赖
- [x] 发送改为内部串行队列（点完等 600ms 再取下一条），修掉「连发只出去最后一条」
- [x] 模拟器实测：`setprop debug.wxpiclabel.text SELFTEST-V4` → 聊天里真实出现该消息（`ui-send: click=true`）
- [x] 合成选图实测：`setprop debug.wxpiclabel.album aa,bb,cc` → `batch start: 3 images` → `aa`/`bb`/`cc` 三条按序进聊天
- [x] 静态核对选图 key：`key_send_raw_image_path` 不存在，真实 key 为 `key_select_image_list` / `key_select_image_uri_list` / `key_send_raw_image`，提取逻辑改为多 key 多类型
- [x] 新增自测通道（`debug.wxpiclabel.text` / `debug.wxpiclabel.album`），排障不必每次都去相册点选
- [x] 真实选图验证（20:19 实跑）：命中 `key_select_image_uri_list`，1 张，但 label 是 `27` —— 那是 MediaStore 行 ID，不是文件名
- [x] 修复 content:// Uri 取错名：改走 `ContentResolver.query` 取 `_data` / `_display_name`；同一张图复测 label 变为 `mmexport1793877171364`
- [x] 自测通道补 `debug.wxpiclabel.uri <媒体库ID>`，可不点相册直接验证 Uri 解析
- [x] 改「固定 3 秒连发」为「按会话条数逐条补发」：每涨 1 条补 1 条文件名，`pendingOwn` 扣掉自发文字，10 秒无新增则兜底一次发完
- [x] 机制自测通过：`interleave start, n=3, count=17` → 无新增 → `interleave timeout` → 三条兜底发出且 `click=true`
- [x] 修掉 interleave 基线 bug：选图返回时前台还是相册，`chatCount()` 返回 -1 导致直接走 3 秒批量兜底（20:36 实测日志 `interleave start, n=7, count=-1`）；改为轮询等聊天界面出现再取基线
- [x] 单条发送延迟 950ms → 700ms（setText 后 250ms 点击、队列间隔 450ms）
- [x] 逆向定位发图链路第一跳：`Li24/a3#onActivityResult` 取 `getStringArrayListExtra` 的图片路径后 → `Lu24/d0#w(Lu24/c0;Landroid/os/Bundle;)`（Bundle 里 `putStringArrayList`），`u24/c0` 是枚举（相册/拍照/文件）
- [x] **内部发送通道实测打通**：`com.tencent.mm.plugin.messenger.foundation.b3#a(talker, content)` 直接发文本成功——`文件传输助手` 里真实出现 `INTERNAL-PROBE-3`，耗时 ~20ms（不再走 UI setText）
- [x] 关键前提：必须用活动对象的 `DelegateLastClassLoader` 加载 b3，用壳 loader 会抛 `IllegalStateException: Please call init(...) first`（事件系统未初始化，是另一份静态状态）
- [x] `g9.Zb(f9)` Hook 实测拿到完整消息对象，混淆字段映射：`x0`=talker、`Q0`=消息类型（文本 1）、`P1`/`M1`=content、`N1`=createTime、`R0`=自己 wxid
- [x] 逐张补发链路实跑通过（模拟入库）：`image#1→甲`、`image#2→乙`、`image#3→丙`，三次 `sent=true`，每条间隔 ~20ms
- [x] 入库 Hook 扩到 `g9` 上全部 7 个「首参是 f9」的方法（`Zb`/`a(f9,ZZ)`/`mc`/`qc`/`Ha`/`Ea`/`dd`）；实测文本发送会连过 `a(f9,ZZ)` 与 `Zb(f9)` 两条，已加 2 秒去重
- [x] 修掉本轮真因：入库 Hook 只在 `Activity#onResume` 里安装，微信启动时 LauncherUI 已 resume 完 → 该进程 7 个 Hook 一个没装（21:05 日志 `insert via=` 计数 0）。改为启动后轮询当前 Activity 的真实 loader 主动安装，冷启动实测 `hooked storage inserts: 7 methods on g9` 自动出现
- [x] **决定性证据（21:11 实测）**：图片消息一直被入库 Hook 抓到——`insert via=a q0=69 alt=3 talker=wxid_fvun2vlk6x9n22 c=<msg><img cdnthumbheight…`；问题是批次武装发生在 `chain.proceed()` 之后，比图片入库晚 0.8 秒（`21:11:48.7` 图 vs `21:11:49.5` batch armed）
- [x] 修复：新增 `armBatchFast()`，在 `chain.proceed()` **之前**解析 Intent 并武装批次；`captureSelection` 只在未武装时兜底
- [x] 时序仍不可靠（21:18 实测：图片入库 21:18:52.8，`batch armed early` 21:18:53.168，仍晚 0.3 秒）→ 改为**顺序无关**：始终缓存最近 30 秒的图片入库，批次到达时按时间回填并立即补发
- [x] 日志补 `r0=`（发送者 wxid），用于确认 `x0` 到底是会话还是发送者
- [x] 构建环境补齐：run_code 的 `process.env` 为空，`build.cmd` 需要 `USERPROFILE` 与 `JAVA_HOME`（本机 JDK 在 C:\Program Files\Java\jdk-21）
- [x] 环境限制实测：adb `input tap` 与原始 `sendevent`（/dev/input/event4，多点触控）都能点返回箭头，但微信聊天页下半屏（输入框 / 「+」/「发送」）一律忽略注入触摸，只能人工点
- [x] 图片判定改为「正文含 `<img` 或类型非文本（q0!=1）」，不再依赖猜混淆后的 type 常量
- [x] 批次期间对任何入库都打 `insert via=<方法> q0=<类型> alt=<getType> talker=<会话> c=<正文前 24 字>`，用于定位图片消息的真实类型字段
- [ ] 待确认最后一跳：真实发图时图片消息是否命中这 6 个入库方法（模拟事件已验证业务链路，差一次真实发图）
- [ ] 待实测数据：`new messages: k at t=...ms` 判定微信插入节奏
- [ ] 引用条不可做：需被引图片消息的 svrid，WCDB native 落库 + `MsgInfo` 类名混淆，Java 层取不到

## 2026-11-05 · 接管发图尝试（部分失败，已回落）

- [x] 静态链路确认：`AlbumPreviewUI#e8` → `db3.g5#b(Intent,List)` → `db0.u#Vj` → `db0.u#e` → 逐张 `xa0.b0#Oj(f9,String)`
- [ ] 实测：`db3.g5#b` 与 `xa0.b0#Oj` 在「相册选图→发送」这条路径上**均未被调用**（钩子已装，日志零命中）；只有 `db0.u#Vj/e` 出现在调用栈里
- [x] 副作用修复：为接管而停掉回填后出现「只有图没有字」，已恢复回填发字，并给每条之间加可调间隔（`IMAGE_SETTLE_MS` 首条 + `TEXT_TO_NEXT_GAP_MS+900ms` 步进）
- [ ] 结论：接管内层发送在当前入口不可行；严格交替只能换入口（相册可能是另一个 UI 实现）或改走"图先字后 + 大间隔"的折中

## 2026-11-05 · 图片上传闸门（一图一文字的硬保证）

- [x] 方案：卡在【消息入库】而不是【上传进度】——微信流程是「入库 → 上传」，按住入库等于按住这张图的上传，效果等价且可回退；卡在 99% 需要 hook NetScene/native 的进度回调，容易堵死网络线程
- [x] 实现：`beforeImageInsert()` 在 `g9` 入库 Hook 的 `proceed()` 之前等闸；`releaseImageGate()` 在 `UiSender` 点完「发送」后放行下一张
- [x] 三个保护：只在相册会话内生效（`AlbumPreviewUI#onCreate` 打标记）、主线程不阻塞（避免 ANR）、等文字最多 8 秒超时放行、没有待发文字时不关闸
- [x] 修：图片类型判定改用 `alt=getType`（文字=1、图片=3）；`q0` 对所有消息恒为 69，是之前「发两遍」的根因
- [x] 修「丢一条文字」：`UiSender.step()` 在 setText 前 poll 了一次、点击完成回调里又 poll 一次 → 每条被消费两次，第二条被静默吃掉；改为只在点击完成后出队
- [x] 修「闸门没生效」：`AlbumPreviewUI#onCreate` 的钩子挂上了但从未被调用（各进程均无 `gallery session started`），`gallerySession` 恒 false；改为在 `Activity#onResume` 里按类名含 "gallery" 识别
- [x] 实测结论：闸门与相册「正在发送中」**互锁**——按住入库后相册在等发送完成，而文字必须等回到聊天界面才能输入，于是卡到 8 秒超时；第一次成功是因为武装晚于入库（走回填），闸门没参与
- [x] 闸门已关闭（`GATE_ENABLED = false`），回到「图先字后、不丢不重、不卡」的稳定版
- [x] 预武装点已定位到 `ImagePreviewUI#onCreate`（能从 Intent 读到选图列表），保留代码，等接管发图后再启用
- [ ] 严格交替的唯一剩余路径：接管发图（自己逐张发），需用动态探针定位 `ImagePreviewUI` 的发送方法（156 个混淆方法）

## 2026-11-05 · 发送通道定论（用户实测确认）

- [x] 三种"发送"的实测差异（同一会话，截图 21:47/21:52/21:54）：`b3.a` → 灰色居中的系统提示（反编译显示它写死 `setType(10000)`）；自建 f9 入库 → 左侧对方气泡（方向错、且只写本地库）；**UiSender 走 UI → 右侧绿色气泡，对方真能收到**
- [x] 结论：只有 UI 发送走微信真实发送队列；内部插入仅本地可见，对方收不到
- [x] 改动：批次补发与缓存回填全部改为 `UiSender.send`，内部插入仅保留给探针

## 2026-11-05 · REA 反编译定位

- [x] REA 接入：profile 里 `mcp-rea` 是 `disabled: true`，不重启会话直接用 `node rea.mjs mcp` + JSON-RPC 调用；引擎 jadx-headless 0.7.1，需显式 `JAVA_HOME`（JDK21）
- [x] `inspect_android_package`：微信 8.0.80 索引成功，237475 个类
- [x] 反编译 `AlbumPreviewUI#onActivityResult`（44541 字符体）：只做 `putStringArrayListExtra("key_select_image_list", …)` + `Q8(-1,intent)`（setResult）+ `finish()`，**本身不发送**，发送在更早的 `ImagePreviewUI`
- [x] 反编译 `g9#Zb(f9)` → 只有 `return a(f9, false, true)`；`f9` 有 12 字段 / 119 方法，字段名全混淆
- [x] **关键发现**：上一轮 3 条文件名确实发出去了，只是落到 `Adam.` 会话（列表里 Adam. 行显示「征和工业 003033」），图片在 `Empty.`
- [x] `debug.wxpiclabel.talker` 探针确认 `Empty.` 的会话 id 是 `wxid_4gcobapv09th22`；图片消息里这个值在 `R0`，不在 `x0`
- [x] 修：`onMessageInserted` 取会话优先 `R0`、回退 `x0`

## 2026-11-05 · 会话收尾（交接状态）

**当前能用的**
- 选图后按选图顺序补文件名，走 UI 真实发送（对方可见、不丢、不重）
- 相册不卡「正在发送中」
- 自测通道：`debug.wxpiclabel.text` / `debug.wxpiclabel.talker` / `debug.wxpiclabel.simulate`

**当前不对的**
- 顺序：图先全部落地，字再按间隔跟上（间隔由 `IMAGE_SETTLE_MS`（首条 1.5s）+ `TEXT_TO_NEXT_GAP_MS`（步进 2.4s）控制）
- 两张图 + 两个字会出现"黏住"（图快字慢）

**已排除的路**
- `b3.a`：写死 `setType(10000)`，发出来是灰色系统提示，对方收不到
- 自建 f9 + `g9.Zb`：只写本地库，方向还反（渲染成对方消息），对方收不到
- 拦截入库（闸门）：与相册「发送中」互锁，卡 20 秒
- 逐张发送钩子 `xa0.b0#Oj`：在「相册选图→发送」路径上未被调用（日志零命中）
- `db3.g5#b(Intent,List)`：同样是另一条链路，未被调用

**下一会话第一件事**
1. 在 `db0.u#Vj` 里打印 `n0.c(f1.class)` 返回对象的 `getClass().getName()`，拿到相册实际使用的发送实现类，再挂它的 `Oj(f9,String)`
2. 拿到后按 `图k → 1.5s → 字k → 1.5s → 图k+1` 串行；失败就回落到当前"图先字后 + 间隔"

**分析工具**（已从临时目录搬入仓内）
- `.work/rea/`：dex 解析（`dexq.py` `q2.py` `q3.py` `callees.py` `whosends.py`）与 REA MCP 调用脚本（`rea3.mjs`，直接 `node rea3.mjs call <tool> <json>`）
- `.work/evidence/`：关键截图（三种发送形态对照、探针落点）

## 2026-11-05 · 发送链路真相 + 严格交替实现（待真机发图验证）

**推翻上一轮交接的前提（两路静态逆向，证据在 `.work/evidence/agent-static.md`、`agent-dex-scan.md`）**
- `f1` = `pt.f1`：纯标记接口（0 个方法），全 APK 唯一实现 `xa0.b0`
- `xa0.b0#Oj(f9,String)` **不是发图**：方法体是 HD 缩略图刷新（`MicroMsg.ImgDownload.MsgImgLoaderFeatureService` / `onMsgImgHdThumbGenerated`），第二参是 source 标签；所以挂它必然零命中
- `db0.u#Vj(qa0.k)` = `ImgUpload.SendImgPreFeatureService` 的批量准备入口：先 `e()` 把这一批图**全部**入库（逐张 `g9#Zb(f9)`），循环里只是逐张刷缩略图 —— 「图先全部落地」的根因在此
- `n0` = `fi5.n0`（`MicroMsg.ServiceManager`，`c(Class)→fi5.m`），运行期实测命中并在 `db0.u#Vj` 里打出了服务解析
- 真发送 = `s61/b0`（`MicroMsg.SendMsgService`，`/cgi-bin/micromsg-bin/newsendmsg`），逐张入口 `q61.j1#k(gd5.gu4,f9)`

**改动（本仓）**
- [x] 严格交替改走「入库钩子里同步发字」：`g9` 入库是 `db0.u#e` 里同一线程的逐张循环；第 k 张入库返回后立刻发第 k 个文件名并等它点完（`sendLabelBlocking`），微信才会准备第 k+1 张 → 图1 → 文件名1 → 图2 → 文件名2；连续 2 次超时自动退化为「图先字后」，不让发图线程被拖死
- [x] 批次武装点提前到 `db0.u#Vj` 的实参 `qa0.k`（`g` 字段=选图列表），比选图结果回调早一整轮 preparation；旧的「入库缓存回填」保留为兜底（按 `b.index` 跳过已发条目）
- [x] `UiSender` 支持「后台聊天页」：相册/预览页置顶时前台没有输入框，改为扫 `ActivityThread.mActivities` 里带 `MMEditText` 的聊天页直接 setText + 点「发送」（LOOSE 模式，不看 `isShown`）
- [x] 自测通道补 `debug.wxpiclabel.bgsend`；属性值 `0` 视为未设置；自测/轮询只在主进程跑（修掉 `:push`/`:support` 各发一遍的重复）
- [x] 探针：`db0.u#Vj` 实参 dump、`fi5.n0#c(Class)` 服务解析日志、`db0.u#e` 准备循环、文本入库调用栈（`text-stack`，抓到真文本发送链路 `s61.q#i` 的入库段）

**机制验证（模拟两图，非真机发图）**
- `fake image insert #1` → `interleave#1/2 label=甲 queued=true` → 输入框就绪后 `ui-send: click=true` → `fake insert #2` → 字2 → `interleave batch done, n=2`
- 6 秒超时与「连续失败 2 次退化」保护都触发过，不会卡住

**真机复测（两次，结论：相册窗口内阻塞发字不可行）**
- 第 1 次真机发图（23:31）：`img-stack` 证明入库来自 `db0.u#e:882 ← db0.u#Vj:75`，但**没有任何 `batch armed at Vj`**，走了 `drain: buffered images=6 need=3` → 「图先字后」。原因：Vj 钩子装在壳 loader 上，微信真实执行的是 Tinker `DelegateLastClassLoader` 里的**另一份** `db0.u`。已修：`installSendHooks(loader)` 用 `application.getClassLoader()` 重装，日志实测 `hooked Vj on loader dalvik.system.DelegateLastClassLoader[... tinker/patch-c594ae0e ...]`
- 第 2 次真机发图：Vj 钩子生效了，但相册窗口内聊天页的输入框拿不到 → `interleave` 超时、相册一直转「正在发送中」、文字全部发不出去（用户实测）。结论：**只要还靠 UI setText + 点发送，就不能在相册窗口内发字**——相册把聊天页压住了
- 后台聊天页发送本身是可用的（独立验证：前台是系统设置页时 `ui-send: act=com.tencent.mm.ui.LauncherUI loose=true click=true` → `insert via=a ... c=LOOSE-A`），只是相册窗口那种状态下拿不到

**最终形态（本轮回退并固化）**
- [x] `sendLabelBlocking` 默认不阻塞：`strictEnabled()` 由 `debug.wxpiclabel.strict=1` 打开，默认关；入库钩子只记 `defer label#k`
- [x] 回聊天后补发：轮询（1.2 s）+ `Activity#onResume` 两路触发 `flushLabels`，按 `settle + k*gap` 间隔发剩余文件名，`pendingBatch` 清空后再收尾
- [x] 间隔现场可调：`debug.wxpiclabel.settle`（首条延迟，默认 1500 ms）/ `debug.wxpiclabel.gap`（步进，默认 1500 ms）
- [x] 探针：`debug.wxpiclabel.map` 打出 f9 全部 setter→getter 映射（`a1`→P1/M1/j 正文、`t1`→x0/O0 发送者、`d1`→createTime、`setType`→type、`j1`→A0），`debug.wxpiclabel.recipe` 录制真实文本消息的 setter 调用序列
- [ ] 想真正做到严格交替，只剩「内部发送」一条路：造 `f9`（要补 R0/talker 的写入口，映射里没找到）+ 触发 `s61.b0#Rj()`（SendMsgService 的 looper 启动器，无参 void）把消息真正发出去；这条路没走完，本轮按交接约定收在「图先字后 + 可调间隔」

## 2026-11-05 · 接管发送：相册只当选图器（实现完成，待真机验证）

**用户方案**：相册只负责选图，选完把列表交给插件，由插件自己按「图1 → 文件名1 → 图2 → 文件名2」发。

**实现（本仓）**
- 挂 `on5.f#a(on5.f, qa0.k, I, String, I, J, ia5.a, String, Runnable)V`（相册发送总入口，void，实测已装：
  `hooked takeover entry: on5.f#a params=9 kIdx=1 rIdx=8`）。
- 用户点「发送」时**不放行**：先 `main.post(Runnable)` 调微信自己的回调让相册界面收工（避免「正在发送中」），
  然后由 `startReplay` 在独立线程里逐张处理：把 `qa0.k` 的列表字段临时改成单元素 → 反射调 `db0.u#Vj(params)`
  发这一张 → 等 700 ms → `UiSender` 发对应文件名（此时相册已收工、聊天页在前台）→ 间隔 `gap` → 下一张。
- 兜底：任何一步抛异常就 `replayOriginal()` 把微信原来那次 `on5.f#a` 补回去，图照样能发（只是退回图先字后）。
- 开关：`debug.wxpiclabel.takeover` 默认开，`=0` 关掉即回到「defer + flush」稳定版。
- 探针：`eb0.e#O7` / `lt5.m0#run` 只读钩子，用来确认相册走的到底是哪条上游入口。

**真机验证结果（成功，2026-11-05 23:54）**
- 时间线：`40.34 图1 → 41.50 字1 → 43.41 图2 → 44.42 字2 → 46.33 图3 → 47.35 字3 → takeover: done, images=3 failed=0` —— 严格交替成立
- 踩坑 1：`takeover replay failed: android.util.AndroidRuntimeException: Animators may only be run on Looper threads` —— `UiSender.send` 在队列空闲时就地跑 `step()`，接管线程不是 Looper 线程 → 已改为非主线程一律 `main.post`
- 踩坑 2：异常后 `replayOriginal()` 拿到的 `params.g` 已被改成单元素，导致「只发一张、另一张卡死」→ 现在失败项单独收集、只回放失败的那几张
- 踩坑 3：只重放 `db0.u#Vj` 时图不进入上传（Vj 只入库 + 刷缩略图，上传编排在入口 `on5.f#a` 的后半段）→ 改为**整条入口逐张重放**
- 踩坑 4：`debug.wxpiclabel.simulate=0` 被当成有效值 → 多发一条内容为 `0` 的消息；该分支已改用 `propSet()`

**体验调优（2026-11-05）**
- **判据升级（关键）**：不再靠固定延时猜上传进度 —— 挂 `xm5.y#Fj(f9)`（`album_media_send_succ`，即气泡转圈消失那一刻），每张图发出去后等这个事件到了再打字（`waitImageSent`，60 ms 轮询、上限 12 s、连续 2 次等不到自动退回固定延时）。该方法是静态化签名 `Fj(xm5.y, f9)`，f9 在 `f9Idx=1`
- 因为判据变成事件驱动，固定延时可以归零：**当前定稿全 0** —— `intogap` / `gap` / `preclick` / `postclick` / `confirmgap` = 0（属性 `debug.wxpiclabel.*`）
- 零延时模式的安全阀：`UiSender` 新增 `sameRetry` —— setText 还没被 TextWatcher 处理完（内容不一致）时重来一次（最多 2 次），避免零延时导致「判失败丢字」
- 「字发出」的判据 = `UiSender` 的点击完成回调（latch），不是猜时间；所以 `gap=0` 就是「点完立刻发下一张」
- 历史三档留档：700/1500/250+450（最慢，纯猜）→ 300/600/120/200（会粘）→ 500/1050/185/325（中间值，纯猜）→ 100/250/150/250（事件驱动，当前）
- 原「图→字 700 / 字→图 1500 / 点击 250+450」是最慢的一版，可作上限参考
- 进度提示：已按用户要求**取消**（`UiSender.showProgress` / `hideProgress` 保留实现，接管流程不再调用）；微信自己的「正在发送中」在接管后不会出现

## 2026-11-06 · 文字引用对应图片（实现完成，待真机验证）

**链路（静态 + 方法体实证）**
- `ChatFooter#R(f9, ga5.i)Z`：把消息包成 `storage.ca` 挂在 footer 的 tag 上，然后显示引用条（`V1`/`a2`/`n1`/`O`）——这就是长按气泡点「引用」时微信自己做的事
- `ChatFooter#Q0(String, Integer, HashMap)Z`：发送时读 `getTag()`，用 `plugin/msgquote/model/MsgQuoteItem` 拼出 `<refermsg>` 塞进 appmsg —— 所以只要先调 R，后续 setText + 点「发送」天然就是引用消息
- `ChatFooter#getQuoteMsgInfo()f9` 可用来校验引用是否已挂上

**实现**
- `UiSender.sendQuoted(main, content, done, quoteMsg)`：队列项带上引用目标；`step()` 在主线程先 `setQuote(decorView, quoteMsg)` 再 setText + 点发送
- `UiSender.setQuote`：在聊天页视图树里找 `ChatFooter`，反射调 `R(f9, null)`，随后用 `getQuoteMsgInfo()` 校验并打日志 `quote: set -> … getQuoteMsgInfo=…`
- 模块侧：接管期间 `takingOver=true`，`onMessageInserted` 抓到刚入库的图片 f9（`takeoverImageMsg`），发文字时把它作为引用目标（日志 `takeover: text#k … quote=<msgId>`）

**真机结果：引用条能挂上，但渲染成「引用内容不存在」→ 已回退为不引用**
- 挂引用成功（聊天里出现了引用条），但引用块内容取不到。依据：`ChatFooter#Q0` 用 `Lm.d8#G0()J` 填 `refermsg.svrid`，而图刚发出时 svrid 尚未由服务器回填（补了 `waitSvrid` 轮询 15 s 仍未拿到非 0 值），渲染端按 svrid 反查失败
- 已加开关 `debug.wxpiclabel.quote`（默认 **0 = 不引用**）与控制台日志 `waitSvrid`（成功打 `svrid=… after Nms`，超时打 `svrid still 0 … dump=<全字段>`）；实现保留，等能拿到 svrid 再开
- 下一步想继续的话：从 dump 里认出真正的 svrid 字段，或改填本地 `msgId` + `chatusr/fromusr`，让微信在本地反查

## 2026-11-06 · 代码精简 + 兜底化（功能不变，代码量 3900 → 1527 行）

**只保留实测跑通的那条链路**
- `on5.f#a` 接管（逐张重放 + 调微信回调让相册收工）
- `db0.u#Vj` 只做兜底取选图列表（接管没跑起来时用）
- `xm5.y#Fj(f9)` 单张发送成功事件（打法名的节拍）
- `UiSender`：找输入框（含后台聊天页 LOOSE 模式）→ setText → 点「发送」，串行队列 + 零延时重试

**删除**（都是探路留下的，实测无用或已被否掉）
- 旧 `Batch.java` / `QuoteContent.java` 两个类（引用方案已回退）
- `g9` 入库 7 路钩子、图片闸门（`beforeImageInsert`/`releaseImageGate`/`imgGate`）、`xa0.b0#Oj` 钩子、`db3.g5` 钩子、`db0.u#e` 准备循环钩子
- 探针：`fi5.n0` 定位器、`f9` setter 映射（`map`）、字段录制（`recipe`）、文本调用栈（`text-stack`/`text-dump`）、`img-stack`
- 旧内部发送通道：`b3.a`、自建 `f9` + `g9.Zb`、`sendTextInternal`
- 自测通道：`album`/`uri`/`simulate`/`talker`/`api`/`map`/`recipe`/`bgsend`（只留调试属性改动时序）
- 引用：`UiSender.setQuote`/`sendQuoted`、`waitSvrid`（已确认渲染失败）
- 进度提示：`showProgress`/`hideProgress`（用户要求取消）
- `UiSender` 里无人调用的 `chatCount`/`currentActivityOrNull`/`hasChatableTarget`

**兜底化写法**
- 每个钩子独立 try/catch + 按 loader 去重（`markHooked`），任何一个装不上只是少一个能力
- 接管里任何一步失败：`restoreAndFallback` 把参数列表恢复成全部 → `replayOriginal()` 交回微信整批发 → 文件名进兜底队列
- 兜底队列：`startFallbackPoller` 每 700 ms 检查「接管没在跑 + 聊天页可用」，按顺序补发；120 s 过期丢弃并记日志
- 时序参数全走系统属性（`intogap`/`gap`/`preclick`/`postclick`/`confirmgap`/`confirmtimeout`），默认全 0；`confirmtimeout` 默认 12 s，连续 2 次等不到成功事件就退化成固定间隔

**代码量**：`WxPicLabelModule` 776 行 + `UiSender` 498 行 + `Reflect` 253 行 = 1527 行（精简前同目录 3900+ 行、5 个文件）

## 2026-11-06 · 门面/结构对齐 GSAuxiliary + UI 整套移植（骨架已跑起来）

**结构（照 GSAuxiliary）**
- 源码包从「3 个 java 平铺」改为分层：`app/`(7) `core/`(13) `core/zxcopy/`(7) `feature/`(同名成对 Feature+Targets) `ui/`(33) `util/`(3)，共 141 个 `.kt` 从 A 仓整体复制后统一改包名 `io.github.MVennn.GSAuxiliary` → `io.github.mvennn.wxpiclabel`
- 本模块自己的 Hook 代码仍是 3 个 java（`WxPicLabelModule`/`UiSender`/`Reflect`），留在包根
- 构建：改为 Kotlin 2.4.0 + Compose + Miuix 0.9.3 + backdrop 1.0.6 + libxposed service/annotation，compileSdk 37 / minSdk 27 / targetSdk 36 / JVM 17（与 A 一致）；`BUILD_TIME`/`MIUIX_VERSION` 两个 BuildConfig 字段补齐
- 资源与清单：`res/`（values、values-en、values-night、drawable*、mipmap*）整份照搬；`Theme.GSZX` → `Theme.WxPic`；Manifest 改为「SettingsActivity（MAIN + MODULE_SETTINGS，恒 enabled）+ activity-alias 桌面图标 + DebugConfigProvider」；provider authority 改成自己的包名（否则与已装的 GSAuxiliary 冲突：`INSTALL_FAILED_CONFLICTING_PROVIDER`）
- 应用名/描述：`app_name=WxPicLabel`、`xposed_description=微信发图时按选图顺序在每张图后补一条文件名`

**验证**
- `build.cmd` BUILD SUCCESSFUL，APK 11.8 MB（含 Compose/Miuix）
- 模拟器实装并打开 `.ui.SettingsActivity`：标题 WxPicLabel、「已激活 / LSPosed 2.2.1 / API 102」卡片、模块版本 v0.2.0(2)、使用前须知弹层、底部「设置/关于」Dock —— 截图 `.work/evidence/s-ui2.png`

**功能页已换成只有本模块的功能（2026-11-06 00:33 实机验证）**
- `ui/FeatureCatalog.kt` 的 `FEATURE_CATALOG` 从 A 仓 19 条证券功能裁成 1 条：`id=img_label`、分组「发送」、标题「发送图片文件名」、副标题「相册选图发送后，按选图顺序在每张图后补一条文件名」、`enableKey=ConfigKeys.IMG_LABEL`、默认开
- `core/ConfigKeys.kt` 新增 `IMG_LABEL = "feature_img_label"` / `DEFAULT_IMG_LABEL = true`，并登记进 `FEATURE_KEYS` 与 `SNAPSHOT_KEYS`（漏 `SNAPSHOT_KEYS` 会静默失效）
- `res/values/strings.xml` + `values-en/strings.xml` 补 `category_img_label` / `feature_img_label_switch` / `feature_img_label_summary`
- 实机：功能页只剩这一条（截图 `.work/evidence/s-ui-feature.png`），开关默认开

**仍未剔除的 A 仓残留（下一批）**
- `feature/` 下 18 个证券功能包（aistare/dock/filter/home/ipoclean/market/navbar/stgfilter/stgsnap/stockbar/strategyquote/strategyselect/uiclean/watchlist/zxcopy）与对应 `ui/` 二级页（QuickConfig*/ZxCopy*/Watchlist/StrategyQuote/AiStare*）、`strings.xml` 里 41 条证券文案
- 它们已不被功能页引用（目录裁掉了），属死代码；删除须连同 `Routing.kt` 的 id→Route 映射与 `Screens.kt`/`SettingsTab.kt` 的入口一起处理，否则编译断

## 2026-11-06 · 「发送设置」（重复图片追加 +2/+3）踩点 + 方案（未写代码）

**需求**：同名图片重复发送时，在文件名后追加 ` +` + 次数。第 1 次原文，第 2 次 `征和工业 003033 +2`，第 3 次 `+3`。**认名字不认内容**（第二天重新截图，内容 SHA 变了但名字不变，仍要接着数）。

**踩点结论（证据都在代码里）**
- 文字由 `WxPicLabelModule#labelOfEntry` → `#labelOf` 生成，接管（`on5.f#a`）与兜底（`db0.u#Vj`）两条路都走它 —— 只改这一处即可覆盖全部发送路径
- 模块**从不读配置**，只认 `debug.wxpiclabel.*` 系统属性；因此 `feature_img_label` 总开关目前是装饰性的，本轮要一并接上
- 目标进程侧 remote preferences **只读**，模块 App 目录**跨 uid 不可写** → 计数只能落在微信进程自己的 SharedPreferences
- 二级页机制现成：`FeatureSpec.detailEntryRes` + `FeatureEntry` 展开 + `Routing.onOpenFeatureDetail` 按 id 分发，照 `ZxCopyButtonScreen.kt`（52 行）即可
- `ConfigSnapshot` 指纹布尔位已用 26/32，只剩 6 位；`app/src` 无测试目录，注释引用的 `ConfigSnapshotFingerprintTest` 不存在

**方案**：`docs/IMG-LABEL-SEND-SETTINGS.md`（已登记索引，`check-docs.py` 10 篇全绿）
- 计数键 `n:<label>` → `"<count>|<lastMs>"`（label = 实际发出去的文字），300 条上限 + 最旧淘汰；不算哈希、不读文件，零 IO
- 不计数：label 为空或为 `unknown`（取不到路径时的兜底值）时按第 1 次发原文
- 清空靠配置键 `img_label_repeat_reset`（毫秒时间戳）比对，不需要跨进程监听器
- 防重复计数：每批一个 `IdentityHashMap` 后缀缓存，回退路径复用同一份
- 模块直接 `getRemotePreferences(ConfigKeys.GROUP)` 读 3 个键，不动 `ConfigSnapshot`/`ConfigSource`

**下一步**：等确认后按文档 §5 三批落地（App 端 → 模块端 → 文档/门禁）

## 2026-11-06 · 「发送设置」落地（同名图片 +2/+3）

**实现（与方案文档 `docs/IMG-LABEL-SEND-SETTINGS.md` 一致，一处按现场调整）**
- `feature/imglabel/RepeatCounter.java`（新；随后并行会话把模块 Java 收进了 `feature/imglabel/`，包名已同步）：`bump(label, resetToken)` → 次数；键 `n:<label>` → `"<count>|<lastMs>"`，300 条上限按最后发送时间淘汰最旧；令牌不同则整体 `clear()`；任何异常返回 1（按第 1 次发原文）
- `WxPicLabelModule.java`：新增 `cfgPrefs()`（`getRemotePreferences(ConfigKeys.GROUP)` 懒加载）、`cfgBool/cfgStr`、`featureEnabled()`、`repeatCounter()`、`textFor(entry)`
  - 接管入口加总开关判断：`!featureEnabled()` 直接放行 → 微信原生发送
  - 每批开始 `batchTexts = new IdentityHashMap<>()`，`textFor` 按**条目对象身份**缓存，接管/兜底/回退三条路复用同一份，不会跳成 +3
  - 接管开始时把 `pendingLabels` 取出来复用并清空：若 `db0.u#Vj` 先到已算过文字，直接按序号取，既不重复计数也不会被轮询再补一遍
- App 端：`ui/ImgLabelSendScreen.kt`（新，两个分组「重复图片 / 记录」）、`FeatureCatalog` 挂 `detailEntry*`、`Routing` 加 `Route.ImgLabelSend`、`Screens` 的 `HomeScaffold` 补 `onOpenFeatureDetail` 回调、中英文案 10 条（顺手补上 `values-en` 一直缺的 `category_img_label` 三条）

**现场情况：另一会话同时在改 UI**
- 期间本仓 `Routing.kt`（435→210 行）、`Screens.kt`、`SettingsActivity.kt` 被并行会话重写：证券残留二级页被裁掉，`FeatureTab(onOpenDetail = { _, _ -> })` 成了空回调
- 因此本轮把「功能 → 二级页」的接线**重新接上**（`HomeScaffold.onOpenFeatureDetail` → `FeatureTab` → `Routing` 的 id 映射），并保持极简两层路由的写法，没有恢复三级页那套

**验证**
- `build.cmd` → `BUILD SUCCESSFUL in 44s`，APK 12,229,416 B
- 产物核对：`classes5.dex` 含 `RepeatCounter`、`classes7.dex` 含 `ImgLabelSendScreen`、`resources.arsc` 含 `img_label_repeat_*` 四条新文案；`WxPicLabelModule.class` 里能查到 `textFor` / `wxpiclabel_repeat` / `repeat: label=`
- 未做真机验证：计数需要真实发图两次，等实机

## 待办

- [x] 校准 `WeChatSender` 的发送入口：已判不可行（候选类全部失效），发送链路改由 `UiSender` 视图层承担
- [ ] 引用条待 msgId / svrid 方案：WCDB native 无 Java 插入点，拿到 msgId 之前只发纯文字
- [ ] UI（总开关 / 白名单）