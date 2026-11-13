# 发送设置（重复图片追加次数）

> **一句话**：同名图片重复发送时在文件名后追加 +2/+3 的设计与改动清单，动手前先读这篇。
>
> **状态（2026-11-13 核对）**：本文方案已落地 —— 发送设置与配置键见 `CHANGELOG.md` 0.5.x，时段追加见 0.8.0，总开关自 0.8.2 起由模块 `featureEnabled()` 真读。下文「现状」是立项时点的踩点记录，凡「总开关是装饰性的」「尚未落地」一类表述均已过时，现行行为以源码与 `CHANGELOG.md` 为准。

## 1. 结论

- **要做**，范围三块：App 端「发送设置」二级页、两个配置键、模块侧计数。
- **认图靠名字，不看内容**：键就是实际发出去的那段文字（文件名去扩展名）。今天截图叫 `征和工业 003033`、明天重新截一张同名图，内容 SHA 变了但名字没变 —— 照样接上 `+2`。算内容哈希在这里是错的。
- 计数落在**微信进程自己的 SharedPreferences**（`wxpiclabel_repeat`）：remote preferences 在目标进程侧**只读**，模块 App 目录**跨 uid 不可写**，这两条路都不通。
- 因为只认名字，**不需要读文件、不需要哈希** —— 计数就是一次内存字典自增，零 IO 成本。
- 总开关 `feature_img_label` 立项时是装饰性的（模块从不读它）——本轮已接上（现行 `WxPicLabelModule.featureEnabled()` 在多道闸都读它），否则「发送设置」挂在它下面却不被它管，语义不成立。
- **不动 `ConfigSnapshot` / `ConfigSource`**，模块直接读 remote preferences 的三个键（理由见 §4.2）。
- 本文件是方案；代码按 §5 清单分三批落地。

## 2. 现状（踩点证据）

| 事实 | 位置 |
| --- | --- |
| 发出去的文字 = `labelOf(path)`，即**文件名去掉扩展名** | `WxPicLabelModule#labelOfEntry` / `#labelOf` |
| 接管入口 `on5.f#a`，逐张 `replayOne()` → `waitImageSent()` → `sendLabel()` | `WxPicLabelModule#startReplay` |
| 兜底队列（`db0.u#Vj` 的 `armFromParams`）同样走 `labelOfEntry` | `WxPicLabelModule#armFromParams` |
| `labelOfEntry` 取不到路径时返回 `"unknown"` | `WxPicLabelModule#labelOfEntry` 末行 |
| 模块**不读任何配置**，只认 `debug.wxpiclabel.*` 系统属性 | 全文无 `ConfigSource` / `getRemotePreferences` 引用 |
| 目标进程侧 remote preferences 只读、支持变更监听 | `core/ConfigSource.kt` 类注释 |
| 二级页机制：`FeatureSpec.detailEntryRes` → `FeatureEntry` 展开 → `Routing.onOpenFeatureDetail` 按 id 分发 | `ui/FeatureTab.kt`、`ui/Routing.kt` |
| 单开关二级页的最小样例（52 行，照抄即可） | `ui/ZxCopyButtonScreen.kt` |
| `ConfigSnapshot` 指纹布尔位已用 26/32，只剩 6 位 | `core/ConfigSnapshot.kt` 的 `fingerprint` |
| 无单元测试目录（`app/src` 只有 `main`）；注释里引用的 `ConfigSnapshotFingerprintTest` 不存在 | `ls app/src/` |

## 3. 需求拆解

| 项 | 定义 |
| --- | --- |
| 触发 | 相册选图 → 发送 → 模块给每张图补一条文字 |
| 记录对象 | **名字**：实际发出去的那段文字（文件名去扩展名）。不是内容、不是路径 |
| 追加规则 | 第 1 次：原文；第 n ≥ 2 次：`原文 + " +" + n`（ASCII 空格，`+` 后不空） |
| 例子 | 第 1 天 `征和工业 003033` → 第 2 天（重新截图、同名）`征和工业 003033 +2` → 第 3 天 `… +3` |
| 跨天 / 跨重启 | 保留，不设过期 |
| 同批重复 | 同一名字在一批里出现两次，第 2 条就是 `+2` |
| 追加次数设置 | 只有**落在时段里发出的**那次才计数；时段外发的既不计数也不占号（2026-11-11 加，见 §12） |
| 清空 | 设置页「清空发送记录」→ 下次从 1 重新数 |
| 不计数的情况 | 名字为空、或取不到路径时的 `unknown` —— 一律按第 1 次发原文，不写记录 |

