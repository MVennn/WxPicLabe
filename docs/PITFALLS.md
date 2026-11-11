# 踩坑正本

> **一句话**：每条缺陷的「现象 → 根因 → 怎么避」，修完就补一条，防止同坑二次踩。

## 1. 构建报「gradle 8.11.1 not found」

- **现象**：`build.cmd` 打印 `[ERROR] gradle 8.11.1 not found at: \.gradle\wrapper\...`，路径只剩一个反斜杠开头。
- **根因**：`build.cmd` 用 `%USERPROFILE%` 拼 Gradle 发行版路径；在环境变量被清空（例如从 Node/CI 里 `execFile` 起 cmd）时会展开成空串，路径变形。
- **怎么避**：正常在 cmd 窗口里跑；自动化调用时显式传入 `USERPROFILE` 等 Windows 环境变量，或改用 `gradlew`（它不依赖该变量）。

## 2. 沙箱下 Gradle 缓存写不进去

- **现象**：`Could not create service of type ...`、`Failed to create parent directory ...`，指向 `%TEMP%` 或 `~/.gradle`。
- **根因**：受限环境下这两个目录不可写。
- **怎么避**：`build.cmd` 已把 `GRADLE_USER_HOME` / `TMP` / `TEMP` / `ANDROID_USER_HOME` 重定向到工程内的 `.gradle-home`、`.tmp`、`.android-home`；手工跑 gradle 时也要带上同样三个变量。

## 3. 微信升级后不再补文件名

- **现象**：模块已启用、作用域已勾，日志里没有 `selection via key=...`，图片原样发出。
- **根因**：宿主类名/字段名漂移，Hook 点里有命中失败，模块按约定降级为原样发送。
- **怎么避**：按 `ARCHITECTURE.md`「已知限制」逐项校准候选 key/类/字段；校准后把实际命中的名字回写文档。

## 4. 微信 8.x 里 MsgInfoStorage 按类名找不到

- **现象**：日志打 `MsgInfoStorage not found`，随后 `compress class not found`；选图 Hook 命中，但发出去没有引用条。
- **根因**：微信 8.x 把存储管理类名混淆成 `com.tencent.mm.storage.<1~2 字符>`，只有实体类 `com.tencent.mm.storage.MsgInfo` 还保留原名，按名字找必然落空。
- **怎么避**：走 `findStorageByScan()` 的特征扫描——遍历 `com.tencent.mm.storage.*`，挑「单参数、参数类型是 `MsgInfo`、方法名 `insert`/`insertInner`/`b`」的类。命中会打 `scan hit: <类名>#<方法>`。

## 5. 微信 8.0.80 的消息落库不走 Java 层 SQLite

- **现象**：挂了 `SQLiteDatabase.insert` / `insertWithOnConflict` / `SQLiteStatement.executeInsert` 三路 hook，抓到全是 WorkManager 的 `WorkSpec`/`WorkTag`/`Preference`，**没有一条 message 表写入**；发图后模块零日志。
- **根因**：微信用 WCDB（native C++）写消息库，Java 层 SQLite API 根本不被调用。
- **怎么避**：不要在 SQLite 层找落库时机。可行方向是「发送前插引用」——hook `MsgInfo` 实体的构造或发送入口，在图片消息发出前把引用文字排进去，不依赖 msgId 回填。

## 6. 微信 8.0.80 的类空间要分两步拿

- **现象**：`CompletableFuture`/`Class.forName("com.tencent.mm.storage.MsgInfo")` 一律 `ClassNotFoundException`，但 `strings classes3.dex | grep MsgInfo` 又能看到。
- **根因**：微信用 Tinker 热修复，主 classloader 只挂了壳 dex（`dexElements=1`），真实代码在 tinker patch 目录（191 MB），由 `DelegateLastClassLoader` 加载。壳 dex 里的 `MsgInfo` 只是字符串引用，不是类定义。
- **怎么避**：真实 loader 只能从**运行中的 Activity** 拿——`activity.getClass().getClassLoader()`。

