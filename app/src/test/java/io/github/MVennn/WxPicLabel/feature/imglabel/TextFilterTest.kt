package io.github.MVennn.WxPicLabel.feature.imglabel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文字过滤的纯逻辑单测。
 *
 * 这一层刻意不碰 Android：判定跑在微信进程里，出错的代价是「整批图不带文字」，
 * 所以规则解析、通配、多段匹配、三种模式的放行结果都在这里钉死。
 */
class TextFilterTest {

    @Test
    fun parseModeMapsKnownValuesAndFallsBackToOff() {
        assertEquals(TextFilter.MODE_OFF, TextFilter.parseMode(null))
        assertEquals(TextFilter.MODE_OFF, TextFilter.parseMode(""))
        assertEquals(TextFilter.MODE_OFF, TextFilter.parseMode("off"))
        assertEquals(TextFilter.MODE_OFF, TextFilter.parseMode("whatever"))
        assertEquals(TextFilter.MODE_WHITELIST, TextFilter.parseMode("white"))
        assertEquals(TextFilter.MODE_WHITELIST, TextFilter.parseMode("WHITE"))
        assertEquals(TextFilter.MODE_BLACKLIST, TextFilter.parseMode("Black"))
        assertEquals(TextFilter.MODE_BLACKLIST, TextFilter.parseMode(" black "))
    }

    @Test
    fun parseRulesSkipsBlankLinesAndComments() {
        val rules = TextFilter.parseRules(
            "# 注释行\n\n  截图  \n\t\n#另一个注释\nIMG_0001\n",
            true,
        )
        assertEquals(2, rules.size)
        assertEquals("截图", rules[0].source)
        assertEquals("IMG_0001", rules[1].source)
    }

    @Test
    fun parseRulesTreatsNullAndBlankAsEmpty() {
        assertTrue(TextFilter.parseRules(null, true).isEmpty())
        assertTrue(TextFilter.parseRules("   \n\n\t\n", true).isEmpty())
        assertTrue(TextFilter.parseRules("# 只有注释\n", true).isEmpty())
    }

    @Test
    fun substringMatchHitsAnyOfTheTexts() {
        val rules = TextFilter.parseRules("截图", true)
        assertNotNull(TextFilter.find(rules, "微信截图_20260101"))
        assertNotNull(TextFilter.find(rules, "无关", "微信截图_20260101"))
        assertNull(TextFilter.find(rules, "无关", "另一张"))
    }

    @Test
    fun ignoreCaseIsHonouredPerParseCall() {
        val loose = TextFilter.parseRules("IMG_", true)
        val strict = TextFilter.parseRules("IMG_", false)
        assertNotNull(TextFilter.find(loose, "img_0001"))
        assertNull(TextFilter.find(strict, "img_0001"))
    }

    @Test
    fun wildcardRulesMatchWholeText() {
        val rules = TextFilter.parseRules("IMG_????.jpg", true)
        assertNotNull(TextFilter.find(rules, "IMG_2024.jpg"))
        assertNull(TextFilter.find(rules, "IMG_2024.jpeg"))

        val star = TextFilter.parseRules("Screenshot*", true)
        assertNotNull(TextFilter.find(star, "Screenshot_2026-01-01-01-01-01"))
        assertNotNull(TextFilter.find(star, "Screenshot_x"))
        assertNull(TextFilter.find(star, "my Screenshot_x"))
    }

    @Test
    fun wildcardRulesIgnoreRegexMetacharactersInKeywords() {
        // 关键字里的 ( ) + 之类必须按字面量走，不能当正则语法
        val rules = TextFilter.parseRules("a+b(1)", true)
        assertNotNull(TextFilter.find(rules, "a+b(1)"))
        assertNull(TextFilter.find(rules, "aab1"))
    }

    @Test
    fun offModeAlwaysAllowsText() {
        val rules = TextFilter.parseRules("截图", true)
        assertTrue(TextFilter.allowsText(TextFilter.MODE_OFF, rules, "截图"))
        assertTrue(TextFilter.allowsText(TextFilter.MODE_OFF, rules, "别的"))
    }

    @Test
    fun whitelistKeepsOnlyHits() {
        val rules = TextFilter.parseRules("截图\n表情", true)
        assertTrue(TextFilter.allowsText(TextFilter.MODE_WHITELIST, rules, "微信截图_1"))
        assertTrue(TextFilter.allowsText(TextFilter.MODE_WHITELIST, rules, "无关", "表情包"))
        assertFalse(TextFilter.allowsText(TextFilter.MODE_WHITELIST, rules, "无关"))
    }

    @Test
    fun blacklistDropsHits() {
        val rules = TextFilter.parseRules("截图\n表情", true)
        assertFalse(TextFilter.allowsText(TextFilter.MODE_BLACKLIST, rules, "微信截图_1"))
        assertFalse(TextFilter.allowsText(TextFilter.MODE_BLACKLIST, rules, "无关", "表情包"))
        assertTrue(TextFilter.allowsText(TextFilter.MODE_BLACKLIST, rules, "无关"))
    }

    @Test
    fun emptyRuleListIsDecidedByTheCaller() {
        // TextFilter 只做纯语义（白名单 = 命中才放行）；「空表不吞批」的短路在模块侧
        // WxPicLabelModule 的 textAllowed 里：规则为空一律放行。这里钉住的是纯语义，
        // 免得以后有人把短路逻辑挪进 TextFilter，两处逻辑打架。
        val empty = TextFilter.parseRules("", true)
        assertFalse(TextFilter.allowsText(TextFilter.MODE_WHITELIST, empty, "随便"))
        assertTrue(TextFilter.allowsText(TextFilter.MODE_BLACKLIST, empty, "随便"))
    }

    @Test
    fun emptyTextIsTreatedAsNoMatch() {
        val rules = TextFilter.parseRules("截图", true)
        assertNull(TextFilter.find(rules, ""))
        assertTrue(TextFilter.allowsText(TextFilter.MODE_BLACKLIST, rules, ""))
        assertFalse(TextFilter.allowsText(TextFilter.MODE_WHITELIST, rules, ""))
    }
}