## 4. 方案

### 4.1 计数存储（模块侧，微信进程）

```text
SharedPreferences 名：wxpiclabel_repeat      // 落在 /data/data/com.tencent.mm/shared_prefs/
键：n:<label>     →  "<count>|<lastMs>"      // label = 实际发出去的文字
键：__reset       →  "<已应用的重置令牌>"      // 与配置键 img_label_repeat_reset 比对
```

- **identity = label 本身**（`labelOf(path)` 的返回值，`trim()` 后）。`n:` 前缀只为和 `__reset` 分开。
- **零文件 IO**：不算哈希、不读图片、不解析路径 —— 一次 `SharedPreferences` 读写就是全部成本。
- **上限**：最多 300 条，超出按 `lastMs` 淘汰最旧 —— 防止长期使用把 prefs 撑大。
- **清空**：模块每次计数前读一次 `img_label_repeat_reset`（String，毫秒时间戳），与本地 `__reset` 不同 → 整体 `clear()` 并写回。**不需要监听器**，天然幂等，App 端只要写一个新值即可。
- **失败即降级**：读写抛异常 → 本次按「第 1 次」处理，照常发原文，日志一条 `repeat: … failed`。**绝不因为计数失败少发字或阻塞发送**。

### 4.2 模块怎么读到开关

模块当前没有任何配置通道。两条路：

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| **A（选它）** | 模块内 `getRemotePreferences(ConfigKeys.GROUP)` 直接读三个键：`feature_img_label` / `img_label_repeat_count` / `img_label_repeat_reset` | 新增约 30 行 Java；读点在「一批发送开始时」一次，不是热路径 |
| B | 引入 `ConfigSource` + 给 `ConfigSnapshot` 加两个字段 | 要动 30 字段的 data class、指纹位预算（26→27，只剩 5 位）、`ConfigSource.read()`，且其中 28 个证券键对本模块是死代码 |

选 A 的理由：读点冷、键只有 3 个、diff 最小；等证券残留（`feature/` 下 18 个包、`ConfigSnapshot` 28 个字段）清完，再统一收进快照。

```java
// 懒加载 + 失败即默认值，绝不抛给调用方
private SharedPreferences cfgPrefs() {
    if (cfgPrefs == null) {
        try { cfgPrefs = getRemotePreferences(ConfigKeys.GROUP); }
        catch (Throwable t) { log("config: remote prefs unavailable: " + t); }
    }
    return cfgPrefs;
}
```

### 4.3 开关层级

| 开关 | 键 | 默认 | 关掉的效果 |
| --- | --- | --- | --- |
| 总开关（已有） | `feature_img_label` | `true` | 整个接管不生效，退回微信原生批量发送（**行为变更**：现在它不起作用） |
| 发送设置（新增） | `img_label_repeat_count` | `true` | 只发纯文件名，不追加次数 |

「发送设置」入口按现有约定**只在总开关打开时展开**（`FeatureEntry` 的 `AnimatedVisibility`）。

### 4.4 防重复计数

同一批里 `labelOfEntry` 可能被调用两次（接管里算一遍；失败后 `restoreAndFallback` 交回微信，`db0.u#Vj` 再算一遍）。做法：

- 每次 `on5.f#a` 拦截开始时 `new IdentityHashMap<Object, String> suffixCache`；
- `suffixFor(entry)` 先按**条目对象身份**查缓存，命中直接返回；
- 未命中才 bump 一次并写入。

结果：一次发送 = 一次计数；回退路径复用同一份后缀，不会跳成 `+3`。

### 4.5 App 端「发送设置」页

照 `ZxCopyButtonScreen.kt` 的形状：

```text
SmallTitle「重复图片」
  Card
    GsSwitchPreference  重复图片追加次数 / 同名图片再次发送时，在文字后加 +2、+3…
SmallTitle「记录」
  Card
    BasicComponent      清空发送记录（右侧 ›）/ 下次发送时重新从 1 开始计数
```

