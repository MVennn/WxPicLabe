# 版本规范

> **一句话**：版本号只有两个落点（`app/build.gradle.kts` 的 `versionName` 与 `versionCode`），怎么进位、什么时候才能动，看本篇。

## 1. 命名

- 三段式 `MAJOR.MINOR.PATCH`：`0.1.0`。
- 进位：
  - `PATCH`：修缺陷、内部重构、文档与门禁类改动；
  - `MINOR`：新增用户可感知的能力（文件名格式、发送节奏、开关）；
  - `MAJOR`：不兼容的大改（例如换宿主、重写发送链路）。
- Release tag 用 `v` 前缀（`v0.1.0`）；`docs/release-notes/` 里的文件名**不带 v**（`0.1.0.md`）。

## 2. 版本号只在这里

| 位置 | 含义 |
| --- | --- |
| `app/build.gradle.kts` → `versionName` | 展示版本，对应 tag |
| `app/build.gradle.kts` → `versionCode` | 单调递增整数，每次发版 +1 |
| `CHANGELOG.md` 段标题 | 与 `versionName` 一致 |

## 3. 发版才动版本号

- 未发版前改代码**不动** `versionName` / `versionCode`。
- 只有明确说「发版」时才：升版本号 → 写 `CHANGELOG.md` → 出 `docs/release-notes/<版>.md` → 打 tag → 发 Release。
- Release 说明第一行是加粗的一句话总结，第二行固定反馈入口，正文按「新增 / 调整 / 修复 / 优化 / 移除」写全称动词。
