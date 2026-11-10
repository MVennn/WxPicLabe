# 设置界面总览

> **一句话**：模块设置界面（Compose + Miuix）的文件分工、页面结构、弹层体系与改 UI 时必须守的硬规则；动 `ui/` 之前先读本篇。

材质表现见 [LIQUID-GLASS.md](LIQUID-GLASS.md)，用户可见文案见 [NAMING.md](NAMING.md)，
Hook 与数据链路见 [ARCHITECTURE.md](ARCHITECTURE.md)。

> 跨仓通用的界面设计规范（骨架与间距、九种行型的选型表、可编辑字段三形态、弹层选型与尺寸、图标几何、文案 / 空态 / 反馈口径、提交前自检）正本是 [DESIGN-SYSTEM.md](DESIGN-SYSTEM.md)。本篇只写本仓的页面与硬规则；两处冲突以 DESIGN-SYSTEM 为准。

---

## 1. 技术底座

| 项 | 值 | 备注 |
| --- | --- | --- |
| UI 框架 | Jetpack Compose + **Miuix 0.9.3** | 不要升到 0.9.4+：它要求 AGP ≥ 9.1（见 [BUILD.md](BUILD.md)） |
| 入口 | `SettingsActivity`（`ui/SettingsActivity.kt`） | 单个 Activity，不引入导航库 |
| 路由 | `ui/Routing.kt` 的 `sealed interface Route` | 两层，`when` 穷尽，漏分支直接编译失败 |
| 主题 | `MiuixTheme` + 自建深色判定 | `rememberDarkTheme()`，跟随系统 / 亮色 / 暗色 |
| 语言 | `LocaleHelper.wrap()` 在 `attachBaseContext` | 见 §6 |

---

## 2. 文件职责表

**外壳与路由**

| 文件 | 职责 |
| --- | --- |
| `SettingsActivity.kt` | Activity 入口：edge-to-edge、系统栏、TaskDescription、`setContent` |
| `Routing.kt` | `Route` 定义、`MainScreen`、二级页切换动画、可预测返回 |
| `Screens.kt` | `HomeScaffold`（一级首页外壳）、`SubScreen`（二级页外壳）、`DisclaimerContent` |
| `SystemBars.kt` | 状态栏 / 手势导航条的沉浸同步 |

**一级页内容**

| 文件 | 职责 |
| --- | --- |
| `FeatureTab.kt` | 功能页：按 `FeatureSpec` 渲染开关卡片 |
| `FeatureCatalog.kt` | 功能目录（**纯展示元数据**，不引用任何 Hook 实现） |
| `ModuleTab.kt` | 概览页：**只做展示，不放任何操作项** |
| `SettingsTab.kt` | 设置页：桌面图标 / 液态玻璃 / 光效 / 语言 / 主题 / 可预测返回 / 重置模块 |
| `AboutTab.kt` / `AboutHero.kt` | 关于页：作者、许可、免责声明入口 |

**二级页与三级页**

| 文件 | 职责 | 层级 |
| --- | --- | --- |
| `ImgLabelSendScreen.kt` | 发送设置：重复图片追加次数、清空发送记录、各三级页入口 | 二级 |
| `ScopePickerScreen.kt` | 会话白名单勾选 | 三级 |
| `ImgLabelRenameScreen.kt` | 重命名规则编辑 | 三级 |
| `ImgLabelRecordScreen.kt` | 发送记录（同名图片计数表） | 三级 |
| `ImgLabelFilterScreen.kt` | 文字过滤（黑白名单）：开关定名单，关键字逐条增删 | 三级 |
| `MarkdownText.kt` | 免责声明 / 开源声明的 Markdown 渲染 | 二级 |

**视觉与基础件**