- 开关写 `ModulePreferences.edit { putBoolean(ConfigKeys.IMG_LABEL_REPEAT_COUNT, v) }`。
- 清空写 `putString(ConfigKeys.IMG_LABEL_REPEAT_RESET, System.currentTimeMillis().toString())`，然后 Toast。
- **不显示记录列表**：记录在微信进程的数据目录里，模块 App 读不到（跨 uid）。真要显示得让模块 App 开一个 exported Provider 给微信进程回调，v1 不做（见 §8）。

## 5. 改动清单

### 第一批 · App 端（不动模块，可单独编译验证）

| 文件 | 改动 |
| --- | --- |
| `core/ConfigKeys.kt` | 加 `IMG_LABEL_REPEAT_COUNT = "img_label_repeat_count"` / `DEFAULT_… = true`；`IMG_LABEL_REPEAT_RESET = "img_label_repeat_reset"` / `DEFAULT_… = ""`；登记进 `ALL_KEYS`、`FEATURE_PARAM_KEYS`、`DEFAULTS` |
| `ui/FeatureCatalog.kt` | `img_label` 那条补 `detailTitleRes` / `detailEntryRes` / `detailEntrySummaryRes` |
| `ui/Routing.kt` | 加 `data object ImgLabelSend : Route`；`RouteSaver` 的 save / restore 各补一支；`when (route)` 补一支 `SubScreen(title = …) { ImgLabelSendScreen() }`；`onOpenFeatureDetail` 补 `"img_label" -> Route.ImgLabelSend` |
| `ui/ImgLabelSendScreen.kt` | 新建，照 `ZxCopyButtonScreen.kt` |
| `res/values/strings.xml`、`values-en/strings.xml` | 10 条：`img_label_detail_title` / `img_label_send_entry` / `img_label_send_entry_summary` / `img_label_section_repeat` / `img_label_repeat_switch` / `img_label_repeat_summary` / `img_label_section_record` / `img_label_repeat_clear` / `img_label_repeat_clear_summary` / `img_label_repeat_cleared` |

### 第二批 · 模块端

| 文件 | 改动 |
| --- | --- |
| `RepeatCounter.java`（新建） | `bump(label)` 返回次数 / `applyResetIfNeeded()` / 300 条淘汰；全 `try/catch`，失败返回 1 |
| `WxPicLabelModule.java` | `cfgPrefs()`；`suffixCache`；`sendLabel` 前按 label 拼后缀；接管入口按 `feature_img_label` 判是否接管；日志加 `repeat: label=… count=…` |

### 第三批 · 文档与门禁

| 文件 | 改动 |
| --- | --- |
| `docs/README.md` | 索引登记本篇 |
| `CHANGELOG.md` | 一行（用户可感知改动） |
| `PROGRESS.md` | 实现与实测记录 |
| `python tools\check-docs.py` | 退出码 0 |

## 6. 风险与回退

| 风险 | 影响 | 处置 |
| --- | --- | --- |
| 两张不同图同名 | 算成同一张，次数接上 | **这正是预期**（同一只票的截图就该接着数） |
| 改文件名 | 该名字从 1 重新数 | 可接受；写明「认名字」 |
| 微信「清除数据」 | 记录一起没了 | 可接受，文档写明 |
| 记录无限增长 | prefs 变大 | 300 条上限 + 最旧淘汰 |
| 总开关接上后「关掉就不生效」 | 与旧行为不同（旧版关不掉） | 这正是用户预期；CHANGELOG 写一行 |
| 计数逻辑异常 | 少发字 | 全部 `try/catch`，异常按第 1 次处理，发送链路不受影响 |
| 回退 | — | 三档：关「发送设置」→ 只发纯文件名；关总开关 → 完全不接管；删 `RepeatCounter` 调用即回到现状 |

## 7. 验收

1. `build.cmd` → `BUILD SUCCESSFUL`，贴 APK 大小。
2. 实机：功能页 → 总开关打开 → 出现「发送设置」入口 → 进入页面，开关默认开。
3. **同一名字连发三次**（可以三张不同截图，只要文件名相同）：`征和工业 003033` → `征和工业 003033 +2` → `+3`。
4. 杀微信重启后再发同名 → `+4`（跨进程持久）。
5. 点「清空发送记录」→ 再发 → 回到 `征和工业 003033`。
6. 关「发送设置」开关 → 连发三次都是纯文件名。
7. 关总开关 → 微信原生批量发送，无补字。
8. 日志关键字：`repeat: label=… count=…`、`repeat: reset applied`。

