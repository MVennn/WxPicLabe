# 文档索引

> **一句话**：docs/ 下每篇文档的登记表，新增文档必须先在这里加一行，否则 `check-docs.py` 判失败。

命名一律 **全大写 + 连字符**，唯一例外是本索引 `README.md`。每篇开头 10 行内必须有 `> **一句话**：…` 抬头。

## 一、架构与实现

| 文档 | 一句话定位 |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 模块架构：四个 Hook 点、接管链路与现场提示、重命名与记录通道、降级策略、待适配清单 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 排错手册：按「现象 → 步骤 → 原因 / 解法」组织 |
| [SEND-DESIGN.md](SEND-DESIGN.md) | 发送方案：内部发送面（f9/g9/b3/MsgQuoteItem）实测清单与 A/B 方案对比 |
| [IMG-LABEL-SEND-SETTINGS.md](IMG-LABEL-SEND-SETTINGS.md) | 发送设置：同一张图重复发送追加 +2/+3 的计数存储、开关层级与改动清单 |
| [IMG-LABEL-CHAT-SCOPE.md](IMG-LABEL-CHAT-SCOPE.md) | 会话白名单：只对勾选的好友/群聊生效，talker 取法与好友列表抓取通道 |
| [ALBUM-FILENAME-BUTTON.md](ALBUM-FILENAME-BUTTON.md) | 相册「文件名」开关：原图右边的注入点、布局避坑、显隐规则与三道闸 |

## 二、流程与规范

| 文档 | 一句话定位 |
| --- | --- |
| [BUILD.md](BUILD.md) | 构建：JDK/Gradle/AGP/SDK 版本、build.cmd 的缓存重定向、产物与重建 |
| [CONFIG-MEMORY.md](CONFIG-MEMORY.md) | 配置记忆：功能开关出厂默认关闭、首次连接补齐默认键、重置/备份边界、免责声明为什么不进配置 |
| [VERSIONING.md](VERSIONING.md) | 版本命名与晋升：三段式进位、版本号只在哪、发版才动 |
| [PITFALLS.md](PITFALLS.md) | 踩坑正本：每条「现象 → 根因 → 怎么避」 |
| [DOC-RULES.md](DOC-RULES.md) | 文档规则：分类、单一正本、命名、抬头、索引、断链、改名三步 |

## 三、界面与文案

| 文档 | 一句话定位 |
| --- | --- |
| [UI.md](UI.md) | 设置界面总览：文件职责、页面结构、弹层体系、改 UI 的硬规则与已知坑 |
| [LIQUID-GLASS.md](LIQUID-GLASS.md) | 液态玻璃材质规范：两档光效 + 纯色兜底的设计正本、降级链、逐表面参数矩阵 |
| [NAMING.md](NAMING.md) | 文案与命名规范：开关不写「启用」、新增类写三段式、副标题只说位置 |

## 四、法律与声明

| 文档 | 一句话定位 |
| --- | --- |
| [DISCLAIMER.md](DISCLAIMER.md) | 免责声明全文（README 里是摘要） |