| 文件 | 职责 |
| --- | --- |
| `Glass.kt` | 液态玻璃：`GlassTier` / `glassTier()` / `glassSurfaceTint()` / `GlassTopBar` |
| `LiquidPhysics.kt` | 按压物理三件套（`pressProgress` 驱动 blur↔lens） |
| `Dock.kt` | 底栏两种形态：`MiuixDock` / `LiquidGlassDock` |
| `AppDialogHost.kt` | 自绘弹层宿主（`DialogHost`） |
| `AppDialogSpec.kt` | 弹层规格：`Choice` / `Confirm` / `TextInput` / `Image` / `Agreement`（**选型表见 §4**） |
| `AppDialogWidgets.kt` | 弹层内部件（选项行、按钮组、链接行） |
| `Widgets.kt` | 通用卡片 / 标题 / 分组 |
| `GsSwitch.kt` | 自绘开关（几何对齐 Miuix，**不在玻璃体系内**） |
| `AppIcons.kt` / `AnimCurves.kt` | 图标与动画曲线常量 |
| `PrefState.kt` | 配置读取的唯一入口（`rememberBoolPref` 系列） |

---

## 3. 页面结构

```text
SettingsActivity
└─ MainScreen
   ├─ HomeScaffold            一级首页（常驻，作为下层页的底衬）
   │  ├─ 顶栏（玻璃或纯色）
   │  ├─ Tab 内容：功能 / 概览 / 设置 / 关于
   │  └─ Dock（两种形态）
   ├─ SubScreen               二级页（按 subProgress 右滑进出）
   │  ├─ 免责声明
   │  ├─ 开源声明
   │  └─ 发送设置
   └─ EditScreen              三级页（按 editProgress 压在二级页之上）
      ├─ 会话白名单
      ├─ 重命名规则
      ├─ 发送记录
      └─ 黑白名单
```

**三层，两条独立的滑动进度**（`subProgress` / `editProgress`，都只在 `graphicsLayer` 的 lambda 里读）。
三级页必须单独占一层：早先版本让三级页顶掉二级页的内容，结果是进三级页没有动画、
从三级页可预测返回时手势把整层推走、露出首页再弹回发送设置。动画表与判据写在 `Routing.kt` 顶部注释里。

**新增二级页三步**（漏一步 `when` 就编译失败，这是刻意的）：

1. `Route` 加一个 `data object`；
2. `RouteSaver` 的 `save` / `restore` 各加一个分支；
3. `MainScreen` 的 `when (shownSub)` 里挂上页面。

**三级页再多一步**：在 `editParent()` 里登记它压在哪个二级页下面 —— 返回目标与「进页面时哪一层该滑」都由这张表决定。

---

## 4. 弹层体系

**一律用自绘 `DialogHost`，不用 Miuix 的 `OverlayDropdownPreference` / `OverlayDialog`。**

原因（2026-08 踩过，重编都复现）：Miuix 的弹层在自己的 `onClick` 里**同步**开始收起动画，
而配置写回（`commit()`）会同步回调改写 Compose 状态，动画与重组打架 → 闪退。
调用侧无法规避，只能换掉。

**写入必须排在收起动画之后**：所有配置写入都通过 `DialogController.dismiss { }` 排到
弹窗彻底关闭之后执行。不要在选项行的 `onClick` 本体里直接调 `ModulePreferences.edit`。

> 3.1.0 曾试过换成 Miuix `OverlayBottomSheet`（配 activity-compose 1.12.4 修掉
> ViewTree Owner 缺失的崩溃），因观感与动画手感不合回退为自绘；activity 升级保留。

五种弹层规格。**选型看形态，不看「能不能跑」** —— 同一个 `ChoiceDialogSpec` 传不传 `anchor`，
渲染出来就是小菜单和全宽大列表两种完全不同的东西（见下表第一行）：

