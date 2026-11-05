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
| `AppDialogSpec.kt` | 弹层规格：`ChoiceDialogSpec` / `ConfirmDialogSpec` / `AgreementDialogSpec` |
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

三种弹层规格：

| 规格 | 用途 | 关键约束 |
| --- | --- | --- |
| `ChoiceDialogSpec` | 主题 / 语言这类多选一 | 选中项打勾，点空白关闭 |
| `ConfirmDialogSpec` | 「重置模块」这类破坏性操作 | 必须写清**范围**（见 NAMING §3 例外条） |
| `AgreementDialogSpec` | 首次启动的免责声明门槛 | **不可关闭**，返回键只吞不放行 |

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

---

## 8. 已知坑速查

| 现象 | 根因 | 出处 |
| --- | --- | --- |
| 点下拉/弹窗项闪退 | Miuix 弹层收起动画与同步写配置打架 | §4 |
| 手势条是一条纯色 | `navigationBarContrastEnforced` 被 androidx 打开 | §6 |
| 最近任务里还是旧图标 | `TaskDescription` 是首次创建时缓存的，不跟 mipmap 变 | `SettingsActivity.syncTaskDescription()` |
| 换语言后界面没变 | `attachBaseContext` 里没包装 Context | §6 |
| 二级页返回时闪白 | `SubScreen` 被裁剪 | `Screens.kt` 顶部注释 |
