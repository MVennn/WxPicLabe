# 发送记录：台账、回传与可编辑记录页

> **一句话**：发送记录的数据正本（逐次台账 + 聚合计数）、跨进程回传协议、改名/改次数/删除的语义与记录页的搜索、时间筛选、按批次分组的界面口径。

记录页面上看到的一切，来源只有一个：**微信进程** `wxpiclabel_repeat.xml` 里的台账。
模块 App 既写不进去，也不能直接读，所以它只做两件事——发令牌请求，和在本机缓存一份快照 + 一层编辑覆盖。

## 数据模型（微信进程侧）

文件：`getSharedPreferences("wxpiclabel_repeat", MODE_PRIVATE)`，类：`feature/imglabel/RepeatCounter.java`。

| 键 | 值 | 用途 |
| --- | --- | --- |
| `n:<名字>` | `次数|毫秒时间戳` | 聚合表。**只**用于 `+N` 文案，跟记录页的行数无关 |
| `e:<seq>` | `时间|次数|批次|名字` | 逐次台账，一行 = 一次真实发出的文件名 |
| `__seq` | 递增计数器 | 台账行号，永不复用（编辑/删除都靠它定位） |
| `__edits` | 毫秒时间戳 | 已应用到的编辑令牌，用来判断动作串是否重放过 |
| `__migrated` | `1` | 旧版本只有聚合表、没有台账，迁移只做一次 |
| `__reset` | 毫秒时间戳 | 已应用到的「清空记录」令牌 |

- `MAX_ENTRIES = 300`：聚合表上限，超了按最后时间淘汰最旧的名字。
- `MAX_EVENTS = 600`：台账上限，超了按时间淘汰最旧的行；记录页最多也就展示这么多。
- 名字在台账里存**原名**（`RepeatCounter.baseLabel` 会剥掉发送时的 ` +2` 后缀，但只在
  「后缀全是数字、且原名确实存在聚合表里、且整串不在表里」时才剥，用户真有张图叫 `日报 +2` 不会被误剥）。
- 所有键都用**防御式读取**（`RepeatCounter` 里的 `strOf/longOf/boolOf` 走 `getAll()` 判实际类型）：
  手工注入或旧版本留下的错类型（例如把 `__migrated` 写成字符串）不会再抛 ClassCastException
  被 `catch (Throwable)` 吞掉、进而把整份台账静默清空。
- 旧数据迁移：`ensureMigrated` 把 `n:` 表按 `1..count` 合成事件，**写进真台账**（`seq` 是真号），
  批次写成 `m`（`RepeatCounter.BATCH_MIGRATED`）。所以迁移出来的行**可编辑**，只是时间只有
  聚合表里的「最后一次发送」——同名的多条事件时间相同，界面按 10 分钟窗口分组而不是按批次。

## 回传协议（微信进程 → 模块 App）

两条通道要分清，混用会解析失败：

| 通道 | 内容 | 分片单位 | 外壳 |
| --- | --- | --- | --- |
| `/friends` | 好友名单 | 200 条一片 | Provider 加 `{"fetchedAt":..,"friends":[..]}` |
| `/records` | 整份记录文档 | **3000 个字符**一片 | 无外壳，原样拼接 |

- 文档结构（`RecordExporter.buildDoc`，字段名故意短，键名长度直接变成切片数）：

  ```json
  {"at":1700000000000,"v":2,"edited":"1700000000000",
   "records":[{"label":"日报","count":3,"last":1700000000000}],
   "events":[{"s":12,"t":1700000000000,"l":"日报","n":3,"b":"b1700000000000-1"}]}
  ```

- 切片用 UTF-16 code unit 计，切点落在**代理对中间**时回退一个字，不会把 emoji 缝成半个。
- Provider（`app/FriendBridgeProvider.kt`）的 `insert` 只在 `isFriends` 时套外壳；
  records 分支 `sb.toString()` 直接落 `records_json`，`records_at` 记写入时刻。