| 规格 | 形态 | 什么时候用 | 硬约束 |
| --- | --- | --- | --- |
| `ChoiceDialogSpec` | **锚定小菜单**：宽 `MenuWidth`，右缘对齐触发行，缩放弹出 / 缩回 | 行内**选值**（主题 / 语言 / 日志等级 / 记录的时间范围）与行内**动作菜单**（改文件名 / 改第几次 / 删除这条） | **必须传 `anchor`**。不传不报错，只会退化成全宽居中大列表 —— 形态错了，代码照样编译通过。动作菜单写 `selectedIndex = -1`（不画勾），点了没变化的项走 `disabledIndices` |
| `ConfirmDialogSpec` | **贴底 sheet**：≤420dp、水平居中、抬到导航条之上 | 不可逆动作的二次确认：删除单条记录、清空台账、重置模块 | `anchor` 恒为 `null`；`message` 必须写清**范围**（见 NAMING §3 例外条） |
| `TextInputDialogSpec` | 贴底 sheet，与 Confirm 同壳，随键盘上抬 | 就地改一个值或新增一条：改文件名、改「第几次」、新建关键字 | 只从行内动作菜单 / 按钮进入；**不要拿它做行内选值**（那属于 Choice）。输入非法时在 `validate` 里返回错误文案，弹层不关、错误显示在输入框下方 |
| `ImageDialogSpec` | 贴底 sheet | 图片预览 | `anchor` 恒为 `null` |
| `AgreementDialogSpec` | 贴底 sheet | 首次启动的免责声明门槛 | **不可关闭**，返回键只吞不放行 |

**行尾箭头就是弹层形态的说明书**，三档只有三个含义：

| 箭头 | 含义 | 例子 |
| --- | --- | --- |
| `DownArrowIcon()` | **原地**出东西：弹出锚定菜单，或原地展开 / 收起 | 主题、语言、日志等级、记录的时间范围、批次组头、记录行 |
| `ArrowRightIcon()` | 进入**二级页**，或推进一个会再弹一层的动作（确认 sheet / 文件选择器） | 发送设置、备份与恢复、重置模块、清空记录 |
| 不画箭头 | 点了**立刻执行**，没有后续界面 | 记录页概览卡（重新拉一次记录） |

> 反例（2026-11 记录页踩过，三处同时犯）：行内动作菜单不传 `anchor` → 变成屏幕正中的全宽列表；
> 原地展开的组头在收起态画 →，让人以为点进去是另一个页面；删除直接在菜单项里执行，没有二次确认。
> 判据很简单：**弹层形态和箭头语义对不上，就是选错了规格**。

**行尾除箭头就只有两种图标**，规格抄 GSAuxiliary 的实现（3.2.1 校准，`QuickConfigEditScreen.kt` 的 `RemoveTermButton`）：

| 图标 | 触控区 | 尺寸 | 颜色 | 位置 |
| --- | --- | --- | --- | --- |
| `AppIcons.Close` 删一个取值项 | `Box(40.dp).clip(CircleShape)` | **不传 modifier**，尺寸由图标自身声明（10 × 10.7dp） | `onSurfaceVariantActions`，**不用 `error` 红** | `contentAlignment = CenterEnd` —— 墨迹中心落在距行右缘 5dp 那条竖线上，与 `›` / `⌄` 同一列 |
| `AppIcons.PlusThin` 新建一项 | 同上 `Box(40.dp)` | 不传 modifier（**与 `Close` 同画布** 10 × 10.7dp） | `primary` | 同上 `CenterEnd` —— 与 ✕ 同一列 |

- **同一张卡里并列的行尾动作图标必须同画布同笔画**：✕ 与 ＋ 上下相邻时用 `AppIcons.PlusThin`（细描边十字，与 `Close` 同参），
  **不要**用 `AppIcons.Add` —— 那是 Material 24dp 网格的填充十字，墨迹约 14dp，和 10dp 的 ✕ 摆一起就是「一大一小、右缘也不齐」
  （2026-11 黑白名单页因此被打回两次）。两边都要包同样的 `Box(40.dp)` + `CenterEnd`，行高与右缘才会对齐。
