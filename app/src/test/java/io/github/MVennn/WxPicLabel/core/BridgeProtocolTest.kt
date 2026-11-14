package io.github.MVennn.WxPicLabel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥协议常量的回归钉。
 *
 * 这几行字面量是**跨进程**的：微信进程写进 Provider、模块 App 读出来，中间隔着
 * 老版本 APK。改坏一个字母不会编译报错，只会让回传静默丢失（记录页一直空、
 * 好友名单不更新），所以在这里钉死。
 */
class BridgeProtocolTest {

    @Test
    fun authorityMatchesManifest() {
        // AndroidManifest.xml 的 provider android:authorities 写的也是这个字面量，
        // 改这里必须同步改 manifest —— 单测盯不住 XML，靠这条注释与 PITFALLS 提醒。
        assertEquals("io.github.MVennn.WxPicLabel.bridge", BridgeProtocol.AUTHORITY)
    }

    @Test
    fun pathsAreFrozen() {
        assertEquals("friends", BridgeProtocol.PATH_FRIENDS)
        assertEquals("records", BridgeProtocol.PATH_RECORDS)
        assertEquals("status", BridgeProtocol.PATH_STATUS)
        // 三条 path 不能撞：撞了就是一条回传吃掉另一条的分片
        val all = setOf(
            BridgeProtocol.PATH_FRIENDS,
            BridgeProtocol.PATH_RECORDS,
            BridgeProtocol.PATH_STATUS,
        )
        assertEquals(3, all.size)
    }

    @Test
    fun schemaOnlyGrows() {
        // 只能往上加：老模块 App 读到更大的 v 走 lenient + unsupported，
        // 反过来（把 SCHEMA 改小）会让新 App 把新文档当旧文档严格解析。
        assertTrue("schema 不应回退：${BridgeProtocol.SCHEMA}", BridgeProtocol.SCHEMA >= 2)
    }
}