## 8. 已拍板项

- **认名字，不认内容**：重新截图、内容变了，只要文件名相同就接着数（用户 2026-11-06 明确）。
- **默认值**：`img_label_repeat_count` 默认 **true**（这就是用户要的行为）。
- **不做过期**：不设「N 天后归零」。
- **v1 不显示记录列表**：跨 uid 读不到；要做需要模块 App 开 Provider，单独一轮。
- **总开关接管**：本轮把 `feature_img_label` 接上（关 = 不接管）。若希望它继续只做展示，把 `WxPicLabelModule` 里的判断去掉即可。

## 9. 实现（2026-11-06 落地）

与上面的方案一致，落地时只有一处按现场情况调整（并行会话重写了 UI，见下）。

| 文件 | 实际改动 |
| --- | --- |
| `feature/imglabel/RepeatCounter.java`（新） | `bump(label, resetToken)` → 次数；键 `n:<label>` → `"<count>|<lastMs>"`；300 条上限按最后发送时间淘汰；令牌不同整体 `clear()`；异常一律返回 1 |
| `WxPicLabelModule.java` | `cfgPrefs()`（`getRemotePreferences(ConfigKeys.GROUP)` 懒加载）、`cfgBool/cfgStr`、`featureEnabled()`、`repeatCounter()`、`textFor(entry)`；接管入口加总开关判断；每批 `batchTexts = new IdentityHashMap<>()` 做身份缓存 |
| `ui/ImgLabelSendScreen.kt`（新） | 两个分组：`重复图片`（开关）/ `记录`（清空发送记录） |
| `ui/FeatureCatalog.kt` | `img_label` 补 `detailTitleRes` / `detailEntryRes` / `detailEntrySummaryRes` |
| `ui/Routing.kt` | `Route.ImgLabelSend` + Saver 两支 + 渲染一支 + id 映射 |
| `ui/Screens.kt` | `HomeScaffold` 新增 `onOpenFeatureDetail: (String) -> Unit`，接到 `FeatureTab` |
| `res/values/strings.xml`、`values-en` | 10 条新文案；顺手补齐 `values-en` 一直缺的 `category_img_label` 三条 |

**与方案的一处差异**：方案写的是「兜底队列 `armFromParams` 也走 `textFor`」，实现时再加了一条 —— 接管开始时先把 `pendingLabels` 取出来复用并清空，这样 `db0.u#Vj` 若先于 `on5.f#a` 到达、已经算过文字，接管直接按序号取用，既不重复计数，也不会在接管结束后被轮询再补发一遍。

**已知边界**：`Vj` 先到且清单对象与接管的不同、又恰好绕过上面的复用（例如进程刚重启那一批）时，理论上可能多计一次；日志里会出现两条 `repeat: label=… count=…`，可据此定位。

**包路径变更**：落地时 `RepeatCounter` 在包根；随后并行会话把模块 Java 收进了 `feature/imglabel/`（`RepeatCounter` / `UiSender`），`Reflect` 进了 `util/`。包名与 import 已同步，`build.cmd` 编译通过。

## 11. 同页新增的「重命名规则」与「发送记录」（2026-11-06）

两个新的三级页（都是发送设置的子页）：

### 重命名规则（`ui/ImgLabelRenameScreen.kt`）

- 一行一条 `原名 -> 新名`，也接受 `=>` / `=` / 制表符；`#` 开头是注释。
- 按**文件名（去扩展名）**精确匹配，命中就把发出去的文字换成右边那段；
  计数键跟着新名字走（与「认名字不认内容」一致）。
- 解析在微信进程侧做（`feature/imglabel/RenameRules.java`），每批一次、失败发原名。
- 配置键 `img_label_rename_rules`（String，整表一个字符串）—— 一行一条就是文件的全部内容，
  比多行的 StringSet 好导入导出。
