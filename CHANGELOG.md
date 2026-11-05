# Changelog

## [0.3.0] - 2026-11-06

- 新增 相册「文件名」开关：微信相册选图页底部「原图」右边多一个同款开关（原图左移、整体居中、间距 12dp），勾着才补文件名、不勾就退回微信原生发送；状态存在微信进程自己的偏好文件里，退出相册 / 重启微信都不复位，只有手动点击才变（`docs/ALBUM-FILENAME-BUTTON.md`）
- 新增 相册开关的显隐跟随模块配置：功能总开关关闭时完全不注入；「仅选中的会话生效」开着时，名单外的会话不注入
- 新增 排障开关 `debug.wxpiclabel.albumtoggle=0`（不注入）与 `debug.wxpiclabel.uidump=1`（打视图树）
- 修复 相册开关第一版与原图完全重叠：原图行是 `CENTER_IN_PARENT`，照抄 LayoutParams 会叠在同一个位置 —— 改为自绘圆点行 + 相对原图行定位
- 修复 勾一下「原图」按钮就往上跳 34px：底栏被「发送后合并展示」行从 72px 撑到 140px，居中子视图会整体上移 —— 改为上下都对齐原图行、只做整对水平平移
- 调整 未勾「文件名」时兜底队列连文字都不算（不再白涨 `RepeatCounter` 的 +N 次数）
- 优化 实机左右切页卡顿：release 开 R8（`isMinifyEnabled` + `isShrinkResources` + `localeFilters = [zh, en]`），新增 `app/proguard-rules.pro` 保住模块入口 / Application / Activity / Provider / libxposed 服务通道 / `ConfigKeys`；`build.cmd release` 出正式包（1.2 MB、单 dex，此前只有 debug 12.2 MB）。同一份 UI 代码在 debug 包上掉帧、release 上顺 —— 两包在模拟器帧数据一致，差异是 debuggable 带来的 CPU 开销
- 优化 `ui/Routing.kt` 二级页滑动：进度从组合期移到 `graphicsLayer` 的 lambda，300 ms 滑动期间不再每帧重组整页（MainScreen → HomeScaffold → 当前板块整棵树），组合期只留一个 `derivedStateOf` 门槛布尔
- 修复 功能开关出厂默认是「开」：`DEFAULT_IMG_LABEL` 改 `false` —— 装完模块、什么都不改，微信行为与原生完全一致；子参数 `img_label_repeat_count` 保持默认开（只在总开关打开后才有意义）。口径与 GSAuxiliary 2.11.2 起一致，理由见 `docs/CONFIG-MEMORY.md` §1
- 新增 首次连接补齐出厂默认键：`ModulePreferences.ensureRemoteDefaults()` 在框架连上时（`migrateLocalToRemote` 之后）把 `ConfigKeys.DEFAULTS` 里远端没有的键补写一份，只补不覆盖、幂等；备份与调试导出不再是「只有用户动过的那几个键」的残缺表
- 修复 `disclaimer_accepted` 被放进 `ALL_KEYS` / `DEFAULTS`：备份文件会把「已同意」带到另一台机器（换机即绕过免责声明），「重置模块」还会清掉同意态 → 移出两个表，只走本地文件（`getLocalBoolean`）
- 调整 功能判定的失败方向随之变为 fail-closed：`cfgBool()` 拿不到 remote preferences（框架未连接 / 读取异常）时回落 `DEFAULT_IMG_LABEL`，默认值翻成 `false` 之后**模块没连上 LSPosed 就不接管**。此前是 fail-open（照样接管），属隐式行为变更，排错手册第 1 节已把「开关是打开的吗」列为第一条
- 新增 `docs/CONFIG-MEMORY.md`：配置记忆正本（出厂默认 / 首次补齐 / 重置与备份边界 / 三层职责 / fail-closed 推论）
- 重构 清除 GSAuxiliary 残留、目录按五层重整：删掉 98 个证券业务源文件（约 2.65 万行：feature/aistare、dock、filter、home、ipoclean、market、navbar、stgfilter、stgsnap、stockbar、strategyquote、strategyselect、uiclean、watchlist、zxcopy 与 core/zxcopy、QuickConfig、QuoteModels 等）；路由砍到「首页 + 免责声明 + 开源声明 + 发送设置」两层；strings.xml 各语言 305 → 113 条；ids.xml 17 个证券视图 tag 清零；入口类留在包根，业务下沉 feature/imglabel/（UiSender、RepeatCounter），反射工具归 util/
- 调整 build.cmd 不再写死邻仓 SDK 路径：环境变量 → 仓内 .work/android-sdk → local.properties 依次解析，且脚本改为纯 ASCII（cmd.exe 按 OEM 代码页读 .cmd）
- 新增 「发送设置」（功能页「发送图片文件名」下的二级页）：**同名**图片重复发送时在文字后追加 ` +2`、` +3`…… —— 认名字不认内容，第二天重新截的同名图照样接着数；计数落在微信进程自己的 SharedPreferences（remote preferences 目标进程侧只读、模块 App 跨 uid 不可写），设置页给「清空发送记录」，靠毫秒令牌单向通知微信进程清空
- 调整 「发送图片文件名」总开关**真正生效**：关掉后模块完全不接管，退回微信原生批量发送（此前这个开关只改界面、不影响行为）
- 修复 微信 8.0.80 发送入口失效：删除按类名反射的 `WeChatSender`（8.0.80 已无 `modelmulti` 包），改为视图层发送器 `UiSender`（EditText + 「发送」按钮，纯框架 API）
- 修复 连发多条只出去最后一条：`UiSender` 内部串行队列，点击前校验输入框内容
- 修复 选图结果取不到路径：`key_send_raw_image_path` 在 8.0.80 不存在，改用 `key_select_image_list` 等真实 key，并兼容 String / Uri / String[] 三种形态
- 修复 选图返回 content:// Uri 时把媒体库 ID 当文件名发出去（发 `27` 而不是 `mmexport1793877171364`）：改走 ContentResolver 解析 `_data`/`_display_name`
- 调整 补发时机：由「固定 3 秒后连发 N 条」改为「盯会话条数，每多一条消息补一条文件名」，多图时可做到图后紧跟名字（一次性插入时退化为批量）
- 新增 自测通道：`debug.wxpiclabel.text` 发文字、`debug.wxpiclabel.album` 合成一次选图返回、`debug.wxpiclabel.uri` 合成 Uri 型选图返回、`debug.wxpiclabel.bgsend` 强制走后台聊天页发一条
- 新增 严格交替：图片入库钩子里同步发对应文件名（`sendLabelBlocking`），形成 图1 → 文件名1 → 图2 → 文件名2；连续超时两次自动退化。**真机两次复测证明相册窗口内拿不到聊天输入框，改为默认关闭（`debug.wxpiclabel.strict=1` 才开）**
- 修复 相册卡「正在发送中」/文字全丢：入库钩子默认只记 `defer label#k`，不阻塞发图线程；回到聊天后由轮询 + onResume 两路触发 `flushLabels` 补发
- 新增 间隔现场可调：`debug.wxpiclabel.settle`（首条延迟）/ `debug.wxpiclabel.gap`（步进），默认 1500 / 1500 ms
- 新增 探针 `debug.wxpiclabel.map`（f9 setter→getter 映射）、`debug.wxpiclabel.recipe`（真实文本消息构造序列）
- 新增 接管发送（相册只当选图器）：拦 `on5.f#a` 不放行，调它的回调让相册收工，再逐张重放整条入口并插文件名，实现 图1→文件名1→图2→文件名2
- 修复 接管后图片不上传：只重放 `db0.u#Vj` 时上传编排不执行，改为整条入口逐张重放
- 修复 接管线程操作 View 抛 `Animators may only be run on Looper threads`：`UiSender.send` 非主线程一律 post 到主线程
- 调整 功能页只留本模块功能：`FEATURE_CATALOG` 19 条证券功能 → 1 条「发送图片文件名」，新增 `ConfigKeys.IMG_LABEL`（并登记进 `FEATURE_KEYS`/`SNAPSHOT_KEYS`）与中英文案，实机功能页只剩这一条
- 重构 门面/结构与 GSAuxiliary 对齐：源码改为 app/core/feature/ui/util 分层，构建改 Kotlin+Compose+Miuix，资源与 Manifest 整份照搬（SettingsActivity + 桌面 alias + DebugConfigProvider），主题 Theme.WxPic；APK 11.8 MB，设置页实机可打开
- 重构 代码精简：删除旧 `Batch`/`QuoteContent`、`g9` 入库钩子与闸门、`xa0.b0#Oj`/`db3.g5`/`db0.u#e` 探针、`b3.a` 与自建 f9 通道、各类探针与自测属性，只保留「`on5.f#a` 接管 + `xm5.y#Fj` 节拍 + `UiSender`」；每一环失败都退回微信原生发送并把文件名交给兜底队列（3900+ 行 → 1527 行）
- 新增 文字引用图片（实验，默认关 `debug.wxpiclabel.quote=1` 才开）：借 `ChatFooter#R(f9,ga5.i)` 挂引用状态，实测引用条能显示但渲染成「引用内容不存在」（svrid 未回填），已回退为普通文字
- 新增 发送节奏可调：`intogap` / `gap` / `preclick` / `postclick` / `confirmgap`，默认全 0（事件驱动 + 点击回调驱动，靠 `sameRetry` 兜住 setText 未生效）
- 新增 按「单张发送成功」事件驱动打文件名：挂 `xm5.y#Fj(f9)`（`album_media_send_succ`），转圈消失才插文字，替代固定延时猜测；等不到该事件连续 2 次自动退回固定延时
- 调整 不显示发送进度提示（`UiSender.showProgress` 保留但接管流程不再调用）
- 修复 Vj/e 钩子挂在壳 loader 上不生效：改用 `application.getClassLoader()`（Tinker `DelegateLastClassLoader`）重装，实测命中
- 调整 批次武装提前到 `db0.u#Vj`（发送准备入口）读 `qa0.k` 的选图列表，不再依赖晚到的选图结果回调
- 新增 `UiSender` 后台聊天页发送：相册/预览页置顶、聊天页在下面时照样能 setText + 点「发送」
- 修正 发送链路认知：`xa0.b0#Oj(f9,String)` 是 HD 缩略图刷新（不是发图），真发送入口是 `s61/b0` / `q61.j1#k`；证据 `.work/evidence/agent-static.md`、`agent-dex-scan.md`

