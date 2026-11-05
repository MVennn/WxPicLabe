# tools/

> **一句话**：仓内的门禁与辅助脚本总表；每个脚本一节，写清用途、用法与退出码。

## check-docs.py

- **用途**：文档门禁。检查 `docs/*.md` 是否都被 `docs/README.md` 索引、每篇是否有「一句话」抬头、索引与 README 的相对链接是否可解析、文件名是否全大写 + 连字符。
- **用法**：仓根执行 `python tools\check-docs.py`。
- **退出码**：0 通过；1 有失败项（逐条打印 `[FAIL] …`）。

## check-secrets.py

- **用途**：凭据扫描。查私钥头、AWS/GitHub/Slack/Google 令牌前缀、带口令的连接串、赋值型凭据；跳过 `.git/.work/.gradle-home/.android-home/.tmp/build/dist` 与二进制。
- **用法**：仓根执行 `python tools\check-secrets.py`。
- **退出码**：0 干净；1 有命中（打印 `文件:行号:[规则] 脱敏片段`）。

## 约定

- 零第三方依赖，只用 Python 3 标准库；
- 只读，不改仓库文件，不联网；
- 新增脚本要同步更新本文件，否则视同没有文档。