- **✕ 不是红色**：红色留给「整批毁掉」的动作（清空、重置），删一条取值项是轻量操作，用中性色。
- **不要给行尾图标塞 `Modifier.size()`**：`AppIcons.Close` 是按「与箭头同宽同粗同列」重画的 10dp 叉，一旦被放大就比箭头粗一圈，3.1.1 就是被这个坑过（见 `AppIcons.kt` 注释）。
- 加了 `contentDescription` 就同时给 `clickable` 写 `onClickLabel`，读屏才念得出一句完整动作。

---

## 5. 玻璃与底栏

两处玻璃：顶栏与悬浮底栏；两档光效（完整 / 简约）+ 一档纯色兜底。
参数矩阵、降级链与接入清单全部在 [LIQUID-GLASS.md](LIQUID-GLASS.md)，
这里只记两条容易犯的：

1. **贴边表面不放 rim light**（会连成一条横线）——顶栏就是贴边表面；
2. **`layerBackdrop` 这类每帧采样的修饰符必须条件挂载**，非玻璃模式与淡出期都不要常驻。

底栏两种形态由「液态玻璃」开关决定：开 → `LiquidGlassDock`，关 → `MiuixDock`。
两者的**条目与顺序必须一致**，改一个就要改另一个。

---

## 6. 主题、语言、系统栏

**深色模式**：`rememberDarkTheme()` 读 `ConfigKeys.THEME`，`system` 时看 `isSystemInDarkTheme()`。

**语言**：`LocaleHelper.wrap()` 在 `SettingsActivity.attachBaseContext` 里包装 Context ——
必须在这里生效，Activity 重建时语言才跟着变。它读的是 `ModulePreferences`，
此时 `Application.onCreate` 已跑过，配置可用。

**系统栏**：一律全透明，内容铺到屏幕物理边缘。但**光设透明不够** ——
`SystemBarStyle.auto` 的 `nightMode` 是 `MODE_NIGHT_AUTO`，androidx 的
`EdgeToEdgeApi29` 遇到它会顺手打开 `navigationBarContrastEnforced`，
系统随后在透明导航栏上叠一层半透明遮罩（就是「手势条那条是纯色」的来源）。
所以 `onCreate` 里要再显式关一次（主题层也关了一份，双保险）。

---

## 7. 改 UI 的硬规则

1. **界面不直接读写 SharedPreferences**，一律走 `PrefState.kt` 的 `remember*Pref`。
2. **界面不引用 Hook 实现类**：设置界面跑在模块自己的进程，那里没有
   `io.github.libxposed.api`（依赖是 `compileOnly`）。判据是「这个类会不会被模块自身进程加载」，
   与它放在哪个目录无关 —— 见 [ARCHITECTURE.md](ARCHITECTURE.md)。
3. **文案按 [NAMING.md](NAMING.md) 起名**，中英两份 `strings.xml` 同步改。
4. **改完必须真机看一遍**：Compose 的布局问题（裁切、闪白、重复测量）编译期零报错。
5. **不要顺手改动画参数**：曲线与时长是逐次调出来的，改动前先看 `AnimCurves.kt` 的注释。
6. **新增弹层先过 §4 选型表**，别按「哪个能传这几个参数就用哪个」挑：贴底 sheet 与锚定小菜单是两种形态，
   `ChoiceDialogSpec` 漏传 `anchor` 也只是静默退化，编译和运行都不报错。
7. **行尾箭头跟着形态走**（§4 箭头表）：原地弹窗 / 原地展开用 ↓，进二级页或推进式动作才用 →，点了立即执行就不画。
8. **卡里不写解释性注释行**：卡内的 `CardNote`、「一行一条」「只影响文字，图片一律照发」这类说明文字，
   只要上面那行副标题或分区标题已经表达过，就是重复噪音（2026-11 黑白名单页被明确否掉）。
   语义写进 **行副标题**，卡内只留能点的行 —— 一行字加一句解释凑成两行的卡，就是没想清楚副标题该写什么。
