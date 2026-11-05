# 发送方案（图片 + 文件名 + 引用）

> **一句话**：微信 8.0.80 的内部发送面已经摸清（消息实体 `f9`、存储 `g9`、文本封装 `b3`、引用模型 `MsgQuoteItem`），要真正做到「一图一行字 + 引用」，路径是「探测消息表 + 内部 API 发送」，而不是让微信自己批量发图。

## 问题

当前实现让微信自己把整批图发出去，模块只是事后补文字：

- 图先全部落地，字再逐条补 —— 单条文字走 UI（setText → 点「发送」）约 700ms，追不上微信插消息的速度；
- 引用做不了 —— 引用消息必须带被引消息的 `svrid`/`msgId`，UI 层拿不到。

## 已确认的内部结构（静态分析 8.0.80 的 18 个 dex）

| 角色 | 实际类 / 方法 | 说明 |
| --- | --- | --- |
| 消息实体 | `com.tencent.mm.storage.f9` | `<init>()V` + 13 个 `(String)V` setter + `getMsgId()J` / `getCreateTime()J` |
| 存储/发送 | `com.tencent.mm.storage.g9#Zb(Lcom/tencent/mm/storage/f9;)J` | 文本发送就是 `new f9 → 设字段 → g9.Zb(msg)` |
| 文本封装 | `com.tencent.mm.plugin.messenger.foundation.b3#a(String,String)V` | 内部实现即上面那条链：`t1(talker)`、`setType(1)`、`a1(content)`、`g9.Zb` |
| 按 msgId 取消息 | `com.tencent.mm.storage.g9#U3(String,long)Lcom/tencent/mm/storage/f9;` | 引用构造时用它回查被引消息 |
| 引用模型 | `com.tencent.mm.plugin.msgquote.model.MsgQuoteItem` | `Parcelable`，含被引消息的快照字段 |
| 引用构造 | `com.tencent.mm.ui.chatting.viewitems.qc#b(Z,String,J,String,MsgQuoteItem,String)Lcom/tencent/mm/storage/f9;` | 返回可直接 `Zb` 的引用消息体 |

顺带排除的：`com.tencent.mm.modelmulti` 整个包不存在；`ui/chatting` 里没有 `sendImg`/`sendImage` 之类命名的方法；`i24/a3` → `u24/d0` 那条链属于拍摄/录制插件，不是聊天发图。

## 方案对比

### A. 仍用微信相册，但改成「探测消息表 + 内部 API 补文字」（推荐）

1. 保留 `AlbumPreviewUI#onActivityResult`，照旧拿到 `talker` 与图片路径列表；
2. 让微信自己把图发出去；
3. 每 50~100ms 查一次 `g9`：当前 talker 下 `type==3` 的新消息按 `createTime` 递增出现，第 k 条对应第 k 张图；
4. 每探测到一张图，立刻用 `b3.a(talker, 文件名)` 发文字（内部调用约 10~50ms，不再是 700ms）；
5. 需要引用时改用 `qc.b(...)`，被引消息用第 3 步拿到的 `msgId` 经 `g9.U3(...)` 回查。

- 成本：低。只换「怎么发文字」，相册与路径提取不动。
- 风险：`b3.a` 是内部方法，需实测；若它只入库不发送（服务端没收到），再退到 `g9.Zb` + 发送队列。
- 回退：`b3.a` 调不通就退回现在的 UiSender，一行开关切换。

### B. 完全不用微信相册：模块自选图 + 内部 API 发图

1. 模块自己做选择器（`ACTION_OPEN_DOCUMENT` 或自建预览页）；
2. 逐张构造图片消息：`new f9` → `setType(3)` → 设 `imgPath` 与图片 content XML → `g9.Zb`；
3. 每张之后立即发文件名 / 引用。

- 成本：高。图片消息的 content 是带 md5、长度、CDN 字段的 XML，上传还要走 `NetSceneUploadMsgImg` 一类流程，手搓容易被服务端拒。
- 除非 A 被证明不可行，否则不做。

## 验收（A 方案）

1. `setprop` 触发 / 真实选图两种入口都走一遍；
2. 日志必须出现「探测到第 k 张图 → 立即发第 k 条文字」，且时间差 < 200ms；
3. 聊天界面：图与名字交替出现；
4. 引用版：每条文字上方带该图缩略图，点击能跳回图片消息。

## 待确认（需用户拍板）

- 引用做成「每条文件名引用对应图片」，还是先只做「一图一行字」的纯文字，引用留到第二阶段？
- 若 `b3.a` 实测只入库不发送，是否接受「先入库、靠微信发送队列补发」的绕行方案？