## 7. 微信 8.0.80 没有 `modelmulti` 包，按类名找发送入口必然落空

- **现象**：`WeChatSender.hostCandidates()` 列的 `com.tencent.mm.model.MsgSender` 等四个类全部 `ClassNotFoundException`，`send label[i]=... ok=false`。
- **根因**：8.0.80 的 18 个 dex（182 MB）里 `com/tencent/mm/modelmulti/` 一个类都没有；方法名也全部混淆成单字母，按 `sendText` 搜方法同样 0 命中。
- **怎么避**：不要赌内部 API。改走纯视图层——`ActivityThread.mActivities` 取 Activity → decor view 找 `MMEditText` → setText → 点文字为「发送」的可见 TextView。

## 8. 连发多条只出去最后一条

- **现象**：一次补发 3 个文件名，聊天里只出现最后一个；日志三条都是 `ok=true`。
- **根因**：每条发送是「setText → 350ms 后点发送」，两条间隔只有 50~60ms，后一条 `setText` 把前一条内容覆盖了，第一次点击发出去的是后一条。
- **怎么避**：`UiSender` 内部改成串行队列（一条点完等 600ms 再取下一条），点击前再校验输入框内容仍等于本次要发的文本，不等就跳过。

## 9. 选图结果 Intent 的 key 猜错

- **现象**：模块按 `key_send_raw_image_path` 取路径永远为空，看起来像「选择器没返回」。
- **根因**：该 key 在 8.0.80 的 dex 字符串表里**完全不存在**；真实存在的是 `key_select_image_list`、`key_select_image_uri_list`、`key_send_raw_image`。
- **怎么避**：反编译前先用 `strings`/dex 字符串表核对 key 是否真实存在；取列表时按 String 列表 → Parcelable(Uri) 列表 → String 数组三种形态依次尝试，取不到就打印全部 extra 的 key 与值类型。

## 10. 选图返回的是 content:// Uri，直接 getName() 得到的是媒体库 ID

- **现象**：真机选了一张图，模块发出去的「文件名」是一个纯数字，比如 `27`。
- **根因**：8.0.80 的 `key_select_image_uri_list` 里装的是 `content://media/external/images/media/27`；`Uri.getPath()` 返回 `/external/images/media/27`，取 basename 就成了 `27`。这是 MediaStore 的行 ID，不是文件名。
- **怎么避**：content scheme 的 Uri 必须走 `ContentResolver.query`：先取 `_data`（绝对路径），拿不到再取 `_display_name` 并在前面补一个虚拟目录，保证下游 `File(path).getName()` 仍然成立。实测同一个 id 解析出 `/storage/emulated/0/Pictures/WeiXin/mmexport1793877171364.jpg`。

## 11. 模拟器上 adb 注入触摸会把指针卡死

- **现象**：`input motionevent DOWN` 与 `UP` 分两条命令发之后，之后再 `input tap` 全部无反应（长按也无反应），但 `input keyevent` 正常。
- **根因**：DOWN 与 UP 分两次进程发送，中间被当成持续按压，指针状态没有复位。
- **怎么避**：`adb reboot` 复位；需要「按住」时用单条 `input swipe x y x y <ms>`，不要拆成两条 motionevent。另外 `adb exec-out screencap` 在高负载下会拿到上一帧，验证界面变化要连拍两次取后一张。

## 12. 选 7 张图，结果是「一堆图一堆字」而不是一图一行字

- **现象**：多选图片后，聊天里先出现全部图片，再出现全部文件名。
- **根因**：图片是微信自己的发送流水线一批发出去的（一次选 N 张 = 一次批量任务）；模块原来固定等 3 秒再把 N 条文字连发，所以必然是「图全在前、字全在后」。
- **怎么避**：改成盯 `MMChattingListView.getCurCount()`，条数每涨 1 就补 1 条文字，并用 `pendingOwn` 扣掉自己发出的文字造成的涨幅。微信逐条插入时就能做到图后紧跟名字；一次性插入时仍会退化，日志 `new messages: k at t=...` 可判定。

## 13. 想发「引用图片」的文字却发不出来