- 模块 App 侧 `RecordStore.read` 解析成 `Book(at, edited, rows, events)`；
  `events` 缺失（**微信进程里跑的还是旧模块**）时用 `synth(rows)` 按聚合表合成占位事件
  （`seq = 0`、`batch = "m"`）。这类行是唯一的「只读」来源：没有 `seq` 就没有动作能定位到它。
  页面照样能看，点它会弹一张说明卡（不是静默无反应），并且**重启一次微信**让新模块进进程后，
  `ensureMigrated` 会把同一份历史升级成带 `seq` 的可编辑条目。

## 编辑语义（模块 App → 微信进程）

编辑动作不直接跨进程写数据，而是**追加一串动作、换令牌**，由微信进程轮询重放：

1. 记录页点「改文件名 / 改第几次 / 删除这条」→ `RecordEdits.commit(ctx, op)`。
2. `RecordEdits` 先把**同一个 seq 的旧动作剔掉**再追加——`StringSet` 无序，不剔的话改名 A、改名 B 谁生效看哈希。
3. 写本机副本 `wxpiclabel_record_edits.xml`，同时 `ModulePreferences.edit` 写
   `img_label_record_edits`（动作集合）与 `img_label_record_edits_token`（毫秒时间戳）。
4. 微信进程 `checkRecordEdits()`（`startFallbackPoller` 里 700 ms 一次）比对令牌 →
   `RepeatCounter.applyEdits(ops)` → `markEditsApplied(令牌)` → `pushRecords("edits")` 回推新快照。

动作串语法（`|` 分隔，`RepeatCounter.applyEdits` 与 `RecordEdits.apply` 口径必须一致）：

| 动作 | 含义 |
| --- | --- |
| `d|<seq>` | 删除该次记录 |
| `l|<seq>|<新名字>` | 改这条事件的文件名（只改台账，不动聚合表的键） |
| `n|<seq>|<次数>` | 把这条事件改成「第 N 次」 |

- **幂等**：动作自带 seq，重放多少遍结果一样；`__edits` 只是省一次的快捷判断。
- **反推聚合表**：`applyEdits` 改完对受影响的每个名字跑 `rebuildCounters`——
  取台账里该名字剩余事件的最大 `n`，为 0 就删掉 `n:<名字>`。所以删掉最近一次，下次发同名图就是第 N 次；
  全删干净就是第 1 次。
- **动作串上限 200 条**：`RecordEdits.trim(ctx, liveSeqs)` 在每次刷新拿到新快照后调用，
  丢掉「seq 已经不在台账里」的旧动作（那说明微信进程早就重放并淘汰了）。
- 改名只影响记录页展示与后续 `+N` 判断的输入，不会给微信里的那张图改名。

## 记录页结构（`ui/ImgLabelRecordScreen.kt`）

四级信息，从粗到细：

1. **概览卡**：`共 N 条记录` + `记录更新于 MM-dd HH:mm:ss`，点一下写 `img_label_record_dump` 令牌刷新；
   刷新中显示「正在读取记录…」，`LaunchedEffect` 每 400 ms 轮询一次、最多 45 次，读到 `at` 变了就停。
2. **搜索与筛选卡**：`BasicTextField` 按文件名 `contains`（忽略大小写）；下面一行时间范围
   （全部 / 今天 / 近 7 天 / 近 30 天，`ChoiceDialogSpec` 四档，零点按本机时区算）。
3. **只读提示卡**（仅当存在 `seq = 0` 的行时出现）：`N 条是旧版记录，只能查看` +
   副标题「重启一次微信即可自动升级为可编辑」，点开是说明弹层（单按钮「知道了」）。
