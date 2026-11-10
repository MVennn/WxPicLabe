package io.github.MVennn.WxPicLabel.feature.imglabel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重命名规则的纯逻辑单测。
 *
 * 解析器同时被微信进程和设置页使用：进程侧解析错了是「整批图发错名字」，
 * 设置页解析错了是「新建立即失败」。四种分隔符的优先级、注释空行、覆盖语义都在这里钉死。
 */
class RenameRulesTest {

    @Test
    fun emptyAndNullGiveEmptyTable() {
        assertTrue(RenameRules.parse(null).isEmpty())
        assertTrue(RenameRules.parse("").isEmpty())
    }

    @Test
    fun arrowIsPreferredSeparator() {
        assertEquals(mapOf("003033" to "征和工业"), RenameRules.parse("003033 -> 征和工业"))
    }

    @Test
    fun acceptsFatArrowEqualsAndTab() {
        assertEquals(mapOf("a" to "b"), RenameRules.parse("a => b"))
        assertEquals(mapOf("a" to "b"), RenameRules.parse("a = b"))
        assertEquals(mapOf("a" to "b"), RenameRules.parse("a\tb"))
    }

    @Test
    fun fatArrowWinsOverEquals() {
        // 先找 => 再找 =，否则 "a => b" 会被切成 from = "a"、to = "> b"
        assertEquals(mapOf("a" to "b"), RenameRules.parse("a => b"))
    }

    @Test
    fun rightSideMayContainDashesAndColons() {
        assertEquals(
            mapOf("a" to "b - c: d"),
            RenameRules.parse("a -> b - c: d"),
        )
    }

    @Test
    fun commentsAndBlankLinesAreIgnored() {
        val raw = "# 注释\n\n   \n003033 -> 征和工业\n# 另一条注释"
        assertEquals(mapOf("003033" to "征和工业"), RenameRules.parse(raw))
    }

    @Test
    fun linesWithoutSeparatorAreSkipped() {
        val raw = "这行没写分隔符\n003033 -> 征和工业"
        assertEquals(mapOf("003033" to "征和工业"), RenameRules.parse(raw))
    }

    @Test
    fun emptySideIsSkipped() {
        // "->" 在最左边（arrow == 0）不算；右边全空也不算
        assertTrue(RenameRules.parse("-> 只有右边").isEmpty())
        assertTrue(RenameRules.parse("只有左边 ->").isEmpty())
        assertTrue(RenameRules.parse(" -> ").isEmpty())
    }

    @Test
    fun bothSidesAreTrimmed() {
        assertEquals(mapOf("a" to "b"), RenameRules.parse("  a   ->   b  "))
    }

    @Test
    fun orderFollowsFileAndDuplicatesOverwrite() {
        val map = RenameRules.parse("b -> 2\na -> 1\nb -> 3")
        assertEquals(listOf("b", "a"), map.keys.toList())
        assertEquals("3", map["b"])
    }

    @Test
    fun crlfInputWorks() {
        assertEquals(mapOf("a" to "1", "b" to "2"), RenameRules.parse("a -> 1\r\nb -> 2\r\n"))
    }

    @Test
    fun applyReturnsNewNameOnHitAndOriginalOtherwise() {
        val rules = RenameRules.parse("003033 -> 征和工业")
        assertEquals("征和工业", RenameRules.apply(rules, "003033"))
        assertEquals("600605", RenameRules.apply(rules, "600605"))
    }

    @Test
    fun applyIsSafeWithEmptyTableAndNullLabel() {
        assertEquals("003033", RenameRules.apply(emptyMap<String, String>(), "003033"))
        assertNull(RenameRules.apply(emptyMap<String, String>(), null))
        assertNull(RenameRules.apply(null, null))
    }

    @Test
    fun matchingIsExactNotSubstring() {
        val rules = RenameRules.parse("003033 -> 征和工业")
        assertEquals("003033_副本", RenameRules.apply(rules, "003033_副本"))
    }
}