- **现象**：只能发纯文本，没有引用条。
- **根因**：微信引用是 type-49 消息，正文里嵌 `<refermsg>` XML（`classes11.dex` 里能搜到 `<refermsg>`），必须带被引消息的 `svrid`。而消息落库是 WCDB native，Java 层没有插入点；`com.tencent.mm.storage.MsgInfo` 在 18 个 dex 里 0 命中，实体类名也被混淆，拿不到 msgId。
- **怎么避**：拿到 msgId 之前不要做引用；要突破就得从 native 侧或聊天列表适配器的 `getItem` 反查实体对象，属下一阶段工作。

## 14. 功能开关装完就是「开」的

- **现象**：全新安装、一个开关都没动，微信发图就已经带上文件名；用户以为模块不受控，或者以为设置页那个开关是摆设。
- **根因**：`ConfigKeys.DEFAULT_IMG_LABEL` 写成 `true`（注释还写着「模块装完就是这个功能本身」）。全仓读取一律是 `getBoolean(key, 默认值)`，没有存量键时直接按 `true` 跑；而 `resetAllFeatures()` 又显式写 `false` —— 于是「默认开、重置关」两套语义并存，重置一次反而像修复了 bug。
- **怎么避**：功能开关（`FEATURE_KEYS`）出厂默认一律 `false`，只有子参数（`FEATURE_PARAM_KEYS`）才允许 `true`。改默认值不需要任何迁移代码 —— 键存在就用键、不存在才回落，所以「从没动过」的用户自动变关，「手动开过」的用户保持开。口径见 `CONFIG-MEMORY.md` §1。

## 15. 备份文件把「已同意免责声明」带到另一台机器

- **现象**：备份 JSON 里出现 `disclaimer_accepted: true`；换机恢复后首次启动不弹免责声明，看起来像「免责声明失效」。
- **根因**：`DISCLAIMER_ACCEPTED` 被登记进 `ConfigKeys.ALL_KEYS` 与 `DEFAULTS`，于是它跟着备份 / 恢复 / `resetToDefaults()` 一起走。但它其实是**本机界面状态**，不是模块配置。
- **怎么避**：`ALL_KEYS` / `DEFAULTS` 都不收它，只走本地文件（`getLocalBoolean` / `setLocalBoolean`）；顺带解决「重置模块把同意态一起清掉、每重置一次就要再点一次同意」的问题。判据：**与目标进程无关、且「清除应用数据就该重置」的状态，一律不进配置表**。

## 16. 一批里有两张等不到确认，之后每一批都不再等
- **现象**：某批发图有两张气泡转圈超过 `confirmtimeout`（12 s），之后**所有**批次都不再等事件，剩余图片一股脑重放出去；表现是「图越多越慢，发完手动发文字还在转圈」，且永不自愈。
- **根因**：`sentMisses` 只在等待**成功**时清零，`startReplay` 从不重置 —— 累计到 2 之后，后续每一批的 `waitImageSent` 直接走退化分支。
- **怎么避**：每批开始把 `sentMisses` 归零（`WxPicLabelModule#startReplay`）；退化模式不再零间隔（`intogap` 默认 800 ms），宁可慢也别把上传堆进微信发送队列；批次收尾若本批有 miss，`drainInFlight` 等上传排空再撒手。

## 17. 成功事件被算进基线，每张图白等到 12 秒
- **现象**：个别批次每张图都卡满 `confirmtimeout`，日志两条 `no send-confirm ... misses=1/2`，图文顺序却是对的。
- **根因**：`waitImageSent` 的计数快照取在 `replayOne()` **之后**；`on5.f#a` 的同步段里 `xm5.y#Fj` 就可能落地，快照取晚了一律等不到「下一次」。
- **怎么避**：快照必须在**重放之前**取；轮询间隔 60 ms → 20 ms，事件早到也能立刻识别。

