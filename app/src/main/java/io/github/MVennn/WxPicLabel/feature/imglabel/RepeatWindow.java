package io.github.MVennn.WxPicLabel.feature.imglabel;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * 「重复图片追加次数」的时间闸门：只有**落在设定时段里发出去**的图才进重复计数。
 *
 * <p>闸门在计数之前，不在显示之后 —— 时段外发出的图连计数表都不碰。这是需求的
 * 原话：「今天三点半前发的，三点半以后发也不会 +2」。若只是「计了但不加号」，
 * 时段内第一次就会读到三点半前那次留下的 1，直接变成第 2 次而 +2。
 *
 * <p>存储格式就是一段纯文本：{@code 15:30-17:30} 一段，多段用逗号分隔，
 * 例 {@code 15:30-17:30,20:00-21:00}；**空串 = 全天**（出厂口径，等于没有这道闸门）。
 * 解析是宽容的：认 {@code 1530-1730}、{@code 9-11}、全角连字符，坏段丢掉而不是
 * 让整张表失效 —— 表写坏了最多退回「哪些段还能用」，不会把功能整体关掉。
 *
 * <p>端点口径：含开始、不含结束。{@code 15:30-17:30} 里 15:30:00 算、17:30:00 不算。
 * {@code start > end} 视为跨零点（{@code 23:00-01:00}），这样值夜班的时段也表达得出来。
 */
public final class RepeatWindow {

    /** 全天：没有任何时段限制。出厂值，也是解析后一段都不剩时的回落值。 */
    public static final RepeatWindow ALL_DAY = new RepeatWindow(Collections.<Span>emptyList());

    /** 一天的分钟数；{@code 24:00} 折算成这个值，好让 {@code [start, end)} 直接比大小。 */
    public static final int DAY_MINUTES = 24 * 60;

    /**
     * 段与段之间的分隔符：半角逗号/分号/换行，外加中文输入法顺手打出来的全角
     * 「，」「；」「、」—— 用户不会为了填这个框切输入法。
     */
    private static final String DELIMITERS = ",;\n，；、";

    /** 起止之间的连接符：半角/全角连字符、波浪线都认。 */
    private static final String RANGES = "-~—–～－";

    /** 一段 {@code [start, end)}，分钟制；{@code start > end} 表示跨过零点。 */
    public static final class Span {

        /** 起点分钟数，0..1439。 */
        public final int start;

        /** 终点分钟数，1..1440（1440 = 次日零点）；不含在区间内。 */
        public final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }

        /**
         * 由分钟数直接造一段：给设置页的滚轮用（滚轮给出的是 0..1439 的整数，
         * 不必再格式化成文本让 {@link #parseSpan} 解析一遍）。
         *
         * <p>构造器是包私有的，ui 包拿不到 —— 没有这个工厂，设置页就只能拼字符串
         * 再解析回来，凭空多出一段「可能解析失败」的路径。
         *
         * @return 越界或起止相同的段返回 {@code null}（同样的口径：{@code 15:30-15:30}
         *         长度为零，不该建出来）
         */
        public static Span of(int startMinute, int endMinute) {
            if (startMinute < 0 || startMinute >= DAY_MINUTES) {
                return null;
            }
            if (endMinute < 0 || endMinute > DAY_MINUTES) {
                return null;
            }
            if (startMinute == endMinute) {
                return null;
            }
            return new Span(startMinute, endMinute);
        }

        /** 这一段覆盖多少分钟；跨零点的段按跨天算。 */
        public int length() {
            if (start < end) {
                return end - start;
            }
            if (start > end) {
                return DAY_MINUTES - start + end;
            }
            return 0;
        }

        /**
         * 某个时刻（当天分钟数）在不在段内。
         *
         * <p>含开始不含结束：{@code 15:30-17:30} 的 17:30 那次不算进时段。
         */
        public boolean contains(int minute) {
            if (start < end) {
                return minute >= start && minute < end;
            }
            if (start > end) {
                return minute >= start || minute < end;
            }
            return false;
        }

        /** 存盘 / 展示用：{@code 15:30-17:30}。 */
        public String text() {
            return hhmm(start) + "-" + hhmm(end);
        }

