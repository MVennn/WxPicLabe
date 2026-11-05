# 参与贡献

> **一句话**：本仓是微信发图带文件名的 LSPosed 模块；改动前先读
> [docs/DOC-RULES.md](docs/DOC-RULES.md)（文档规则）与 [docs/BUILD.md](docs/BUILD.md)（构建），本文只讲「怎么提」。

本模块只做**发送时的消息组织方式**增强，不碰账号、不自动操作、不采集数据。建议与 PR 请落在这条线内。

## 提 Issue

- **不生效 / 崩溃 / 名字贴错图** → 用「问题反馈（Bug）」模板，**务必贴 LSPosed 日志**：

  ```bash
  adb shell su -c "grep WxPicLabel /data/adb/lspd/log/modules_*.log | tail -50"
  ```

- **想要新的处理方式**（例如文件名格式、发送节奏）→ 用「功能建议」模板，先说场景。

排错步骤见 [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。

## 提 Pull Request

1. 从 `main` 开分支，一条改动一个分支。
2. 改动前过一遍 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 的降级约定：**任何 Hook 失败都只能降级，不能崩微信**。
3. 提交前至少跑过：

   ```bash
   build.cmd                     # 能编出 APK
   python tools/check-docs.py    # 文档门禁
   python tools/check-secrets.py # 凭据门禁
   ```

4. PR 描述里写清「怎么验的」——真机没验就明说没验。

## 几条硬规矩

| 规矩 | 在哪定义 |
| --- | --- |
| 每条规则只在一篇文档里定义，别处只留链接 | [docs/DOC-RULES.md](docs/DOC-RULES.md) §2 |
| 发版才动 `versionCode` / `versionName` | [docs/VERSIONING.md](docs/VERSIONING.md) |
| 用户可感知的改动要进 [CHANGELOG.md](CHANGELOG.md) | [docs/DOC-RULES.md](docs/DOC-RULES.md) §5 |
| 修缺陷必须补「现象 → 根因 → 怎么避」 | [docs/PITFALLS.md](docs/PITFALLS.md) |

## 免责声明

提交 Issue / PR 即表示你已知悉：本模块会 Hook 微信，可能违反其用户协议并带来账号风险，相关风险由你自己承担。
全文见 [docs/DISCLAIMER.md](docs/DISCLAIMER.md)。