## 18. 白名单外的会话发图，+N 计数照样被顶
- **现象**：给白名单外的会话发 N 张图，日志先出 N 条 `repeat: label=… count=…`，随后 `fallback: … not in scope, drop N` —— 图是微信原生发的，计数却被白涨一次。
- **根因**：`armFromParams` 先 `textFor()`（bump 计数）再由 `flushFallback` 做白名单判定。
- **怎么避**：`armFromParams` 在算名单前先过 `scopeAllowed(lastTalker)`，名单外直接放弃。

## 19. 子进程各弹一个 Toast，进度永远停在 1/N
- **现象**：`dumpsys notification` 的 Toast Queue 里出现 3 条同名进度 Toast（分别来自 `com.tencent.mm` 的三个进程），屏幕反复显示 1/N。
- **根因**：钩子装在每个进程里，HUD 的 begin/progress 没按进程区分；每个进程一条 Toast 记录，排队依次显示。
- **怎么避**：`onModuleLoaded` 里按 `processName == 包名` 记主进程，HUD、兜底轮询、自测通道只在主进程跑。

## 20. 模块 App 改了配置，微信进程永远读不到新值
- **现象**：「刷新名单」按钮写了令牌，微信进程 3 分钟内毫无反应；只有重启微信后那一次轮询会「发现」令牌变了（`lastScopeRefresh` 初始为空）。
- **根因**：目标进程侧 `getRemotePreferences()` 拿到的是**附着那一刻的快照**，模块 App 后续的写入不会推给已附着的那份 —— 读一万次都是旧值。
- **怎么避**：`cfgPrefs()` 按 TTL（3 s）重新附着拿新快照；重取失败沿用上一份，**绝不能**因为一次异常把功能判成「关」（`feature_img_label` 是 fail-closed）。

## 21. 关掉桌面图标后，每次覆盖安装又冒出来
- **现象**：设置里关掉「桌面图标」并隐藏成功，覆盖安装 / 更新 APK 后桌面又出现图标；设置页里开关还显示「关」。
- **根因**：两层。① 隐藏靠 `PackageManager.setComponentEnabledSetting(alias, DISABLED, DONT_KILL_APP)` 写一条覆盖记录，覆盖安装会清掉它，alias 回落到 manifest 的 `android:enabled="true"`；② 原来只在 `WxPicApp.onCreate` 里按 `ModulePreferences` 同步，更新后用户没打开模块 App 就不执行，而且框架 binder 未连时读到本地缺省 `DEFAULT_DESKTOP_ICON = true`，反而把图标点亮。
- **怎么避**：alias 在 manifest 改 `android:enabled="false"`，打开图标用 `COMPONENT_ENABLED_STATE_ENABLED`（`DEFAULT` 现在指向 false，再用它等于打不开）；开关值写进**独立** SharedPreferences `launcher_icon_state`（缺省 false）——绝不能写 `ConfigKeys.GROUP`，那个文件会被 `resetToDefaults()` 的 `clear()` 抹掉、被 `migrateLocalToRemote()` 遍历搬到远端，写它还会触发 `notifyChanged()` 与 observe 形成回路；静态 `PackageReplacedReceiver`（`exported=false`）接 `ACTION_MY_PACKAGE_REPLACED` 在更新后按镜像重放，`WxPicApp.onCreate` 兜底，两者都不挂在 `ModulePreferences.observe` 上；设置页开关状态也读同一份镜像，否则会出现「开关显示开、图标却隐藏」。LSPosed 设置入口由 `SettingsActivity` 本体的 `MAIN + MODULE_SETTINGS` 保证，禁用 alias 不影响它。
## 22. 只改注释，release APK 的 sha256 也变了

- **现象**：改的是 KDoc / 行注释，assembleRelease 产物体积几乎不动，但 GitHub Release 已发布 apk 的 sha256 对不上，用户侧校验失败。
- **根因**：classes.dex 保留源码行号映射（debug info），注释增删会改变行号表，dex 字节随之变化；「同长度改动未必不变」。
- **怎么避**：注释 / KDoc 级改动也按功能改动对待：跑完 assembleRelease 必须重新 gh release upload --clobber 并回写发布说明里的 sha256，不要把「只改注释」当成免验证改动。
