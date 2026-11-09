# Changelog

## [0.5.2] - 2026-11-09

- 修复 记录页「回传 N 字但 0 个文件名 / 0 条记录」：解析器原来只有一条严格路径，文档形状一变（分片没拼全 / 顶层键不同 / 元素不是对象）就整份读成空表且不留证据。现在 `RecordStore.read` 三层兜底——严格读 → 别名键与数组元素读（`label|name|l`、`count|n|c`、`last|t|at`，元素支持三元/五元数组）→ 直接在原文上按 `{"label":…,"count":…,"last":…}` / `{"s":…,"t":…,"l":…,"n":…,"b":…}` 形状正则回捞（转义自己还原），后两层只要捞到东西就按结果展示
- 新增 诊断行按 `Book` 新字段分支到七态，把「为什么空」直接写在页面上：结构不符时报**顶层键名**与原始条目数（`回传 17171 字，但一条都解析不出来（顶层键 fetchedAt,friends；原始 0 / 0 项）`）、空文档单列一态、靠原文回捞时明确写「已按原文回捞 X 个文件名 / Y 条」
- 修复 刷新中的那 6 秒把诊断行整段藏起来（每次进页都退回「0 个文件名 0 条记录」的空白态，像读不到）—— 只有一次快照都还没有时才显示「正在读取记录…」
- 文档 `docs/IMAGE-RECORD.md` 诊断一节改成七态判定表，排障表补「顶层键不对」「已按原文回捞」两行

## [0.5.1] - 2026-11-09

- 修复 记录再次读不到新数据：模块侧推送**一次失败就永久停在旧快照**（原先只有批次结束 / 兜底 / 三个令牌变化四处触发，失败不重试）。现在 `RecordExporter.push` 返回成功与否，微信进程**启动即补推一次**，失败按 `min(60s, 5s × (1+失败次数))` 退避重试，每次记台账也置脏——用户不用点刷新、也不用再发一张图
- 修复 三路推送（批末 / 令牌 / 重试）可能并发分片 insert，把两份快照的片拼成烂 JSON —— `pushRecords` 加锁串行
- 新增 记录页概览下的**诊断行**：直接说清「回传 N 字 · M 个文件名 / K 条记录」/「微信进程还没回传过记录」/「回传内容解析失败：<异常>」/「回传的是旧版文档」；解析异常不再被静默吞成空表（`RecordStore.Book` 增加 `chars` / `error` / `hasEvents`）
- 文档 `docs/IMAGE-RECORD.md` 补「推送是自愈的」与诊断行四态，排障表新增三行

## [0.5.0] - 2026-11-09

- 新增 发送记录按时间段分组：同一批发出的图折成一张卡，**组头就是那个时间段的时间戳**，点一下展开/收起那一堆图；组头时间按同日/跨日两种写法拼（`11-09 14:51–14:51:45` / `11-09 14:51 – 11-09 16:02`），旧版来源只体现为摘要后缀「N 张 · 旧版」
- 新增 记录行原地编辑（点行 → 锚定小菜单）：**改文件名**、**改「第几次」**（1..999 校验，预填当前次数）、**删除这条**（二次确认，只删记录、不动微信里已经发出去的图）；动作写进模块侧动作串（`d|seq` / `l|seq|名字` / `n|seq|次数`），跨进程回放，随时可改可删
- 新增 记录页搜索与筛选：一个框覆盖文件名 / 「第几次」/ 时间片段（`14:51`、`11-09 14:51`），纯数字只按文件名与次数理解；下面一行时间范围四档（全部 / 今天 / 近 7 天 / 近 30 天）；**筛选态全部命中组自动展开**，概览改报「筛出 N 条 · 按时间段全展开」
- 修复 筛选态只展开最新一组「搜不到记录」：命中但非最新的组只剩组头、行全折着（组头写着 3 张却一行看不见）—— 筛选态改为全展开，并记住用户手动收起的组
- 修复 旧版记录（微信进程里跑旧模块、回传文档没有 `events`）整段历史挤成一组，看着像「根本没有分组」——迁移条目批次是占位 `m`，改为按 `at / 600000` 折成 10 分钟一格兜底
- 修复 记录页「正在读取记录…」死等 18 秒像卡住 —— 轮询上限 45 → 15 次（6 秒）自动收
- 调整 弹层按 `docs/UI.md` §4 的选型表收敛：锚定小菜单用 `ChoiceDialogSpec`（必须给 `anchor`）、二次确认用 `ConfirmDialogSpec`（`anchor` 恒空、居中）、贴底输入用 `TextInputDialogSpec`；改名页的保存/复制/粘贴、名单页的刷新去掉「→」（都是点了就地生效，不换界面），名单页展开卡改成「˅」
- 优化 底栏拖动的每个拖动事件都新起一个协程做动画 —— 改为状态直接累加、松手再回弹，左右切页不再被协程调度拖慢

## [0.4.0] - 2026-11-06

- 调整 功能名与副标题按 `docs/NAMING.md` 收敛：功能卡片由「发送图片文件名」改为「**增加 文件名 开关**」（§2 判定为新增类 → §3 三段式），副标题改成只说位置「相册底栏原图右边」（规则四）；发送设置页六个开关 / 入口的复述型副标题全部删掉，只留「已选 N 个」状态与「清空」的范围说明；`strings.xml` 按引用集裁掉 14 条死文案
- 调整 `docs/NAMING.md` §5 对照表补齐二级页清单（开关 / 入口 / 破坏性操作三类各留什么副标题）