9. **卡内不画分隔线**：同一张 `Card` 里放多行也**一律不加** `HorizontalDivider`/`VerticalDivider`，分层只靠
   「拆成多张卡 + 行本身的高度」。判断依据是「这个仓里别的页面有没有」—— 全仓唯一的分隔线曾在黑白名单页出现
   （0.5dp、`start = 16.dp`，从卡内缩进处起笔像一条分叉），被明确否掉（2026-11）。
   需要把「列表」和「动作行」分开时，拆成两张 `Card(modifier = Modifier.cardPadding())`，卡与卡之间补一行
   `Spacer(Modifier.height(12.dp))` —— 这是本仓既有口径（`ImgLabelRecordScreen.kt:517`、`:587`），不补就会贴成一整块。
10. **输入框只有两种**：页内搜索走 `AppSearchField`（§9），弹层输入走 `TextInputDialogSpec`。
    别在页面里直接写 `BasicTextField` —— 各写各的会漏 `cursorBrush`（深色主题下光标纯黑看不见）、漏 `ImeAction.Search`，
    内边距与字号也会各飘各的（2026-11 记录页与选择会话页就是三份分叉实现）。
11. **需要打字才能改的值，一律「一行入口 + 弹层输入」**：行上只放当前值（或空态灰字），点行进弹层改，
    清空这类值走二次确认。页面上不常驻输入框 —— 常驻输入框会把整页撑高（黑白名单页曾有一块 260dp 的文本框），
    而且「框里的字」和「已保存的字」是两件事，用户以为改完就生效（2026-11 重命名页与页脚文字都按这条改的）。
    唯一例外是搜索框：它的值不落地、不影响配置，见 §9。

---

## 8. 已知坑速查

| 现象 | 根因 | 出处 |
| --- | --- | --- |
| 点下拉/弹窗项闪退 | Miuix 弹层收起动画与同步写配置打架 | §4 |
| 手势条是一条纯色 | `navigationBarContrastEnforced` 被 androidx 打开 | §6 |
| 最近任务里还是旧图标 | `TaskDescription` 是首次创建时缓存的，不跟 mipmap 变 | `SettingsActivity.syncTaskDescription()` |
| 换语言后界面没变 | `attachBaseContext` 里没包装 Context | §6 |
| 二级页返回时闪白 | `SubScreen` 被裁剪 | `Screens.kt` 顶部注释 |

---

## 9. 输入框与搜索框

全 App 的搜索框只有一处实现：`ui/AppSearchField.kt` 的 `AppSearchField`。
**页内搜索一律调它**，不要再自己拼 `BasicTextField` + `decorationBox` —— 这个仓曾经有三份各写各的：
一份竖向内边距 10dp、一份 12dp；一份传了 `keyboardOptions`、一份漏了；**三份都没传 `cursorBrush`**，
而 `BasicTextField` 的默认光标是纯黑，深色主题下等于没有光标。差异不是设计选择，是抄骨架时漏参数。

### 9.1 形态

| 项 | 值 | 为什么 |
| --- | --- | --- |
| 圆角 | 12dp | 与弹层输入框同一个数，进出弹层不跳 |
| 底色 | `surfaceContainerHighest`，0.9；聚焦时满色 1.0 | 卡已经是面，输入框只是卡上的一块凹处，不画描边 |
| 高度 | 固定 44dp | 卡里每行等高；让它随字号浮动，切语言就串版 |
| 壳内左内边距 | 12dp | 见 9.2 |
| 字号 / 色 | 页内 15sp、`onSurface`；占位 `onSurfaceVariantSummary` 同字号 | 与 `BasicComponent` 摘要一致，页内不出现第三档字号 |
| 光标 | `cursorBrush = SolidColor(primary)` | 硬约束，不是装饰；不传就是纯黑看不见 |
| 清空 | 值非空时右端 40dp 触控区，`AppIcons.Close` 细 ✕，`onSurfaceVariantActions` | 与行尾 ✕、＋ 同一套几何（见 §4 图标表），✕ 在这里是「清掉输入」不是批毁动作，所以不用红 |
| 键盘 | `singleLine` + `ImeAction.Search`，搜索键只收键盘 | 结果是边打边筛，不需要「再确认一次」 |
| 行数限制 | 无 | 搜索词不该被截断；弹层的 `maxChars` 是另一套语义 |

