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
