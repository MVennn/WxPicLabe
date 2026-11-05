package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.content.SharedPreferences;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 「发送设置」的计数仓库：**同名**图片发过几次。
 *
 * <h2>为什么认名字、不认内容</h2>
 *
 * 用户第二天重新截一张同名图，文件内容（SHA）已经变了，但名字没变 ——
 * 要接着数 +2、+3。所以键就是**实际要发出去的那段文字**
 * （labelOf(path) 的返回值，文件名去掉扩展名），不算哈希、不读文件。
 *
 * <h2>为什么存在微信进程自己的 SharedPreferences</h2>
 *
 * remote preferences 在目标进程侧**只读**，写不进去；模块 App 的私有目录
 * **跨 uid 不可写**。唯一能落盘的地方就是微信自己的数据目录：
 * /data/data/com.tencent.mm/shared_prefs/wxpiclabel_repeat.xml。
 *
 * 代价是模块 App 读不到这份记录，所以「清空发送记录」只能走**单向令牌**：
 * 设置页往 remote preferences 写一个毫秒时间戳（img_label_repeat_reset），
 * 本类每次计数前比对，发现变了就整体清空。不需要跨进程监听器，天然幂等。
 *
 * <h2>值的编码</h2>
 *
 * 每个键存 {@code "<次数>|<最后一次发送的毫秒时间戳>"}，用竖线分隔：
 * 次数给 bump 用，时间戳只给淘汰用（见 {@link #trimIfNeeded()}）。
 *
 * <h2>失败即降级</h2>
 *
 * 任何异常都返回 1（= 当作第一次发，发原文）。**绝不因为计数失败影响发送链路**。
 */
public final class RepeatCounter {

    /** 计数键前缀，用来和 {@link #KEY_RESET} 区分。 */
    private static final String PREFIX = "n:";

    /** 已应用过的清空令牌。 */
    private static final String KEY_RESET = "__reset";

    /**
     * 最多记多少条。超出按「最后一次发送时间」淘汰最旧的 ——
     * 长期使用不会把 prefs 撑大，也不会因为清理把正在用的记录删掉。
     */
    private static final int MAX_ENTRIES = 300;

    private final SharedPreferences prefs;

    public RepeatCounter(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 记一次并返回「这是第几次」。
     *
     * @param label      实际要发出去的文字（同名即同一张图）
     * @param resetToken 设置页写下的清空令牌；与本地记录的不同就先把历史清掉
     * @return ≥1 的次数；任何异常都返回 1
     */
    public synchronized int bump(String label, String resetToken) {
        if (prefs == null || label == null || label.isEmpty()) {
            return 1;
        }
        try {
            applyResetIfNeeded(resetToken);
            String key = PREFIX + label;
            int count = parseCount(prefs.getString(key, null)) + 1;
            prefs.edit()
                    .putString(key, count + "|" + System.currentTimeMillis())
                    .commit();
            trimIfNeeded();
            return count;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 令牌变了就整体清空（含旧的 __reset），再把新令牌记下来。 */
    private void applyResetIfNeeded(String resetToken) {
        String wanted = resetToken == null ? "" : resetToken;
        String applied = prefs.getString(KEY_RESET, "");
        if (wanted.equals(applied)) {
            return;
        }
        prefs.edit()
                .clear()
                .putString(KEY_RESET, wanted)
                .commit();
    }

    /** {@code "<次数>|<时间戳>"} 里的次数；解析不出来一律当 0。 */
    private static int parseCount(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0;
        }
        String head = raw;
        int bar = raw.indexOf('|');
        if (bar > 0) {
            head = raw.substring(0, bar);
        }
        try {
            int n = Integer.parseInt(head.trim());
            return n < 0 ? 0 : n;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 超过 [MAX_ENTRIES] 条时，按时间戳从旧到新删掉多出来的那些。 */
    private void trimIfNeeded() {
        Map<String, ?> all = prefs.getAll();
        int count = 0;
        for (String key : all.keySet()) {
            if (key.startsWith(PREFIX)) {
                count++;
            }
        }
        if (count <= MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith(PREFIX)) {
                continue;
            }
            Object v = e.getValue();
            long ts = 0L;
            if (v instanceof String) {
                String s = (String) v;
                int bar = s.indexOf('|');
                if (bar > 0) {
                    try {
                        ts = Long.parseLong(s.substring(bar + 1).trim());
                    } catch (Throwable t) {
                        ts = 0L;
                    }
                }
            }
            entries.add(new AbstractMap.SimpleEntry<>(e.getKey(), ts));
        }
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));
        int excess = entries.size() - MAX_ENTRIES;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < excess && i < entries.size(); i++) {
            ed.remove(entries.get(i).getKey());
        }
        ed.commit();
    }
}