- 页面（2026-11 重做）：规则逐条成行 + 新建规则走弹层 + 清空走二次确认，导入就地合并；
  形态、校验与导入语义的正本在 [IMG-LABEL-RENAME.md](IMG-LABEL-RENAME.md)，本节只留配置侧事实。
  导入导出走 SAF（`OpenDocument` / `CreateDocument`），导出文件名默认 `wxpiclabel-rename.txt`。

### 发送记录（`ui/ImgLabelRecordScreen.kt`）

- 计数表在微信进程自己的 prefs 里（跨 uid 读不到），所以列表靠回传：
  设置页写令牌 `img_label_record_dump` → 微信进程轮询到变化 →
  `RepeatCounter#snapshot()` → `RecordExporter#push` → FriendBridgeProvider `/records` →
  模块 App `RecordStore` 落盘。每批发完也会推一份。
- 页面：记录数 + 更新时间 + 列表（文字 / 第 N 次 / 最后发送时间）+ 清空发送记录。
  清空沿用原令牌；回传时微信进程会先应用清空，所以列表立即变空。

## 10. 同页新增的「发送提示」开关（2026-11-06）

同一张二级页后来又加了两个开关，键与默认值都按 §4.3 的层级登记：

| 开关 | 键 | 默认 | 关掉的效果 |
| --- | --- | --- | --- |
| 发送进度提示 | `img_label_progress_toast` | `true` | 发图批次不再弹系统 Toast |
| 发送期间屏幕常亮 | `img_label_keep_screen_on` | `true` | 批次期间不加 `FLAG_KEEP_SCREEN_ON` |

- 实现跑在微信进程的 `feature/imglabel/UiHud.java`，只在主进程生效（子进程各弹一个会把 Toast 队列排死）。
- Toast 复用同一个实例 `setText` + `show` 原地更新「正在发送 3/12」，不复用会排一长串。
- 两个键都已登记 `ALL_KEYS` / `FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS` / `DEFAULTS`（漏 `SNAPSHOT_KEYS` 会静默失效）。

## 12. 同页新增的「追加次数设置」（2026-11-11）

> **一句话**：`+N` 只累计**落在时段里发出去的那几张**；时段外发的图连计数表都不碰，所以不会出现「今天三点半前发过一次、三点半后再发就是 +2」。

### 12.1 为什么要闸在计数之前

用户的原话是「今天三点半前发的，三点半以后发也不会 +2」。如果先照常计数、只在显示时判断时段，三点半后第一次发送读到的就是三点半前留下的 `1`，直接变成第 2 次 → `+2`。所以判定必须在 `bump()` **之前**：时段外这一次压根不调 `bump`，计数表里没有它，第二天同样不受影响。

### 12.2 配置

| 键 | 类型 | 默认 | 含义 |
| --- | --- | --- | --- |
| `img_label_repeat_window` | String | `""`（空 = 全天） | 追加次数设置，规范写法 `15:30-17:30,20:00-21:00` |

- 已登记 `ALL_KEYS` / `FEATURE_PARAM_KEYS` / `SNAPSHOT_KEYS` / `DEFAULTS` 四处（漏 `SNAPSHOT_KEYS` 会静默失效）。
- 出厂值空串 = 全天追加，等于没有这道闸门；老用户升级后行为不变。
- 解析在**微信进程侧**（`feature/imglabel/RepeatWindow.java`），App 端页面用的是同一份 `parse` / `parseSpan`，所以「页面上显示几段」就是「发图时按几段判定」。

### 12.3 口径

| 项 | 定义 |
| --- | --- |
| 端点 | **含开始、不含结束**：`15:30-17:30` 里 15:30:00 算、17:30:00 不算 |
| 跨零点 | `start > end` 视为跨零点（`23:00-01:00`），夜班时段也表达得出来 |
| 多段 | 逗号/分号/换行分隔，全角「，」「；」「、」也认；单段格式不对就丢掉那一段，好段照留 |
| 全天 | 空串、或一段都解析不出来 → 全天（宁可多记，不把功能整体关掉） |
| 判定时刻 | 每张图**算文字那一刻**的本地时间，不是真正发出去那一刻（兜底队列是异步跨批的，见 §12.4） |
| 改时段 | **不回算历史**：改完只影响之后的发送，台账里已有的条目不动 |
| 跨天 | 计数表不按天清（沿用现状）：今天时段内发过 2 次，明天时段内第 1 次就是 3 |

