# 版本规范

> **一句话**：版本号只有两个落点（`app/build.gradle.kts` 的 `versionName` 与 `versionCode`），怎么进位、什么时候才能动，看本篇。

## 1. 命名

- 三段式 `MAJOR.MINOR.PATCH`：`0.1.0`。
- 进位：
  - `PATCH`：修缺陷、内部重构、文档与门禁类改动；
  - `MINOR`：新增用户可感知的能力（文件名格式、发送节奏、开关）；
  - `MAJOR`：不兼容的大改（例如换宿主、重写发送链路）。
- **Release tag 用 `{versionCode}-{versionName}`**（`4-0.4.0`），与姊妹仓 GSAuxiliary 一致 ——
  这样 tag 自带构建号，回退与覆盖安装时一眼能看出先后，也与 APK 资产名同形。
  （0.3.0 及以前的 tag 是 `v0.3.0`，属历史写法，不再改写。）

## 1.1 Release 的三件命名

| 东西 | 写法 | 例子 |
| --- | --- | --- |
| tag | `{versionCode}-{versionName}` | `4-0.4.0` |
| Release 标题 | `v{versionName}`；版本自带定位时接一句：`v{versionName} · <一句定位>` | `v0.4.0` / `v1.0.0 · 功能重构升级` |
| 资产名 | `WxPicLabel_{versionCode}-{versionName}.apk` | `WxPicLabel_4-0.4.0.apk` |

标题里塞**功能清单**（`v0.4.0 · 新增某某 / 修复某某`）是**错的**：版本列表靠 tag 与标题对齐，
标题一长，列表里每一行都不一样长，且描述与正文第一行重复。

允许的是**一句版本定位**（1.0.0 起）：只在「这一版是什么性质」一句话说得清时写，
例如 `v1.0.0 · 功能重构升级`；说不清性质就不写，回到纯 `v{versionName}`。

## 2. 版本号只在这里

| 位置 | 含义 |
| --- | --- |
| `app/build.gradle.kts` → `versionName` | 展示版本，对应 tag |
| `app/build.gradle.kts` → `versionCode` | 单调递增整数，每次发版 +1 |
| `CHANGELOG.md` 段标题 | 与 `versionName` 一致 |

## 3. 发版才动版本号

- 未发版前改代码**不动** `versionName` / `versionCode`。
- 只有明确说「发版」时才：升版本号 → 写 `CHANGELOG.md` → 出 Release 说明（本仓当前无 `docs/release-notes/` 子目录，Release 正文用 `<REPLACE-RELEASE-BODY>` 占位或随 GitHub Release 附正文）→ 打 tag → 发 Release。
- Release 说明三段固定结构（与 GSAuxiliary 同口径）：
  1. 第一行：**加粗的一句话总结**（用户视角，说这一版最值得升级的那件事）；
  2. 第二行：固定反馈入口（Issues 链接 + 要带什么）；
  3. `## 本版内容` 正文：按分区（发送 / 发送设置 / 界面 / 文档）分组，
     每条以「新增 / 调整 / 修复 / 优化 / 移除」开头，写全称动词。
