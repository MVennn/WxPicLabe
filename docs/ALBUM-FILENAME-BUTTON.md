# ALBUM-FILENAME-BUTTON

> **一句话**：相册选图页在「原图」右边注入一个同款开关「文件名」，勾着才补文件名、不勾就走微信原生发送；状态只由手动点击改变。

## 1. 需求

- 位置：微信相册选图页（`com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI`）底部「原图」右边，原图左移。
- 观感：跟随「原图」组件的设计语言（同字号 / 同颜色 / 同圆点图标 / 同水平基线）。
- 显隐：跟随模块配置 —— 总开关 `feature_img_label` 关 → 完全不注入；「仅选中的会话生效」开着时，名单外的会话不注入。
- 语义：勾选 = 这一批补文件名；不勾 = 微信原生发送（图照发、不带文字）。
- 持久化：退出相册、重启微信都不复位，只有手动点它才变。

## 2. 实证：微信 8.0.80 的「原图」不是 CheckBox

静态取证（`.work/evidence/agent-album-ui.md`）+ 运行时视图树（`debug.wxpiclabel.uidump=1`）一致：

```text
RelativeLayout (底栏, gravity=bottom, 高 72px)
 ├ LinearLayout  w5x  「发送后合并展示」行（默认 GONE，勾原图时变 INVISIBLE —— 占位 68px）
 ├ LinearLayout  r0   合并提示行（默认 GONE）
 ├ RelativeLayout km6  原图行  rules= BELOW(r0) + CENTER_IN_PARENT
 │   ├ WeImageView km5  26×72 圆点图标（勾选态换 src：raw/radio_checked ↔ raw/radio_off_white）
 │   ├ TextView    km9  「原图」 rules= RIGHT_OF(km5) + CENTER_VERTICAL
 │   └ TextView    km8  原图体积提示（默认 GONE）
 └ （本模块注入）LinearLayout  文件名行
```

两条结论直接决定实现方式：

1. **不能克隆参数**：原图行是 `CENTER_IN_PARENT`，照抄 LayoutParams 插进同一个父容器，两个视图会叠在同一位置（第一版就是这么叠的）。
2. **不能跟底栏一起居中**：勾上「原图」后底栏被 w5x 撑到 140px，居中的子视图会整体上跳 34px（第一版点一下原图，按钮就往上跑）。

## 3. 实现

`feature/imglabel/AlbumMemoryToggle.java`：

| 环节 | 做法 | 为什么 |
| --- | --- | --- |
| 注入时机 | 挂 `AlbumPreviewUI` 的**构造器**，300ms 后首试，找不到「原图」就每 400ms 重试，最多 6 次 | 相册页自己没声明 onCreate/onResume（都在混淆父类链上）；挂 `Activity#onResume` 实测零命中 |
| 找原图 | 视图树里按文本精确匹配「原图」/「Full Image」 | 抗混淆、抗资源 id 漂移；文案来自微信语言设置 |
| 造行 | `LinearLayout[ImageView(自绘 DotIcon) + TextView("文件名")]`，字号/颜色/字体/内边距/行高照抄原图 | res/raw 下是 svg，只有微信自己的 SVG 渲染器能画；自绘圆点不依赖微信资源 |
| 定位 | `RIGHT_OF(原图行) + ALIGN_TOP/ALIGN_BOTTOM(原图行)`，`leftMargin = 12dp` | 上下都对齐原图行 → 底栏变高时两行一起走，不会各走各的 |
| 居中 | 布局后按实测宽度算 `dx = (底栏宽 - (原图宽 + 间距 + 本行宽)) / 2 - 原图行.left`，两行同时 `translationX` | 只动渲染属性，不改微信的 LayoutParams（微信自己会改原图行参数，硬改会被覆盖） |
| 点击 | 整行 clickable，点一次翻转状态并重绘图标 | 与微信「点整行切换」的手感一致 |