### 9.2 页内怎么摆（不进卡）

**搜索框不进 `Card`。** 卡自己有 `surfaceContainer` 底色，搜索框也有一层底色 —— 两层壳叠起来就是
「框套框」，边界发脏；卡的内边距与框的圆角也对不齐。直接放在页面上：

```kotlin
AppSearchField(
    // ...
    modifier = Modifier.padding(horizontal = CardHMargin, vertical = 4.dp),
)
```

- 左右 `CardHMargin`（12dp）= 与同页卡同宽，视觉上是一条「更浅的卡」；
- 上下 4dp：框高 44dp，与周围卡的 8 / 12dp 间距不打紧；
- 壳自带 12dp 左内边距 → 文字距屏幕 24dp。与卡内行标题（28dp）差 4dp，这是刻意的：
  框是独立控件，对齐对象是卡的边缘，不是卡里的标题。**不要再包一层 `Column`/`Row` 只为了给 padding。**

**搜索不要和别的东西同框**：搜索是「筛」，时间范围 / 排序是「条件」，塞进一张卡里看着像同一个控件的两半，
卡里再套框又多一层壳。记录页现在的摆法是：`SmallTitle(筛选)` → 裸搜索框 → 时间范围卡（各占一层）。

### 9.3 空结果三件套

搜不到东西时，三件事缺一不可：

1. **命中数或空态**要看得见 —— 别让页面只剩一片空白，用户会以为名单/记录被删了；
2. **空态文案要区分「本来就没有」和「搜索筛掉了」**：`record_empty_body` 对 `record_empty_search`、
   `scope_empty_body` 对 `scope_empty_search`。两句共用一句，用户就分不清是数据没了还是词打错了；
3. **退出搜索的路径**：清空按钮 + 键盘搜索键 + （记录页）时间范围回到「全部」。

搜索词只 `remember`，**不持久化**：下次进来是干净的全量视图，不会「一进来就少一半」。

### 9.4 弹层输入框不在本节

`AppDialogWidgets.kt` 里的弹层输入框有自己的壳（16sp、`maxChars`、错误红字），
它和页内搜索是两种东西，**不要把 `AppSearchField` 塞进弹层** —— 会套两层底、字号还会打架。

```kotlin
// 页内搜索的标准写法
AppSearchField(
    value = query,
    onValueChange = { query = it },
    hint = searchHint,            // 兼作无障碍标签，写清「能搜什么」
    clearLabel = searchClearLabel, // strings.xml 的 search_clear，全 App 共用
    modifier = Modifier.padding(horizontal = CardHMargin, vertical = 4.dp),
)
```

### 9.5 反面清单

- 把搜索框放进 `Card`（卡底色 + 框底色 = 框套框，2026-11 两页都犯过）；
- 把搜索框与条件行（时间范围 / 排序）塞进同一张卡；
- 自己 `BasicTextField` + `decorationBox`（本仓已有两处前科，2026-11 清理）；
- 省 `cursorBrush`；
- 省 `keyboardOptions`/`keyboardActions`（键盘上会出现「换行」而不是「搜索」）；
- 清空按钮用 `AppIcons.Add`/实心图标或红色；
- 给清空按钮的图标塞 `Modifier.size(...)`（尺寸归图标自己，见 §4）；
- 搜不到时一句 `Text("无")` 了事，不留退出路径；
- 把搜索词写进配置（下次进来是筛过的状态）。
