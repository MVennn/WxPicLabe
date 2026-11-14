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
## 23. 发送记录里冒出一行「第 0 次」

- **现象**：开了「追加次数设置」后，时段外发出的那几张图在发送记录里显示成 `第 0 次`，或者整行消失。
- **根因**：`RepeatCounter.record` 原来在 `n ≤ 0` 时会**回查计数表**来补次数；「追加次数设置」必须用 `n = 0` 表达「发出时不在时段内、不占号」，沿用回查就会把历史计数顶成这次的实际编号。
- **怎么避**：`record(label, n, batch, atMs)` 现在把 `n` 当明确值落盘（`Math.max(0, n)`），`0` 就是「未累计」。记录页渲染按 `ev.n <= 0` 单独出「未累计 · 时间」文案 —— 不能显示成第 0 次，也不能隐藏（隐藏等于丢发送台账）；`rebuildCounters` 天然跳过 `n <= 0`，删记录 / 改次数不会凭空造出计数。
- **顺带**：`RepeatWindow.plus` 早期用 `List.contains(span)` 去重，`Span` 没重写 `equals` 就恒为 false，同一段能加两次；改成按起止比较。给自定义类做去重前先确认它有没有 `equals`。

## 24. 新加的三级页返回后掉回首页

- **现象**：从发送设置进新加的三级页，点返回，回到的不是发送设置而是首页，或者配置页重进后落在别的子页。
- **根因**：`ui/Routing.kt` 里加一个 `Route` 要同步四处，但只有 `RouteSaver` 的 `when` 是穷尽的才编译报错；`editParent` 和三级页 `when` 都带 `else`，漏了不报错，只是静默走默认分支。`RouteSaver.restore` 漏一支，进程被回收后重建就会落回 `Home`。
- **怎么避**：新三级页按五处清单改：`Route` 定义、`editParent` 列表、`RouteSaver.save`、`RouteSaver.restore`、三级页 `when` 的 `SubScreen` 分支（外加二级页传入的 `onOpen*` 回调）。改完全局搜一遍新路由名，确认出现次数与预期一致。

## 25. 新弹层缩在左上角，滚轮一划就跳回原刻度

- **现象**：新做的贴底弹层（时段滚轮）弹出来缩在屏幕左上角、只有内容那么宽；或者滚轮能滑，一松手又跳回打开时的刻度。
- **根因**：①`ui/AppDialogHost.kt` 的 `sheet` 判定是**手写的类型白名单**（`spec is ConfirmDialogSpec || spec is TextInputDialogSpec || ...`），新 spec 不登记就走锚定分支，看起来像「锚点算错了」。②滚轮的当前值如果存在 `TimeWheel` 内部，每次重组都会用外部传入的初始值重建，滑不到别的位置。
- **怎么避**：新 spec 一定同步改 `AppDialogHost.kt` 的 `sheet` 判定（贴底类才写进去，锚定菜单不是）。滚轮的 `value` 由弹层那一层持有（`remember(spec) { mutableIntStateOf(...) }`），`TimeWheel` 只做无状态渲染 + 回调；`remember` 的 key 必须带 `spec`，否则同一个 controller 连开两次会沿用上次的刻度。`NumberPicker` 的 `wrapAround = true` 才和 iOS 闹钟一致（23 点再往下是 0 点）。

## 26. 算出来的 sha256 和 GitHub 上的对不上

- **现象**：`build.cmd release` 已打印 `BUILD SUCCESSFUL`，立刻 `Get-FileHash` 算 sha256 记进 `PROGRESS.md` 与 Release 正文，上传后 GitHub 资产 digest 与记录不一致，**体积却一模一样**（1,419,160 B）。
- **根因**：Gradle 打印 `BUILD SUCCESSFUL` 之后还有 daemon 收尾与 APK 的最终落盘（AGP 先写未对齐未签名的包到同一路径，再对齐、再签名）；这时读文件拿到的是中间态。体积相同、字节不同，肉眼完全看不出来。
- **怎么避**：算 sha256 前先确认构建任务**已经退出**（后台 job 状态 `completed`），并且 `LastWriteTime` 已经稳定；上传后用 `gh release view <tag> --json assets` 的 `digest` 与本地 `Get-FileHash` 对齐，**以 GitHub 侧为准**回写记录。本仓 0.8.0 就是首轮记录成 `bc19c2…`、实际上传 `fe02a4…`，事后按 digest 改正的。

## 27. build.cmd 把调用方那条失效的 SDK 路径当成真 SDK

