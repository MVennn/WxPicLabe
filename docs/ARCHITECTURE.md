# 架构

> **一句话**：模块接管微信相册的批量发送入口，改成「一张图 → 一条文件名」逐张重放，并保留一条「图先字后」的兜底队列，图一定发得出去。

## 目录结构（五层，与 GSAuxiliary 同一套约定）

```
app/src/main/java/io/github/MVennn/WxPicLabel/
├── WxPicLabelModule.java    # Xposed 入口：唯一实现 XposedModule 的类（java_init.list 指向它）
├── feature/imglabel/        # 业务实现：跑在**微信进程**里
│   ├── UiSender.java        #   视图层发送：MMEditText → setText → 点「发送」，内部串行队列
│   └── RepeatCounter.java   #   同名图片计数（落在微信自己的 SharedPreferences）
├── core/                    # 两进程共用、且**不依赖宿主**的基础设施
│   ├── ConfigKeys.kt        #   配置键与默认值的唯一来源
│   ├── BridgeProtocol.kt    #   跨进程回传协议的 authority / path / 载荷版本（唯一来源）
│   ├── GLog.kt / LogStore.kt / LogExporter.kt   # 日志等级、落盘、导出
├── app/                     # 模块**自身进程**（设置页那个进程）
│   ├── WxPicApp.kt          #   Application：初始化配置、日志、框架服务
│   ├── ModulePreferences.kt #   配置读写（remote preferences + 本地兜底）
│   ├── FrameworkBridge.kt   #   与 LSPosed 框架服务（热重载 / 远程配置）的通道
│   ├── DebugConfigProvider.kt # adb 调试通道（content query/update）
│   ├── LauncherIcon.kt / LocaleHelper.kt / ConfigBackup.kt
├── ui/                      # Compose 设置界面（Miuix），只跑在模块自身进程
└── util/                    # 与业务无关的工具
    └── Reflect.java         #   反射工具（现代 API 不再提供 XposedHelpers）
```

### 分层判据（照搬 GSAuxiliary 的那条红线）

**判据不是「放在哪个目录」，而是「这个类会不会被模块自身进程加载」。**

- `compileOnly("io.github.libxposed:api")` 只存在于编译期；模块自身进程的 APK 里**没有**这些类。
  所以 `app/` 与 `ui/` 里的任何类都**不得**直接或间接引用 `io.github.libxposed.api.*`。
- 本仓现在没有 `HookBag` / `FeatureManager` 这一层：只有一个功能，钩子句柄直接由
  `WxPicLabelModule` 持有，`ui/` 也不会碰到它们。将来功能变多、需要引入功能注册表时，
  注册表必须留在目标进程侧，`ui/` 只能读 `ui/FeatureCatalog.kt` 那种纯展示元数据。
- `core/` 只放**两个进程都能加载**的东西：不能 import `io.github.libxposed.api`，也不能 import 宿主的混淆类。

## 进程与入口

- 入口类：`io.github.MVennn.WxPicLabel.WxPicLabelModule`（`META-INF/xposed/java_init.list` 声明）。
- 作用域：`com.tencent.mm`（`scope.list`，`staticScope=true`）。
- 框架版本：`minApiVersion=101`、`targetApiVersion=102`。
- 模块自身进程：`.app.WxPicApp` + `.ui.SettingsActivity`（manifest 里声明，LSPosed 从自己的进程拉起）。

## 发送路径（微信 8.0.80 实测）

```
相册点「发送」
   └─ on5.f#a(qa0.k params, …, Runnable 回调)     ← 拦这里，不放行微信的批量发送
        ├─ 调它自己的 Runnable，让相册界面收工（聊天页回到前台）
        └─ 逐张重放：params 的 List 字段只留第 k 条 → 调 on5.f#a
             → 等 xm5.y#Fj（气泡转圈消失）→ UiSender 发文件名 → 下一张
```

- **接管入口**：`on5.f#a`，参数里同时有 `qa0.k` 与 `Runnable` 的那个重载。
- **重放服务**：`db0.u#Vj` 的实例由微信自己的服务定位器 `fi5.n0#c(Class)` 取出。
- **发送成功信号**：`xm5.y#Fj(f9)`，按 `getType()==3` 判定图片。连续两张等不到就退回固定间隔。
- **重命名**：`feature/imglabel/RenameRules.java`，一行一条 `原名 -> 新名`（也接受 `=>` / `=` / 制表符），
  按**文件名（去扩展名）**精确匹配，命中就用右边那段文字发出去；解析在微信进程侧、每批一次。
- **现场提示**：`feature/imglabel/UiHud.java`，主线程复用同一个系统 Toast 显示 `正在发送 3/12`，
  并给前台 Activity 加 `FLAG_KEEP_SCREEN_ON`（整批发完自动解除）；两个开关
  `img_label_progress_toast` / `img_label_keep_screen_on`，只在主进程跑（见 `docs/PITFALLS.md` 第 19 条）。
- **文字通道**：`feature/imglabel/UiSender.java`，从 `ActivityThread.mActivities` 取当前 Activity，
  在 decor view 里找可见 `EditText`（`MMEditText`）→ `setText` → 点文字为「发送」的可见 `TextView`。
  内部串行队列：一条点完再取下一条，否则后一条 `setText` 会覆盖前一条。
  输入框 / 发送键按 Activity 实例缓存，命中就不再走全树递归（主线程 O(视图数)）；
  点击时机自适应——内容一落进输入框就点，不再死等 `preclick`，点完等输入框被清空就取下一条。

## 兜底队列

任何一环不可用（类名漂移、重放抛异常、开关关闭、接管超时）都**不重试接管**，而是：

