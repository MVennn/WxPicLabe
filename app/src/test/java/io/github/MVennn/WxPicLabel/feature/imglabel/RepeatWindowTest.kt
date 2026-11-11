package io.github.MVennn.WxPicLabel.feature.imglabel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 追加时段的解析与判定。
 *
 * 这里锁的是**闸门口径**，不是 UI 文案：端点含开始不含结束、坏段丢掉不让整表失效、
 * 一段都不剩就退回全天。口径一旦松动，「今天三点半前发过、三点半后再发」就会变成 +2 ——
 * 那正是要避免的那件事。
 */
class RepeatWindowTest {

    /** 本地时区的某天某时某分；判定也用本地时区，所以这里跟机器时区无关。 */
    private fun at(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    @Test
    fun emptyMeansAllDay() {
        assertTrue(RepeatWindow.parse(null).isAllDay)
        assertTrue(RepeatWindow.parse("").isAllDay)
        assertTrue(RepeatWindow.parse("   ").isAllDay)
        // 一段都解析不出来 → 全天，而不是「什么都不记」
        assertTrue(RepeatWindow.parse("乱写的").isAllDay)
        assertTrue(RepeatWindow.ALL_DAY.contains(at(3, 0)))
        assertTrue(RepeatWindow.ALL_DAY.contains(at(15, 30)))
        assertEquals("", RepeatWindow.ALL_DAY.raw())
    }

    @Test
    fun parsesOneSpan() {
        val w = RepeatWindow.parse("15:30-17:30")
        assertFalse(w.isAllDay)
        assertEquals(1, w.spans().size)
        assertEquals(15 * 60 + 30, w.spans()[0].start)
        assertEquals(17 * 60 + 30, w.spans()[0].end)
        assertEquals("15:30-17:30", w.raw())
        assertEquals("15:30-17:30", w.spans()[0].text())
        assertEquals(120, w.spans()[0].length())
    }

    @Test
    fun startIncludedEndExcluded() {
        val w = RepeatWindow.parse("15:30-17:30")
        assertFalse(w.contains(at(15, 29)))
        assertTrue(w.contains(at(15, 30)))
        assertTrue(w.contains(at(17, 29)))
        // 17:30 那一刻已经出段：含开始、不含结束
        assertFalse(w.contains(at(17, 30)))
        assertFalse(w.contains(at(23, 0)))
    }

    @Test
    fun crossMidnightSpan() {
        val w = RepeatWindow.parse("23:00-01:00")
        assertTrue(w.contains(at(23, 0)))
        assertTrue(w.contains(at(23, 59)))
        assertTrue(w.contains(at(0, 30)))
        assertFalse(w.contains(at(1, 0)))
        assertFalse(w.contains(at(22, 59)))
        assertEquals(120, w.spans()[0].length())
    }

    @Test
    fun severalSpansAndSeparators() {
        val w = RepeatWindow.parse("15:30-17:30, 20:00-21:00;08:00-09:00\n12:00-13:00")
        assertEquals(4, w.spans().size)
        assertEquals("15:30-17:30,20:00-21:00,08:00-09:00,12:00-13:00", w.raw())
        assertTrue(w.contains(at(12, 30)))
        assertFalse(w.contains(at(14, 0)))
    }

    @Test
    fun tolerantInput() {
        // 不写冒号、单位数小时、全角连字符都认；坏段丢掉，好段照留
        val w = RepeatWindow.parse("1530-1730，9-11，坏段")
        assertEquals(2, w.spans().size)
        assertEquals("15:30-17:30,09:00-11:00", w.raw())
        assertTrue(w.contains(at(9, 0)))
        assertTrue(w.contains(at(10, 59)))
        assertFalse(w.contains(at(11, 0)))
    }

    @Test
    fun duplicateSpanKeptOnce() {
        val w = RepeatWindow.parse("15:30-17:30,15:30-17:30")
        assertEquals(1, w.spans().size)
        assertEquals("15:30-17:30", w.raw())
    }

    @Test
    fun parseSpanRejectsBadInput() {
        assertNull(RepeatWindow.parseSpan(null))
        assertNull(RepeatWindow.parseSpan(""))
        assertNull(RepeatWindow.parseSpan("   "))
        assertNull(RepeatWindow.parseSpan("15:30"))
        assertNull(RepeatWindow.parseSpan("abc-def"))
        assertNull(RepeatWindow.parseSpan("15:30-15:30"))
        assertNull(RepeatWindow.parseSpan("25:00-26:00"))
        assertNull(RepeatWindow.parseSpan("15:60-17:00"))
        assertNull(RepeatWindow.parseSpan("24:30-25:00"))
        // 合法的照收
        assertEquals(15 * 60 + 30, RepeatWindow.parseSpan("15:30-17:30")!!.start)
        assertEquals(60, RepeatWindow.parseSpan("23:00-01:00")!!.end)
        assertEquals(1440, RepeatWindow.parseSpan("23:00-24:00")!!.end)
    }

    @Test
    fun plusAndMinusRebuildRaw() {
        val base = RepeatWindow.parse("15:30-17:30")
        val added = base.plus(RepeatWindow.parseSpan("20:00-21:00")!!)
        assertEquals("15:30-17:30,20:00-21:00", added.raw())
        // 重复加同一段不产生第二条
        assertEquals(2, added.plus(RepeatWindow.parseSpan("20:00-21:00")!!).spans().size)
        val removed = added.minus(0)
        assertEquals("20:00-21:00", removed.raw())
        // 删空 → 全天
        val empty = removed.minus(0)
        assertTrue(empty.isAllDay)
        assertEquals("", empty.raw())
        // 越界不动
        assertEquals("15:30-17:30", base.minus(9).raw())
        assertEquals("15:30-17:30", base.minus(-1).raw())
    }

    @Test
    fun spanOfFromMinutes() {
        // 设置页的滚轮直接给分钟数，不再经过文本：合法边界要能直接造段
        val span = RepeatWindow.Span.of(15 * 60 + 30, 17 * 60 + 30)!!
        assertEquals("15:30-17:30", span.text())
        assertEquals(120, span.length())
        // 终点 1440 = 次日零点，是合法上界（文本路径也认 24:00）
        assertEquals("23:00-24:00", RepeatWindow.Span.of(23 * 60, RepeatWindow.DAY_MINUTES)!!.text())
        // 起止同一分钟 = 零长度，不该建出来（弹层据此报「开始和结束不能是同一个时间」）
        assertNull(RepeatWindow.Span.of(15 * 60, 15 * 60))
        // 越界一律不建
        assertNull(RepeatWindow.Span.of(-1, 60))
        assertNull(RepeatWindow.Span.of(RepeatWindow.DAY_MINUTES, 60))
        assertNull(RepeatWindow.Span.of(0, RepeatWindow.DAY_MINUTES + 1))
    }

    @Test
    fun hhmmFormatsDayEnd() {
        assertEquals("24:00", RepeatWindow.hhmm(RepeatWindow.DAY_MINUTES))
        assertEquals("00:05", RepeatWindow.hhmm(5))
        assertEquals("15:30", RepeatWindow.hhmm(15 * 60 + 30))
    }
}
