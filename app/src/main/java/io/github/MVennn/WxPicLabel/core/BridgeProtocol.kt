package io.github.MVennn.WxPicLabel.core

/**
 * 桥协议的唯一常量源：模块 App ↔ 目标进程（微信）之间走的那条 ContentProvider。
 *
 * 改前 authority 与两条 path 在四处各写一份字面量（AndroidManifest、本 Provider、
 * [io.github.MVennn.WxPicLabel.feature.imglabel.RecordExporter]、[io.github.MVennn.WxPicLabel.feature.imglabel.FriendExporter]），
 * 任何一处改漏都表现为「回传静默丢失」——没有异常、没有日志，只是记录页一直空。
 * 现在只有这一份定义，manifest 里的 [AUTHORITY] 仍需手写（XML 认不了常量），
 * 但代码侧四处都从这里取。
 *
 * 版本策略（与 TBridge / GS 广播共同的约定）：**键名不加版本，载荷加**。
 *  - records 文档顶层带 `"v"`，[SCHEMA] 是当前写出的值
 *  - 读侧遇到 `v > SCHEMA` 不猜结构，按 lenient 解析并把 path 标成 `unsupported`
 *  - 老的模块 App 对任意 path 都回 friends 三列，新调用方看到 `friends_json`
 *    就知道对端还没有 `/status`
 */
object BridgeProtocol {

    /** 桥的 authority；AndroidManifest.xml 里的 provider android:authorities 必须与它一致。 */
    const val AUTHORITY = "io.github.MVennn.WxPicLabel.bridge"

    /** 好友名单回传：每片 ≤200 条的「条目 + 尾逗号」，由 Provider 套外壳。 */
    const val PATH_FRIENDS = "friends"

    /** 发送记录回传：整份文档的字符切片，Provider 只按序拼接、不改结构。 */
    const val PATH_RECORDS = "records"

    /**
     * 只读探针：返回 schema / 模块版本 / 回传时间 / 能力开关 / 快照规模。
     *
     * 用途是让调用方（包括以后接进来的联动模块）先问一次「你对端是哪一版、支持什么」，
     * 而不是靠「调一下看会不会崩」来试探。
     */
    const val PATH_STATUS = "status"

    /** 载荷 schema 版本：records 文档的 `"v"` 与 /status 的 `schema` 列都取它。 */
    const val SCHEMA = 2
}
