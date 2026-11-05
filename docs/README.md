# 文档索引

> **一句话**：docs/ 下每篇文档的登记表，新增文档必须先在这里加一行，否则 `check-docs.py` 判失败。

命名一律 **全大写 + 连字符**，唯一例外是本索引 `README.md`。每篇开头 10 行内必须有 `> **一句话**：…` 抬头。

## 一、架构与实现

| 文档 | 一句话定位 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 模块架构：四个 Hook 点、批次与绑定规则、降级策略、待适配清单 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 排错手册：按「现象 → 步骤 → 原因 / 解法」组织 |
| [SEND-DESIGN.md](SEND-DESIGN.md) | 发送方案：内部发送面（f9/g9/b3/MsgQuoteItem）实测清单与 A/B 方案对比 |
| [IMG-LABEL-SEND-SETTINGS.md](IMG-LABEL-SEND-SETTINGS.md) | 发送设置：同一张图重复发送追加 +2/+3 的计数存储、开关层级与改动清单 |

## 二、流程与规范

| 文档 | 一句话定位 |
| --- | --- |
| [BUILD.md](BUILD.md) | 构建：JDK/Gradle/AGP/SDK 版本、build.cmd 的缓存重定向、产物与重建 |
| [VERSIONING.md](VERSIONING.md) | 版本命名与晋升：三段式进位、版本号只在哪、发版才动 |
| [PITFALLS.md](PITFALLS.md) | 踩坑正本：每条「现象 → 根因 → 怎么避」 |
| [DOC-RULES.md](DOC-RULES.md) | 文档规则：分类、单一正本、命名、抬头、索引、断链、改名三步 |

## 三、法律与声明

| 文档 | 一句话定位 |
| --- | --- |
| [DISCLAIMER.md](DISCLAIMER.md) | 免责声明全文（README 里是摘要） |
