<div align="center">

<img src="https://raw.githubusercontent.com/MVennn/WxPicLabe/main/assets/logo.png" width="128" alt="WxPicLabe">

# WxPicLabe

**微信 · 发图带文件名**（非官方，与腾讯及其关联方无任何隶属、合作或授权关系）

图-文-图-文，文件名跟着图片走

![版本](https://img.shields.io/github/v/release/MVennn/WxPicLabe) ![CI](https://github.com/MVennn/WxPicLabe/actions/workflows/ci.yml/badge.svg) ![源码仓库](https://img.shields.io/badge/源码仓库-GitHub-181717?logo=github&logoColor=white) ![问题反馈](https://img.shields.io/badge/问题反馈-Issues-181717?logo=github&logoColor=white) ![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-5C6BC0) ![License](https://img.shields.io/badge/License-Apache--2.0-blue)

</div>

---

## 这是什么

在微信里一次选多张图发送时，模块按**选图顺序**给每张图补一条引用消息，引用文字就是这张图的文件名（例如 `贵州茅台600519`），形成 图-文-图-文 交替；点引用条能跳回对应原图。

不联网、不上传、不申请任何 Android 权限，只改变发送时的消息组织方式，不修改聊天记录与账号数据。

## 环境要求

- 安卓 8.0+（minSdk 26）
- **较新的 LSPosed**（现代 Xposed API 102）。过旧的 LSPosed、太极 / LSPatch / EdXposed 等实现会**静默不加载**（已启用但零日志、零生效）
- 微信**不锁版本**：模块按类名与字段特征定位，宿主升级后可能需要校准 Hook 点（见 Issues 与 [排错手册](https://github.com/MVennn/WxPicLabe/blob/main/docs/TROUBLESHOOTING.md)）

## 安装

1. [Releases](https://github.com/MVennn/WxPicLabe/releases) 下载最新 APK 并安装
2. LSPosed 管理器 → 模块，勾选「微信发图带文件名」
3. 作用域里勾上**微信**（模块已静态声明默认作用域，一般会自动勾好）
4. 强制停止微信，再打开一次
5. 多选图片发送；LSPosed 日志过滤 tag `WxPicLabel`，看到 `selection via key=...` 说明选图 Hook 命中

## 反馈

遇到问题、想让某个页面更顺手，到 [Issues](https://github.com/MVennn/WxPicLabe/issues) 反馈。请带上：

- 微信版本号（微信 → 我 → 设置 → 关于）
- 模块版本号、安卓版本、机型
- 复现步骤，以及预期为什么样、实际什么样
- 日志：`adb shell su -c "grep WxPicLabel /data/adb/lspd/log/modules_*.log | tail -50"`

## 免责声明

本项目仅供学习与个人使用。

- 不收集、不上传、不存储任何个人信息，不含统计分析、广告或数据回传组件
- 不修改微信安装包、不绕过任何鉴权，只改变发送时的消息组织方式
- 使用第三方模块可能违反微信用户协议并带来账号风险，相关后果由使用者自行承担
- 完全免费开源，不直接或间接获取经济利益

「微信」「WeChat」商标与标识的权利归腾讯及其关联方所有。本项目与之不存在任何隶属、合作、授权、认可或支持关系。

完整声明见 [docs/DISCLAIMER.md](https://github.com/MVennn/WxPicLabe/blob/main/docs/DISCLAIMER.md)。

## 开源鸣谢

| 项目 | 用途 |
| --- | --- |
| [LSPosed](https://github.com/LSPosed/LSPosed) · [libxposed](https://central.sonatype.com/artifact/io.github.libxposed/api) | 模块框架与现代 Xposed API（API 102） |
| [Android Gradle Plugin](https://developer.android.com/build) | 构建 |

## 许可证

本项目以 [Apache License 2.0](https://github.com/MVennn/WxPicLabe/blob/main/LICENSE) 发布。
第三方组件的版权与许可证声明见 [NOTICE](https://github.com/MVennn/WxPicLabe/blob/main/NOTICE)。