1. 把这一批「选图条目 → 文件名」交给兜底队列（`armFallback`）；
2. 把微信原来那次调用补回去（`replayOriginal`），保证图照样发得出去；
3. 轮询到聊天页可用时按固定间隔补发文件名（`flushFallback`，120 秒过期）。

最差退化成「图先字后」，但**不丢图、不重复发**。

## 配置与计数

- 配置读的是 libxposed 的 remote preferences（目标进程侧**只读**），框架不可用时一律走 `ConfigKeys` 的默认值。
  目标进程拿到的是**附着那一刻的快照**，`cfgPrefs()` 每 3 s 重附着一次才能看到模块 App 的新写入
  （否则「刷新名单」这类单向令牌永远不生效，见 `docs/PITFALLS.md` 第 20 条）；重取失败沿用上一份。
- 同名图片计数存在**微信进程自己的** SharedPreferences（`wxpiclabel_repeat.xml`）：
  remote preferences 写不进去、模块 App 的目录跨 uid 不可写，这是唯一能落盘的地方。
- 那份 prefs 里同时存两套数据：`n:<名字> = 次数|最后时间`（聚合表，只决定 `+N` 文案）与
  `e:<seq> = 时间|次数|批次|名字`（逐次台账，记录页的一切都来自它），元键 `__seq` / `__edits` /
  `__migrated` / `__reset`。两者的写入口都在 `RepeatCounter`，细节见 `docs/IMAGE-RECORD.md`。
- 「清空发送记录」和「编辑记录」都是**单向令牌**：设置页写毫秒时间戳，微信进程轮询到变化才动手——
  清空整体重来，编辑把动作串重放一遍（幂等），改完按台账剩余最大次数反推聚合表。
- **发送记录回传**走令牌 `img_label_record_dump`：微信进程把「聚合表 + 台账 + 已应用编辑」拼成一份
  JSON 文档（顶层带 `"v"` 载荷版本与 `"at"` 生成时间），按**字符**切成 ≤3000 字的片经 `/records` 送过去
  （Provider 只按序拼接、**不加外壳**），模块 App 的 `RecordStore` 落盘、「发送记录」页展示。每批发完也会推一份。
  `/friends` 走的是 200 条一片的另一套协议，外壳（`v` / `fetchedAt` / `friends`）由 Provider 在末片自己拼。
- **协议常量只有一个来源**：`core/BridgeProtocol.kt` 定义 authority、三个 path 与 `SCHEMA`；
  读侧看到 `v > SCHEMA` 时按 lenient 解析并把 `path` 标成 `unsupported`（**能读多少读多少，不整份丢弃**），
  键名不随版本改名 —— 加版本只加在载荷里，跨模块靠 TAG 区分。
- **`/status` 是只读探针**：`schema` / `module`（版本名）/ `features` / `last_records_at` / `last_friends_at` /
  `records_chars` / `friends_count`。老版本 Provider 对任意 path 都回 `friends_json` 那三列，
  新调用方读到 `friends_json` 即判「没有 /status 能力」，不需要额外握手。复用同一套 UID 白名单（root / shell / 微信 / 本模块）。
- **两条回传的重试口径一致**：记录与好友名单都按 `min(60s, 5s × (1+失败次数))` 退避，失败不落盘半份快照
  （分片 `insert` 返回 null 就整份放弃），模块 App 侧不需要点「刷新」。

## 降级策略

- 找不到 Activity / EditText：本轮不发，日志留 `ui-send: act=... edit=null`。
- 找不到接管入口：只走兜底队列，日志留 `takeover: on5.f not found, fallback only`。
- 任何 Hook 安装失败都只影响那一个能力，不阻断其它钩子。

## 自测通道（排障用）

设置系统属性后进入聊天界面即可触发，不需要真的去相册选图：

```sh
# 直接发一条文字（走真实 UiSender 链路：找输入框 → setText → 点发送）
adb shell setprop debug.wxpiclabel.text hello

# 跑一遍进度 Toast + 屏幕常亮（N = 步数，每步 400ms）
adb shell setprop debug.wxpiclabel.hud 5

# 关掉接管，强制走兜底链路
adb shell setprop debug.wxpiclabel.takeover 0
```

属性同一取值只触发一次，换值再触发；`text` / `hud` 只在主进程生效。

## 时序参数（排障用）

| 属性 | 默认 | 含义 |
| --- | --- | --- |
| `debug.wxpiclabel.preclick` | 120 ms | 点击「发送」前最多等多久（自适应，内容落进输入框就点） |
| `debug.wxpiclabel.postclick` | 150 ms | 点击后最多等多久取下一条（输入框被清空就提前走） |
| `debug.wxpiclabel.confirmtimeout` | 12000 ms | 等单张发送成功事件的上限 |
| `debug.wxpiclabel.intogap` | 800 ms | 连续两次等不到事件后的固定间隔（退化模式，不许为 0） |
| `debug.wxpiclabel.gap` / `confirmgap` | 0 ms | 重放间隔 / 确认后的间隔 |
| `debug.wxpiclabel.drainmax` | 6000 ms | 批次收尾等在途上传排空的上限 |

## 已知限制

- 视图层发送要求聊天界面在前台；用户在这几秒里切走会本次不发。
- **引用条做不了**：引用消息要带被引消息的 `svrid`，而消息落库走 WCDB native、Java 层没有插入点，拿不到 msgId。
- 类名与字段全是混淆名，微信升级后可能需要重新校准 `on5.f#a` / `db0.u#Vj` / `xm5.y#Fj` 三处。