        @Override
        public String toString() {
            return text();
        }
    }

    private final List<Span> spans;

    private RepeatWindow(List<Span> spans) {
        this.spans = Collections.unmodifiableList(new ArrayList<>(spans));
    }

    /**
     * 解析时段表。空串、全空白、一段都解析不出来 → 全天。
     *
     * @param raw 存盘格式的原始文本，可为 null
     */
    public static RepeatWindow parse(String raw) {
        if (raw == null) {
            return ALL_DAY;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return ALL_DAY;
        }
        List<Span> out = new ArrayList<>();
        for (String part : s.split("[" + DELIMITERS + "]")) {
            Span span = parseSpan(part);
            if (span == null || containsSpan(out, span)) {
                continue;
            }
            out.add(span);
        }
        if (out.isEmpty()) {
            return ALL_DAY;
        }
        return new RepeatWindow(out);
    }

    /**
     * 解析单独一段。格式不对（缺连接符、时间越界、起止相同）返回 null。
     *
     * <p>UI 的新建弹层直接拿它做校验，所以这里**不宽容**：宁可当场提示，
     * 也不要让用户以为存进去了。
     */
    public static Span parseSpan(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return null;
        }
        int cut = -1;
        for (int i = 1; i < s.length(); i++) {
            if (RANGES.indexOf(s.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut < 0) {
            return null;
        }
        int start = minuteOf(s.substring(0, cut));
        int end = minuteOf(s.substring(cut + 1));
        if (start < 0 || end < 0 || start == end) {
            return null;
        }
        return new Span(start, end);
    }

    /** 这一段在不在；全天恒真。 */
    public boolean contains(long atMs) {
        if (spans.isEmpty()) {
            return true;
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(atMs);
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i).contains(minute)) {
                return true;
            }
        }
        return false;
    }

    /** 有没有设过时段；全天返回 true。 */
    public boolean isAllDay() {
        return spans.isEmpty();
    }

    /** 各段（只读，输入顺序）。 */
    public List<Span> spans() {
        return spans;
    }

    /** 追加一段，返回新对象；起止跟已有段完全相同就不动（Span 没有 equals，按值比）。 */
    public RepeatWindow plus(Span span) {
        if (span == null || containsSpan(spans, span)) {
            return this;
        }
        List<Span> out = new ArrayList<>(spans);
        out.add(span);
        return new RepeatWindow(out);
    }

    /** 去掉第 {@code index} 段，返回新对象；下标越界原样返回。 */
    public RepeatWindow minus(int index) {
        if (index < 0 || index >= spans.size()) {
            return this;
        }
        List<Span> out = new ArrayList<>(spans);
        out.remove(index);
        return new RepeatWindow(out);
    }

    /** 规范化的存盘文本：{@code 15:30-17:30,20:00-21:00}；全天是空串。 */
    public String raw() {
        if (spans.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(spans.get(i).text());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return spans.isEmpty() ? "all-day" : raw();
    }

    // ------------------------------------------------------------------ 解析

    /** 分钟数 → {@code HH:mm}；1440 写成 {@code 24:00}，好跟用户输入对得上。 */
    static String hhmm(int minute) {
        int h = minute / 60;
        int m = minute % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    /** 把一个时间字段折成分钟数；{@code 930} / {@code 9:30} / {@code 9} 都认，非法返回 -1。 */
    private static int minuteOf(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return -1;
        }
        int h;
        int m;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            h = digits(s.substring(0, colon));
            m = digits(s.substring(colon + 1));
        } else if (s.length() <= 2) {
            h = digits(s);
            m = 0;
        } else {
            h = digits(s.substring(0, s.length() - 2));
            m = digits(s.substring(s.length() - 2));
        }
        if (h < 0 || m < 0 || h > 24 || m > 59) {
            return -1;
        }
        if (h == 24 && m != 0) {
            return -1;
        }
        return h * 60 + m;
    }

    private static int digits(String s) {
        if (s.isEmpty() || s.length() > 2) {
            return -1;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static boolean containsSpan(List<Span> list, Span span) {
        for (int i = 0; i < list.size(); i++) {
            Span cur = list.get(i);
            if (cur.start == span.start && cur.end == span.end) {
                return true;
            }
        }
        return false;
    }
}
