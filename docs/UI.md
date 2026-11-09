# 设置界面总览

> **一句话**：模块设置界面（Compose + Miuix）的文件分工、页面结构、弹层体系与改 UI 时必须守的硬规则；动 `ui/` 之前先读本篇。

材质表现见 [LIQUID-GLASS.md](LIQUID-GLASS.md)，用户可见文案见 [NAMING.md](NAMING.md)，
Hook 与数据链路见 [ARCHITECTURE.md](ARCHITECTURE.md)。

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

**二级页**

| 文件 | 职责 |
| --- | --- |
| `ImgLabelSendScreen.kt` | 发送设置：重复图片追加次数、清空发送记录 |
| `MarkdownText.kt` | 免责声明 / 开源声明的 Markdown 渲染 |

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
   ├─ HomeScaffold            一级首页（常驻，作为二级页下方的底衬）
   │  ├─ 顶栏（玻璃或纯色）
   │  ├─ Tab 内容：功能 / 概览 / 设置 / 关于
   │  └─ Dock（两种形态）
   └─ SubScreen               二级页（按进度右滑进出）
      ├─ 免责声明
      ├─ 开源声明
      └─ 发送设置
```

**只有两层**。本仓的二级页都是只读长文，没有编辑态也没有三级页，
所以不保留 GSAuxiliary 那套「二级 / 三级各一条进度」的双层动画 —— 多一层是纯开销。

**新增二级页三步**（漏一步 `when` 就编译失败，这是刻意的）：

1. `Route` 加一个 `data object`；
2. `RouteSaver` 的 `save` / `restore` 各加一个分支；
3. `MainScreen` 的 `when (shownSub)` 里挂上页面。

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
| `TextInputDialogSpec` | 贴底 sheet，与 Confirm 同壳，随键盘上抬 | 就地改一个值：改文件名、改「第几次」 | 只从行内动作菜单 / 按钮进入；**不要拿它做行内选值**（那属于 Choice） |
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

---

## 8. 已知坑速查

| 现象 | 根因 | 出处 |
| --- | --- | --- |
| 点下拉/弹窗项闪退 | Miuix 弹层收起动画与同步写配置打架 | §4 |
| 手势条是一条纯色 | `navigationBarContrastEnforced` 被 androidx 打开 | §6 |
| 最近任务里还是旧图标 | `TaskDescription` 是首次创建时缓存的，不跟 mipmap 变 | `SettingsActivity.syncTaskDescription()` |
| 换语言后界面没变 | `attachBaseContext` 里没包装 Context | §6 |
| 二级页返回时闪白 | `SubScreen` 被裁剪 | `Screens.kt` 顶部注释 |