图标 `DotIcon`：未选 = 白色圆环（20dp，1.4dp 描边）；已选 = 微信绿 `#07C160` 实心 + 白色对勾。

## 4. 状态与闸门

| 项 | 值 |
| --- | --- |
| 存储 | 微信进程自己的 `SharedPreferences("wxpiclabel_state")`，键 `album_memory_on` |
| 默认 | `true`（保持「装上就能用」的历史行为，不勾才是用户主动选择） |
| 为什么不用 remote preferences | 目标进程侧只读，模块 App 跨 uid 不可写；勾选态必须能在微信里写 |
| 持久化 | `apply()` 落盘，退出相册 / 杀进程 / 重启微信都保持；只有点击会改 |

三道闸（任何一道不放行都退回微信原生发送）：

1. `featureEnabled()` —— 功能总开关（`feature_img_label`）；
2. `scopeAllowed(talker)` —— 会话白名单（`docs/IMG-LABEL-CHAT-SCOPE.md`）；
3. `memoryOn()` —— 本次新增的「文件名」勾选态。

闸门落点：接管入口 `on5.f#a` 拦截、兜底队列 `flushFallback`、兜底武装 `armFromParams`（未勾时**连文字都不算**，避免 `RepeatCounter` 白涨次数）。

## 5. 显隐规则

| 条件 | 结果 |
| --- | --- |
| `feature_img_label = false` | 不注入（用户明确要求：开关不开发，按钮不出现） |
| 总开关开 + 「仅选中的会话生效」关 | 所有会话都注入 |
| 总开关开 + 仅选中开 + 当前会话在名单 | 注入 |
| 总开关开 + 仅选中开 + 当前会话不在名单 | 不注入（那里功能本来就不生效） |
| 取不到会话 id（`GalleryUI_ToUser` 与 `Chat_User` 都拿不到） | 按放行处理，注入 —— 与 `ScopeGate` 口径一致 |

会话 id 取法：先 `AlbumPreviewUI` 的 Intent `GalleryUI_ToUser`，再扫 `ActivityThread.mActivities` 里活着的聊天页 `Chat_User`。

## 6. 验收

模拟器 emulator-5554（Android 12 / 微信 8.0.80 / 900×1600）：

| 验证点 | 结果 | 证据 |
| --- | --- | --- |
| 注入位置与观感 | ✅ 预览(1) \| ○原图 ○文件名 \| 发送(1) | 截图 `.work/adbtmp/u4crop.png` |
| 与原图不重叠 | ✅ 两行同一水平线、间距 12dp | 几何日志 `src=412,0,487,72 mine=502,0,600,72 dx=-56` |
| 勾原图后不上跳 | ✅ 底栏 72→140 时两行 box 同步（`src=…68,487,140` / `mine=…68,600,140`） | 同上日志 |
| 点击切换 | ✅ 每次点击一条 `filename toggle: true/false` | logcat |
| 不勾 → 不发文件名 | ✅ `fallback: memory off, drop 1`，聊天里只有图 | 截图 `.work/adbtmp/u5crop.png` |
| 退出相册保持 | ✅ 状态落 SharedPreferences，重开仍是上次的勾选 | 代码路径 |
| 开关关闭不注入 | ✅ `debug.wxpiclabel.albumtoggle=0` 时不出现 | 排障开关 |

## 7. 风险与回退

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 微信改底栏结构（原图行不再是 RelativeLayout / 文案变了） | 注入失败 | 全程 try/catch；找不到「原图」只打日志，界面与原生一致，功能不受影响 |
| 微信给底栏加子视图 | 位移计算仍按实测宽度，自动适配 | `onGlobalLayout` 每次布局重算 |
| 用户想彻底关掉 | 功能总开关一关即不注入 | 无需回退代码 |
| 注入本身出问题 | 想临时停 | `setprop debug.wxpiclabel.albumtoggle 0`（重启微信生效） |
