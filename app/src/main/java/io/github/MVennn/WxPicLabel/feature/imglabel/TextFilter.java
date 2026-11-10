package io.github.MVennn.WxPicLabel.feature.imglabel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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

    private static final int CASE_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

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

        /** 通配规则的编译结果；不含通配符时为 null */
        private final Pattern wildcard;

        Rule(String source, boolean ignoreCase) {
            this.source = source;
            this.ignoreCase = ignoreCase;
            this.keyword = ignoreCase ? source.toLowerCase(Locale.ROOT) : source;
            boolean glob = source.indexOf('*') >= 0 || source.indexOf('?') >= 0;
            this.wildcard = glob
                    ? Pattern.compile(globToRegex(source), ignoreCase ? CASE_FLAGS : 0)
                    : null;
        }

        boolean matches(String text) {
            if (text == null || text.isEmpty()) {
                return false;
            }
            if (wildcard != null) {
                return wildcard.matcher(text).matches();
            }
            String t = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
            return t.contains(keyword);
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
        for (Rule r : rules) {
            for (CharSequence t : texts) {
                if (t == null) {
                    continue;
                }
                if (r.matches(t.toString())) {
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

    /** 把星号 / 问号通配转成整段匹配的正则；其余字符逐个转义。 */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 16);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