### 12.4 为什么文字要跟序号一起打包（`Draft`）

兜底队列（`armFromParams` → 轮询发送）是**异步、跨批**的：一批图先在 `draftFor` 里算好文字，真正发出去可能已经是下一批、甚至跨过了时段边界。如果等到发送那一刻再去查计数表，15:29 算好的图 15:31 才发，就会被记成「第 1 次」——那是错的。

所以 `draftFor` 返回 `Draft`（`label` + `n`），序号随文字一起进队列：

- `n >= 2` → 发 `label + " +" + n`；
- `n == 1` → 发 `label`（时段内第 1 次）；
- `n == 0` → 发 `label`，且**没进过计数表**（时段外 / 重复功能关着）。

`noteSent(Draft)` 把 `draft.n` 原样写进台账，于是「时段外发出的那次」在记录里是 `n = 0`，显示成「未累计 · 时间」，而不是「第 0 次」。

### 12.5 台账语义变更（`n = 0`）

`RepeatCounter#record(label, n, batch, atMs)` 原来在 `n <= 0` 时会去计数表回查当前次数；现在 `0` 是**明确的值**「这次没累计」，落盘原样写 `0`。

- `rebuildCounters()` 按「该名字所有事件的 `max(n)`」重建，未累计条目是 0，不会把次数抬上去；只有某个名字**一条正数事件都没有**时才删键（`if (n == null || n <= 0) ed.remove(...)`），所以「时段内发过 2 次 + 时段外发过 3 次」编辑后台账重建仍得 2，不会被抹掉；
- 记录页可编辑的「改次数」仍然接受 1..999：用户手动把一条未累计改成 5，是在显式修正历史，按他改的算。

### 12.6 页面

- 二级页「重复图片」卡里多一行「追加次数设置」，摘要：全天 → 「全天追加」；有段 → 「只在 15:30-17:30、20:00-21:00 内追加」。
- 三级页 `ui/ImgLabelRepeatWindowScreen.kt`：第一张卡每行一个时段（行尾 ✕ 删），第二张卡「新建时段」+（有时段时）「恢复全天追加」。
- 新建走**贴底时段滚轮弹层**（`TimeRangeDialogSpec`）：开始 时/分 + 结束 时/分，四根 Miuix `NumberPicker`，`wrapAround` 打开（23 点再往上滑回 0 点、59 分往下滑回 0 分，与 iOS 闹钟一致），初始值是「当前整点 → +2 小时」。
  滚轮只能给出合法数字，所以**不存在「格式不对」**，校验只剩两条、都当场提示不落盘：起止落在同一分钟 / 与已有时段重叠（跨零点的段先按零点拆两截再比，否则「23:00-01:00」与「00:00-00:30」会漏判）。
  确认时交给页面的是两个**分钟数**，走 `RepeatWindow.Span.of(start, end)` 直接造段，不再把值拼成字符串让解析器回读一遍 —— 少一条「拼错了」的失败路径。
- 端点仍按系统口径：含开始、不含结束；`start > end` 就是跨零点（两条滚轮组谁也不会互相推开）。
- 行副标题显示这段持续多久（「2 小时」「45 分钟」「1 小时 30 分钟」），跨零点的额外标「 · 跨零点」，免得被当成只生效一小时。
- 删掉最后一段 = 恢复全天，与「恢复全天追加」同一条路径（写空串）。
- 路由：`Route.ImgLabelRepeatWindow`，`editParent` / `RouteSaver` 两支 / 三级页挂载 / 二级页回调，共 5 处。

### 12.7 验收（无实机，靠编译 + 单测）

- `app/src/test/java/.../feature/imglabel/RepeatWindowTest.kt`：空串→全天、端点含开始不含结束、跨零点、多段分隔符、宽容输入丢坏段、`plus`/`minus`/`raw` 规范化、`parseSpan` 严格拒绝。
- `gradlew :app:testDebugUnitTest` 与 `assembleDebug` 通过。
- 实机路径（用户自测）：设 15:30-17:30 → 15:00 发一张 → 15:40 再发同一张，应仍是无后缀；16:00 连发同一张，第 2 条起才 +2。