- 定位 由「发一图一文」扩为「微信 · 发图功能扩展模块」：门面仍是「logo + 标题 + 一行粗体定位 + 标语 + 徽章」五元素（标语位保持口号，不塞功能清单），免责声明与关于页统一按「接管相册批量发送、按选图顺序逐张发」表述，删掉早已回滚的「引用条」说法
- 补齐 开源鸣谢与第三方组件声明：README 鸣谢表与随 APK 分发的 NOTICE 都补齐到 Miuix / AndroidLiquidGlass(backdrop) / Jetpack Compose·AndroidX / Kotlin / Material Icons / HyperIsland（含 MIT 全文与派生说明），与姊妹项目 GSAuxiliary 同口径
- 修正 README 的两处过期描述：minSdk 26 → 27（安卓 8.1+）、自测日志关键字 `selection via key=` → `takeover: intercepted`

- 新增 发送进度提示：发图批次期间用系统 Toast 显示「正在发送 3/12」（当前/总数），复用同一个 Toast 实例原地更新文本，不会排一长串；只在主进程跑（`:push`/`:tools` 子进程各弹一个会把 Toast 队列排死，进度永远停在 1/N）
- 新增 发送期间屏幕常亮：给前台 Activity 加 `FLAG_KEEP_SCREEN_ON`，整批发完自动解除，不用 WakeLock、不需要宿主权限
- 新增 发送提示两个开关（`img_label_progress_toast` / `img_label_keep_screen_on`，默认开）进「发送设置」二级页与配置快照
- 修复 一批里有两张等不到发送确认后，`sentMisses` 永不清零 → 之后每一批都不再等事件、图片一股脑重放，上传全压在微信发送队列里（表现：图越多越慢、发完手动发文字还在转圈）—— 每批开始归零
- 修复 发送成功事件的计数基线取在重放之后，事件在 `on5.f#a` 同步段落地时一律白等满 12 秒 —— 快照改为重放前取，轮询 60ms → 20ms
- 修复 白名单外的会话发图也会白涨 `RepeatCounter` 的 +N 计数（算完又被兜底队列丢弃）—— `armFromParams` 先过白名单再算名单
- 优化 `UiSender` 输入框 / 发送键按 Activity 实例缓存：命中不再走 3~4 遍全树递归（主线程 O(视图数)，聊天越长越卡）
- 优化 点击时机自适应：内容落进输入框就点（实测 120 ms → 9~13 ms），点完等输入框被清空就取下一条（150 ms → ~0），实测一组图文从 ~2.7 s 降到 ~1.17 s
- 优化 批次收尾：本批有未确认的图先 `drainInFlight` 等上传排空再撒手，避免用户紧接着发的文字排在图片上传后面转圈
- 修复 `disclaimer_accepted` 仍在 `ALL_KEYS` 里（PITFALLS 第 15 条说已移除，实际漏了一处）→ 移出，备份/重置不再碰同意态
- 恢复 自测通道 `debug.wxpiclabel.text`（精简轮误删，排错手册第 7 节一直写着）并新增 `debug.wxpiclabel.hud`
- 修复 模块 App 改的配置微信进程永远读不到：目标进程侧 remote preferences 是附着那一刻的快照，`cfgPrefs()` 改为每 3 s 重附着 —— 此前「刷新名单」令牌写进去微信进程根本看不见（只有重启微信才会被发现），所有配置改动都要重启微信才生效
- 新增 好友选择页进页自动刷新名单（不再依赖手动点）；新增独立「刷新名单」行（带「正在刷新名单…」反馈与「名单更新于 11-06 13:21」时间戳）
- 新增 重命名规则（发送设置 → 重命名规则）：一行一条 `原名 -> 新名`（也接受 `=>` / `=` / 制表符，`#` 注释），按文件名精确匹配后替换发出去的文字，计数键跟着新名字走；支持从文件导入、导出到文件、复制到剪贴板、从剪贴板粘贴（整表一个字符串，一行一条就是文件全文）
- 新增 发送记录（发送设置 → 发送记录）：列出每个名字发过几次、最后一次发送时间，可清空；计数表在微信进程的 prefs 里（跨 uid 读不到），由新增的 `/records` 回传通道经 FriendBridgeProvider 分片推送，进页自动拉一次、每批发完再推一份
- 修复 三级页可预测手势返回动画错乱：原来三级页（好友选择）直接顶掉二级页内容，手势把整层二级页往右推、露出的是首页，提交后又把二级页推回来 —— 改为三级页单独一层、两条独立进度，手势只推动最上面那一层（与 GSAuxiliary 同一套做法）
- 修复 「已选 X 个」点击无任何反应：原来这一行被当成刷新入口，现在点击展开**已选名单**（显示勾了谁，再点一行即取消勾选），刷新职责拆给独立的「刷新名单」行

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

- 首个可用版本：微信发图时按选图顺序在每张图后发一条文字消息（图片文件名）；0.1.0 的引用条 / 跳回原图为当时设计，现已改为纯文字（见 [未发布] 修复条目）。
- 四个 Hook 点（0.1.0 初版，后续补偿挂三路 + 自测通道）：选图结果、图片压缩（绑定原图路径 → imgPath）、消息落库认领、svrId 回填。
- 现代 Xposed API 102（`io.github.libxposed:api`，compileOnly），静态作用域 `com.tencent.mm`。
- 无 UI；Hook 点类名随微信版本漂移时只降级不崩溃。