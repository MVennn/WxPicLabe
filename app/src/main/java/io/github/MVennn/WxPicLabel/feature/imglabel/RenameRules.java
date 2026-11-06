package io.github.MVennn.WxPicLabel.feature.imglabel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重命名规则表：把「文件名」换成用户指定的发送文字。
 *
 * <h2>格式</h2>
 *
 * 一行一条，左边是**文件名（去扩展名）**，右边是要发出去的文字：
 *
 * <pre>
 * MVIMG_20261104_114734 -> 征和工业 003033
 * Screenshot_2026 -> 圣导体 海贼团
 * # 以井号开头的是注释，空行忽略
 * </pre>
 *
 * 分隔符按 `->` → `=>` → `=` → 制表符 的顺序取**第一个**命中的，
 * 所以右边文字里可以安全地出现冒号、括号、单个 `-`。
 *
 * <h2>为什么解析在微信进程侧做</h2>
 *
 * 规则要跟「这一批图」同步使用，配置键是一个字符串（remote preferences 只读），
 * 每批解析一次几十行字符串的成本可以忽略；缓存反而会读到上一批的旧规则。
 *
 * <h2>失败即降级</h2>
 *
 * 任何异常都返回空表 —— 发原名，绝不因为规则有问题把文字弄丢。
 */
public final class RenameRules {

    private RenameRules() {
    }

    /** 解析规则表；解析不出来就是空表（= 全部发原名）。 */
    public static Map<String, String> parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> out = new LinkedHashMap<>();
            for (String line : raw.split("\\r?\\n")) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#")) {
                    continue;
                }
                int at = -1;
                int skip = 1;
                int arrow = s.indexOf("->");
                if (arrow > 0) {
                    at = arrow;
                    skip = 2;
                } else {
                    int fat = s.indexOf("=>");
                    if (fat > 0) {
                        at = fat;
                        skip = 2;
                    } else {
                        int eq = s.indexOf('=');
                        if (eq > 0) {
                            at = eq;
                            skip = 1;
                        } else {
                            int tab = s.indexOf('\t');
                            if (tab > 0) {
                                at = tab;
                                skip = 1;
                            }
                        }
                    }
                }
                if (at <= 0) {
                    continue;
                }
                String from = s.substring(0, at).trim();
                String to = s.substring(at + skip).trim();
                if (from.isEmpty() || to.isEmpty()) {
                    continue;
                }
                out.put(from, to);
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    /** 命中规则就返回新名字，否则返回原名。 */
    public static String apply(Map<String, String> rules, String label) {
        if (rules == null || rules.isEmpty() || label == null) {
            return label;
        }
        String hit = rules.get(label);
        return hit == null ? label : hit;
    }
}