- 定位 微信 8.0.80 消息落库走 WCDB native，Java 层 SQLite 三路 Hook 只抓到 WorkManager 表，改为发送前插引用的设计（见 docs/PITFALLS.md 第 5 条）
- 修复 微信 8.x 下 MsgInfoStorage 类名被混淆导致落库 Hook 全落空：新增按特征（参数类型 MsgInfo + 方法名 insert/insertInner/b）扫描 com.tencent.mm.storage.* 的兜底路径
- 调整 仓库门面 —— README 改为系列仓七段式（居中标题 + 徽章 + 这是什么 / 环境要求 / 安装 / 反馈 / 免责声明 / 开源鸣谢 / 许可证），去掉引言块
- 新增 CONTRIBUTING / SECURITY / LICENSE / NOTICE / .github（Issue 模板 + CI）与 `assets/` logo
- 新增 docs/DISCLAIMER、docs/TROUBLESHOOTING、docs/VERSIONING、docs/PITFALLS，docs 索引按四类重组
- 新增 tools/README.md（脚本总表）

## 0.1.0 — 2026-11-05

- 首个可用版本：微信发图时按选图顺序在每张图后发一条引用文字（图片文件名），引用条可跳回原图。
- 四个 Hook 点：选图结果、图片压缩（绑定原图路径 → imgPath）、消息落库认领、svrId 回填。
- 现代 Xposed API 102（`io.github.libxposed:api`，compileOnly），静态作用域 `com.tencent.mm`。
- 无 UI；Hook 点类名随微信版本漂移时只降级不崩溃。