- **现象**：`build.cmd` 打印 `[build] SDK=C:\Users\<user>\AppData\Local\Android\Sdk`，而该目录 `Test-Path` 是 False；本机因 `local.properties` 的 `sdk.dir` 兜底还能编，换台机器或删掉 `local.properties` 就报找不到 SDK。
- **根因**：脚本只判「`ANDROID_SDK_ROOT` 有没有定义」，没判目录是否存在（文件头注释写了 must exist，代码没落实）；环境变量指向安装器卸载后留下的空路径时，错误路径会被原样传给 Gradle。
- **怎么避**：脚本在采用前先校验存在性，不存在就 echo 一行说明并清空变量，让它回落到 `.work\android-sdk` 或 `local.properties`。现在日志会打 `ANDROID_SDK_ROOT=... does not exist, ignored.` 与 `SDK=sdk.dir from local.properties`。

## 28. CI 的 build job 死在 setup-android

- **现象**：CI 的 build job 31 秒就失败，挂在步骤 `Run android-actions/setup-android@v3`，其下「安装 SDK 组件」「构建」「上传 APK」全部 skipped；日志末尾 `Warning: Failed to find package 'tools'` → `Error: The process '/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager' failed with exit code 1`。
- **根因**：该 action 默认安装 `tools platform-tools`，而 `tools` 包已从 SDK 仓库下架。另有第二个坑：平台包真名带小版本号 —— 仓库里是 `platforms;android-37.0`，写成 `platforms;android-37` 同样 `Failed to find package`（`sdkmanager --list --channel=3` 可直接核对）。
- **怎么避**：不用该 action，直接调 runner 预置的 `cmdline-tools/*/bin/sdkmanager` 装 `platforms;android-37.0` 与 `build-tools;35.0.0`、`36.0.0`；同时把 `ANDROID_SDK_ROOT` / `ANDROID_HOME` 显式指到 `/usr/local/lib/android/sdk`（CI 上没有 `local.properties`）。`.github/workflows/ci.yml` 现在就是这套，见 `docs/BUILD.md`。

## 29. 发完图片上滑 / 使用微信掉帧，功能关着也卡

- **现象**：微信里发完图片后上滑消息列表、点开相册或切换会话时间歇掉帧；把模块「增加 文件名」总开关保持出厂关闭也一样。发图那一下「发送」转圈变久，多图连发更明显。`adb shell dumpsys gfxinfo com.tencent.mm framestats` 看到 Choreographer 的 doFrame 被推迟，StrictMode `detectDiskWrites().detectDiskReads()` 在微信主线程报 SharedPreferences `.commit()` 与 `ContentResolver.insert/query`。
- **根因**：`startFallbackPoller()` 在 `onPackageLoaded` 无条件 `main.postDelayed(poll, 1200L)` 起一个主线程 700ms 自续轮询，唯一闸门 `if (!mainProcess)` 不读 `featureEnabled()`；每轮 6 个 `check*`/`retry*` + `checkSelfTest()` 两次 `SystemProperties` 反射，`cfgPrefs()` 3s TTL 一到就 `getRemotePreferences` 跨进程重附着。有待补发队列时还叠 `UiSender.hasInput()` 全树递归与 `RecordExporter` 分片 `insert()` 同步 `.commit()` 写盘。`hookImageSent` 的 `isImage` 每条消息 `getDeclaredMethods()` 全扫；`AlbumMemoryToggle` 的 `OnGlobalLayoutListener` 只 add 不 remove，每次全局布局拼 kids 字符串并 `Log.i`；`Reflect.findMethod/findField` 每次沿继承链全扫。这些都不是图片解码，解码在宿主侧，插件只是把主线程时间片吃掉了。
- **怎么避**：轮询主体放 `HandlerThread`，必须读 View 的 `flushFallback()` 用 `main.post` 回主线程并用 `AtomicBoolean flushPending` CAS + `CountDownLatch.await(1500ms)` 有界合并；`pushRecords` 统一 hop 到 worker 并保留 `synchronized doPushRecords`；`SystemProperties.get` / 消息 `getType` / `Reflect.findMethod/findField` 按 Class 缓存 Method（查无也记哨兵）；本地台账 `.commit()` 改 `.apply()`，跨进程 Provider 末片拼 JSON 的 `.commit()` 不能换（Binder 同步语义）；相册注入只挂一个常驻 `OnGlobalLayoutListener`，默认零字符串分配、零反射、零 Log.i，保证勾「原图」后底栏变宽仍能重算 dx。别拿「功能开关关了就不会卡」当判据 —— 轮询不读开关；验收用 `adb shell dumpsys gfxinfo com.tencent.mm framestats` 与 `StrictMode.setThreadPolicy(new ThreadPolicy.Builder().detectDiskWrites().detectDiskReads().penaltyLog().build())`。

## 30. 用户填一条通配规则，微信就卡住不动

