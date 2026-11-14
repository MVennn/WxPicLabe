package io.github.MVennn.WxPicLabel.feature.imglabel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 文字过滤（黑白名单）：决定「这一张图要不要带文字发出去」。
 *
 * <h2>两种模式</h2>
 *
 * <ul>
 *   <li><b>白名单</b>（{@link #MODE_WHITELIST}）：<b>命中</b>关键字的图才发文字，其余只发图；</li>
 *   <li><b>黑名单</b>（{@link #MODE_BLACKLIST}）：<b>命中</b>关键字的图只发图，其余照常发文字；</li>
 *   <li>{@link #MODE_OFF}：全部照发（出厂默认，等于没有这个功能）。</li>
 * </ul>
 *
 * <h2>关键字写法</h2>
 *
 * 一行一条，井号开头是注释，空白行忽略：
 *
 * <pre>
 * 003033
 * IMG_2026*
 * 截图
 * </pre>
 *
 * 不含通配符的关键字按<b>子串包含</b>判定（写 033 就能命中 003033）；
 * 含星号 / 问号时按整段通配判定（星号 = 任意多个字、问号 = 一个字），所以两头要自己补星号。
 *
 * <h2>为什么不让用户写正则</h2>
 *
 * 正则表达式手写就是找 bug：一个漏转义的左括号就让整表失效。通配只保留星号与问号，
 * 转义交给这里做，写错最多是「不命中」，不会炸。
 *
 * <h2>失败即放行</h2>
 *
 * 任何异常都退回「全部照发」—— 宁可多带一行文字，也不能因为名单解析失败
 * 把整批图弄成「只有图、没有名」。
 *
 * <h2>纯 Java</h2>
 *
 * 刻意不引用 Android / Xposed 类：设置页（模块 App 进程）与发送链路（微信进程）
 * 共用同一份判定，且能在宿主上直接跑 JVM 单测（见 {@code TextFilterTest}）。
 */
public final class TextFilter {

    /** 关闭：不判断，每张图都照常发文字 */
    public static final int MODE_OFF = 0;

    /** 白名单：只有命中的图才发文字 */
    public static final int MODE_WHITELIST = 1;

    /** 黑名单：命中的图不发文字 */
    public static final int MODE_BLACKLIST = 2;

    private TextFilter() {
    }

    /** 配置里的模式字符串 → 常量。认不出来一律当「关闭」。 */
    public static int parseMode(String raw) {
        if (raw == null) {
            return MODE_OFF;
        }
        String s = raw.trim();
        if ("white".equalsIgnoreCase(s)) {
            return MODE_WHITELIST;
        }
        if ("black".equalsIgnoreCase(s)) {
            return MODE_BLACKLIST;
        }
        return MODE_OFF;
    }

    /** 一条关键字。构造时就编译好，判定路径上不再做字符串处理。 */
    public static final class Rule {

        /** 原文（去首尾空白），用于界面回显「命中的是哪一条」 */
        public final String source;

        /** 归一化后用于子串包含判定的词（按需折叠大小写） */
        private final String keyword;

        private final boolean ignoreCase;

        /** 通配规则（需要折叠时已折叠）；不含通配符时为 null */
        private final String glob;

        Rule(String source, boolean ignoreCase) {
            this.source = source;
            this.ignoreCase = ignoreCase;
            this.keyword = ignoreCase ? source.toLowerCase(Locale.ROOT) : source;
            boolean wild = source.indexOf('*') >= 0 || source.indexOf('?') >= 0;
            // 通配也一并折叠：判定时只折文本一次，见 find
            this.glob = wild ? keyword : null;
        }

        /** 判定一段**已按需折叠**的文本（由 {@link #find} 负责折叠，避免每条规则各折一遍）。 */
        boolean matches(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            return glob != null ? globMatches(glob, text) : text.contains(keyword);
        }
    }

    /**
     * 解析关键字表。
     *
     * @param raw        一行一条的原文
     * @param ignoreCase 是否忽略大小写（中文不受影响；英文截图名常见全大写）
     */
    public static List<Rule> parseRules(String raw, boolean ignoreCase) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<Rule> out = new ArrayList<>();
            for (String line : raw.split("\\r?\\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                out.add(new Rule(s, ignoreCase));
            }
            return out.isEmpty() ? Collections.emptyList() : out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /**
     * 找出第一条命中的关键字；没有命中返回 null。
     *
     * <p>{@code texts} 传多段时按「任一命中即算命中」——
     * 调用方一般传「重命名后的文字」和「原文件名」两段。
     */
    public static Rule find(List<Rule> rules, CharSequence... texts) {
        if (rules == null || rules.isEmpty() || texts == null) {
            return null;
        }
        // 同一段文本只折一次大小写：改前是「每条规则 × 每段文本」各折一遍，
        // 名单几十条时同一个文件名会被反复 toLowerCase 几十次。
        String[] folded = new String[texts.length];
        boolean[] foldedDone = new boolean[texts.length];
        for (Rule r : rules) {
            for (int i = 0; i < texts.length; i++) {
                CharSequence t = texts[i];
                if (t == null) {
                    continue;
                }
                String s;
                if (r.ignoreCase) {
                    if (!foldedDone[i]) {
                        folded[i] = t.toString().toLowerCase(Locale.ROOT);
                        foldedDone[i] = true;
                    }
                    s = folded[i];
                } else {
                    s = t.toString();
                }
                if (r.matches(s)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** 按模式判定：这张图该不该发文字。 */
    public static boolean allowsText(int mode, List<Rule> rules, CharSequence... texts) {
        if (mode == MODE_WHITELIST) {
            return find(rules, texts) != null;
        }
        if (mode == MODE_BLACKLIST) {
            return find(rules, texts) == null;
        }
        return true;
    }

    /**
     * 整段通配匹配（{@code *} = 任意多个字，{@code ?} = 一个字）。
     *
     * <p>为什么不用正则：早前把规则译成 {@code .*} / {@code .} 再 {@code matches()}，
     * 而 {@code *a*a*a*a*a*a*b} 这种名单会让回溯爆炸 —— 判一条文件名就能把微信进程的
     * Binder 线程卡住（用户自己写的名单，等于自带一个卡死入口）。
     * 这里是经典的「记住上一个星号位置」线性扫描：最坏 O(长度×星号数)，没有回溯。
     *
     * <p>输入永远是文件名（不含换行），所以不像正则那样额外把换行排除在 {@code .} 之外。
     */
    private static boolean globMatches(String glob, String text) {
        int g = 0;
        int t = 0;
        int star = -1;
        int mark = 0;
        int gl = glob.length();
        int tl = text.length();
        while (t < tl) {
            if (g < gl) {
                char gc = glob.charAt(g);
                if (gc == '?' || gc == text.charAt(t)) {
                    g++;
                    t++;
                    continue;
                }
                if (gc == '*') {
                    star = g++;
                    mark = t;
                    continue;
                }
            }
            if (star < 0) {
                return false;
            }
            // 回退到上一个星号，让它多吃一个字再比
            g = star + 1;
            t = ++mark;
        }
        while (g < gl && glob.charAt(g) == '*') {
            g++;
        }
        return g == gl;
    }
}
