package io.github.MVennn.WxPicLabel.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RecordEdits.merge] 的合并语义。
 *
 * 整段删除一次要并一百多条 `d|seq`，合并错了就是删错行 —— 这里锁住三条：
 * 同一行只留最新动作、不同行累加、解析不出 `seq` 的原样带上。
 */
class RecordEditsMergeTest {

    @Test
    fun sameSeqKeepsNewestAction() {
        val merged = RecordEdits.merge(
            current = listOf("l|3|旧名字"),
            incoming = listOf("d|3"),
        )
        assertEquals(setOf("d|3"), merged)
    }

    @Test
    fun differentSeqAccumulates() {
        val merged = RecordEdits.merge(
            current = listOf("l|1|甲", "d|2"),
            incoming = listOf("d|7", "n|8|3"),
        )
        assertEquals(setOf("l|1|甲", "d|2", "d|7", "n|8|3"), merged)
    }

    @Test
    fun opWithoutSeqIsKeptAsIs() {
        val merged = RecordEdits.merge(
            current = listOf("legacy"),
            incoming = listOf("d|4"),
        )
        assertEquals(setOf("legacy", "d|4"), merged)
    }

    @Test
    fun emptyIncomingKeepsCurrent() {
        val merged = RecordEdits.merge(
            current = listOf("l|1|甲", "d|2"),
            incoming = emptyList(),
        )
        assertEquals(setOf("l|1|甲", "d|2"), merged)
    }
}