- **现象**：开启文字过滤、规则里填了带多个 `*` 的写法（`*a*a*a*a*b` 这种，或从别处粘来的长词），发图时微信界面卡死数秒到数十秒；`logcat` 里没有任何异常，最后进程被系统 ANR 掉。换成别的文本又不复现。
- **根因**：`TextFilter` 早期把通配编译成正则（`*` → `.*`、`?` → `.`）再 `String.matches()`。`matches()` 要求整串命中，正则引擎在最坏情况下按指数级回溯 —— 规则是用户手填的、长度没有上限，一个病态模式就能吃掉微信主线程的时间片。这类开销只对特定文本触发，所以「平常都好好的」。
- **怎么避**：通配不许交给正则。现在 `Rule` 直接持有 glob 串，`globMatches` 用双指针 + 回溯点（`star` / `mark`）线性推进，最坏 O(长度 × 星号数)；大小写折叠在 `find()` 里对每段文本只做一次 `toLowerCase(Locale.ROOT)` 后复用（`folded` / `foldedDone` 两个数组）。回归钉在 `TextFilterTest.wildcardKeepsHandOnPathologicalPattern`：`"*a"×12 + "*b"` 对 60 个 `a` 必须不命中且耗时 < 2s。凡「把用户输入变成可执行模式」的地方都按这条办（正则 / 通配 / SQL 前缀都算），并在单测里给一个病态输入，别只测正常形状。

## 31. 钩子先标记后挂载，一次失败就永久不再尝试

- **现象**：微信升级或热重启后，相册底栏的「原图」开关再也不出现、发图也不再补文件名；重启模块、重新勾作用域都没用，只有重装模块或重启手机偶尔恢复。`logcat` 里只有一个早年的 `hooked ...` 记录，之后没有任何失败日志。
- **根因**：挂载点用 `hooked` 集合去重，但 `markHooked(...)` 写在 `hook()` 之前（`hookAlbumToggle` 里甚至在 `findClass` 之前）。类找不到、方法签名变了、反射抛异常时标记已经落下，之后每次进 `installAll` 都被「已挂载」挡住 —— 一次瞬时失败变成整个过程生命周期的永久失效。另外 `on5.f#a` 只按名字 + 参数类型定位、不校验返回类型，而拦截成功路径 `return null`：某版本若不是 `void`，宿主拆箱就是 NPE 崩微信。
- **怎么避**：① 标记只在 `hook()` 成功返回之后落，键要够细（类名 + 参数个数 + 成员名，不能只用参数个数或只用类名），并在标记成功后打一行 `hooked ...`；② 带返回值的挂载点先校验 `m.getReturnType() != void.class`，不匹配就跳过并打日志，别硬拦；③ 拦截回调体一律包 `try/catch(Throwable)` —— `PROTECTIVE` 模式下宿主吞掉异常、我们这边又没有日志，出事就完全查不到线索。

## 32. 发文字的兜底队列会「串批」，还会吞掉下一批

- **现象**：连发两批图，第二批的 `+N` 文字排进了第一批的记录组；或者更糟 —— 上一批收工时把还在排队的那批文字一起清了，用户一直没等到文件名，`logcat` 里也没有 drop 记录。
- **根因**：兜底队列只有一组全局状态 `pendingDrafts` / `pendingIndex` / `pendingAt`，三个来源（`armFromParams`、`armDeferred`、接管批末）各自整表替换：队列已经补发到第 i 格时被换成新批，`pendingIndex` 仍是 i，新批的第 0..i-1 格就被当成旧批的发过而跳过；接管批末又无条件 `pendingDrafts = null`，把接管期间 `armDeferred` 排进来的**下一批**一并清掉。
- **怎么避**：① 消费前先校验手里的引用还是当前表（`if (pendingDrafts != drafts) return;`），每一处清零都写成「身份相同才清」；② 新批到来时**追加到队尾**（`merged = queued + drafts`）而不是替换，且不要重置 `pendingIndex`；③ 批末只清自己那份 —— 本批的文字在 `startReplay` 开头就 `preDrafts = pendingDrafts; pendingDrafts = null` 取走了，收尾时再清一次只会误伤别人。

## 33. 记录页换成 LazyColumn 后一进页面就崩

- **现象**：发送记录页改 `LazyColumn` 之后，只要台账里有旧版本迁移过来的条目，进页面直接抛 `IllegalArgumentException: Key "e:0" was already used`，整页白屏。
- **根因**：`items(list, key = { it.key })` 要求 key **全局唯一**，而迁移条目由 `FriendBridgeProvider` 的聚合表兜底造出来时 `seq` 恒为 `0`（`Event(0L, …)`）。原来用 `key(ev.seq)` 组合函数包一层，撞 key 只是复用度差；换成 Lazy 的 item key，撞了就是硬错误。
- **怎么避**：item key 不要拿单个可能重复的字段，用「作用域键 + 组内下标 + 原字段」（`"e:" + g.key + ":" + i + ":" + ev.seq`）。改 Lazy 化之前先确认候选 key 在**最坏数据**下唯一（迁移 / 导入 / 手工改过的台账都要算），并把这条写进该行数据类的 KDoc。