4. **按时间段**：同一批发送折叠成一张卡，组头就是**那个时间段的时间戳**，点一下展开/收起那一堆图。
   - 分组键：真实批次 `ev.batch`；**没有批次时**（`batch` 为空，或等于迁移占位 `m`）按
     `at / 600000` 折成 10 分钟一格。迁移条目必须走这条兜底：它们批次全是 `m`，
     按批次分组会把整段历史挤成一组，用户看到的就是「根本没有分组」。
   - 组头时间文本：单条 `MM-dd HH:mm:ss`；同日多条 `MM-dd HH:mm–HH:mm:ss`；
     跨日 `MM-dd HH:mm – MM-dd HH:mm`。**组头永远是时间戳**（那就是「调出那一堆图」的入口），
     旧版来源只体现为摘要后缀 `N 张 · 旧版`。
   - 组内按时间正序，行摘要 `第 N 次 · HH:mm:ss`；只读行显示 `第 N 次 · HH:mm:ss · 只读`。
   - 默认只展开**最新一组**（`expanded == null` 的含义），手动展开/收起后按用户的选择走。
5. **操作卡**：清空发送记录（`ConfirmDialogSpec` 二次确认）→ 写 `img_label_repeat_reset` 令牌、
   清空动作串、再写一次 `img_label_record_edits_token`，然后请求刷新。

行点击 → `ChoiceDialogSpec`（改文件名 / 改「第几次」/ 删除这条）→ `TextInputDialogSpec`
（`numeric = true`、`maxChars = 3`、`validate` 限 1..999；校验不过不关弹窗、错误提示显示在输入框下方）。
只读行点击 → `ConfirmDialogSpec` 说明卡（`cancelText = ""` 时只画一颗按钮）。两种都是原地弹窗，
所以两种行都画朝下箭头（`docs/UI.md` §4）。

## 排障

| 现象 | 先看什么 |
| --- | --- |
| 记录页一直「正在读取记录…」 | 微信进程日志有没有 `records: pushed ...`；没有就是令牌没到（`cfgPrefs` 重附着失败，见 `docs/PITFALLS.md` 第 20 条） |
| 记录页有行、点进去只弹「旧版记录只能查看」 | 微信进程里跑的还是旧模块（回传的文档没有 `events`）。装了新包要**重启一次微信**让模块进进程 |
| 记录页有条目但都显示「旧版」 | `__migrated` 已置位但台账被清空过；发一张新图就会产生带批次的新行 |
| 改了「第几次」没生效 | 模块日志看 `checkRecordEdits` 是否换了令牌；`img_label_record_edits_token` 没变说明 `ModulePreferences.edit` 没落盘 |
| 改名后行数没变但 `+N` 变了 | 正常：改名只动台账展示，`rebuildCounters` 会按剩余最大次数重算聚合表 |
| 真台账整份消失、只剩「旧记录」 | `wxpiclabel_repeat.xml` 里有键被写成别的类型（手工注入、旧版本残留）。`strOf/longOf/boolOf` 已按实际类型转换，正常不该再发生；先备份文件再看 `events()` 返回的条数 |
| 删了最近一次，下次发还是原次数 | 台账里该名字还有更大的 `n`（删的是中间那条）；要归零就连着删干净的或直接清空记录 |

日志关键字：`records: snapshot N row(s) / M event(s) reason=`、`records: pushed N row(s) / M event(s) in K chunk(s)`、
`records: edit applied`、`records: note failed for <名字>`（台账写失败，通常是 `MAX_EVENTS` 淘汰或 prefs 只读）。

## 已知限制

- 台账上限 600 条，超过按时间淘汰最早的；记录页不会无限长。
- 迁移来的旧行可编辑但**时间不精确**：只有聚合表记的「最后一次发送」，同名的多条事件时间相同。
- `seq = 0` 的合成行永远只读：台账里没有对应条目，删除只能走「清空发送记录」或重启微信让模块升级。
- 记录页的编辑发生在**发出去之后**，不会追溯修改微信聊天里已发出的文字。
