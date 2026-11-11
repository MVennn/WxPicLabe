package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.content.SharedPreferences;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「发送设置」的计数仓库：**同名**图片发过几次，外加一份**逐次发送台账**。
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
 * 「改记录 / 删记录」同理：设置页把一组动作写成 StringSet 再换一个令牌
 * （img_label_record_edits / img_label_record_edits_token），本类重新应用一遍。
 *
 * <h2>两份数据，各管一件事</h2>
 *
 *  - **计数表**（键 {@code n:<名字>} → {@code "<次数>|<最后一次毫秒>"}）：
 *    只服务「下一次发这张图该是第几次」，键少、读得快。
 *  - **台账**（键 {@code e:<序号>} → {@code "<毫秒>|<次数>|<批次>|<名字>"}）：
 *    每次发送一条事件，服务「发送记录」页面的按时间段分组 / 搜索 / 编辑 / 删除。
 *    序号单调递增、永不复用 —— 模块 App 的改删动作就靠它定位。
 *
 * <h2>失败即降级</h2>
 *
 * 任何异常都返回 1（= 当作第一次发，发原文）。**绝不因为计数失败影响发送链路**。
 */
public final class RepeatCounter {

    /** 计数键前缀，用来和 {@code __xxx} 这类内部键区分。 */
    private static final String PREFIX = "n:";

    /** 台账键前缀。 */
    private static final String EVENT = "e:";

    /** 已应用过的清空令牌。 */
    private static final String KEY_RESET = "__reset";

    /** 台账序号计数器（单调递增，永不复用）。 */
    private static final String KEY_SEQ = "__seq";

    /** 是否已经把旧计数表迁移成台账。 */
    private static final String KEY_MIGRATED = "__migrated";

    /** 最近一次应用的手工修改令牌（回传给模块 App 做「已生效」判据）。 */
    private static final String KEY_EDITS = "__edits";

    /** 迁移出来的事件用这个批次名，界面上单独显示成「旧记录」。 */
    public static final String BATCH_MIGRATED = "m";

    /**
     * 计数键最多记多少条。超出按「最后一次发送时间」淘汰最旧的 ——
     * 长期使用不会把 prefs 撑大，也不会因为清理把正在用的记录删掉。
     */
    private static final int MAX_ENTRIES = 300;

    /** 台账最多留多少条事件，超出按时间淘汰最旧的。 */
    private static final int MAX_EVENTS = 600;

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
            ensureMigrated();
            String key = PREFIX + label;
            int count = parseCount(strOf(key, null)) + 1;
            prefs.edit()
                    .putString(key, count + "|" + System.currentTimeMillis())
                    .commit();
            trimIfNeeded();
            return count;
        } catch (Throwable t) {
            return 1;
        }
    }

    /**
     * 清空令牌变了就整体清空（含台账与迁移标记），返回是否真的清了。
     *
     * <p>{@link #bump} 里也会调它（发送链路自己兜住），这里另外暴露一个同步入口：
     * 设置页点了「清空」之后，微信进程的轮询直接调用，记录页立刻变空 ——
     * 不必等到用户下一次发图才清。
     */
    public synchronized boolean applyReset(String resetToken) {
        if (prefs == null) {
            return false;
        }
        try {
            return applyResetIfNeeded(resetToken);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一条聚合记录：名称、发过几次、最后一次的毫秒时间戳。 */
    public static final class Row {
        public final String label;
        public final int count;
        public final long lastMs;

        public Row(String label, int count, long lastMs) {
            this.label = label;
            this.count = count;
            this.lastMs = lastMs;
        }
    }

    /** 一条逐次发送事件：序号、时间戳、名字、第几次、批次 id。 */
    public static final class Event {
        public final long seq;
        public final long at;
        public final String label;
        public final int n;
        public final String batch;

        public Event(long seq, long at, String label, int n, String batch) {
            this.seq = seq;
            this.at = at;
            this.label = label;
            this.n = n;
            this.batch = batch;
        }
    }

    /**
     * 聚合快照，按最后发送时间**倒序**（最近发的在最前）。
     *
     * <p>模块 App 跨 uid 读不到这份 prefs，所以这里只负责取值，
     * 回传由 {@code WxPicLabelModule#pushRecords} 经 FriendBridgeProvider 完成。
     * 任何异常返回空表 —— 记录页显示「还没有记录」，不影响发送。
     */
    public synchronized List<Row> snapshot() {
        List<Row> out = new ArrayList<>();
        if (prefs == null) {
            return out;
        }
        try {
            out.addAll(rawRows());
            out.sort((a, b) -> Long.compare(b.lastMs, a.lastMs));
        } catch (Throwable ignored) {
            // 读不出来就当没有记录
        }
        return out;
    }

    /**
     * 逐次发送台账，按时间**正序**（先发的在前）—— 界面按批次分组后就是这个顺序。
     *
     * <p>任何异常返回空表。
     */
    public synchronized List<Event> events() {
        List<Event> out = new ArrayList<>();
        if (prefs == null) {
            return out;
        }
        try {
            ensureMigrated();
            out.addAll(rawEvents());
            out.sort((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
        } catch (Throwable ignored) {
            // 同上：读不出来就当没有记录
        }
        return out;
    }

    /**
     * 追加一条发送事件，返回它的序号（0 = 没记成）。
     *
     * <p>台账里存的是**原名**（不带「 +N」后缀），这样记录页按名字分组、按名字搜索
     * 才是一张图一条线。后缀由 {@link #baseLabel} 按「计数表里确实有这个原名」
     * 的硬证据剥掉 —— 不是正则截字段，名字里真带「 +2」的不会被误伤。
     *
     * @param label 实际发出去的文字（可能带「 +N」后缀）
     * @param n     这次是第几次；**0 = 没进累计**（时段外发出的、或重复功能关着），原样落盘
     * @param batch 批次 id；空则按时间戳临时造一个
     * @param atMs  发送成功的毫秒时间戳
     */
    public synchronized long record(String label, int n, String batch, long atMs) {
        if (prefs == null || label == null || label.isEmpty()) {
            return 0L;
        }
        try {
            ensureMigrated();
            String base = baseLabel(label);
            // 0 是**明确的值**，不是「去查一下」：时段外发出的图压根没进计数表，
            // 回查只会拿到上一条历史（甚至昨天的 +3），记录页就会把没加号的这次标成第 3 次。
            int want = Math.max(0, n);
            long seq = longOf(KEY_SEQ, 0L) + 1L;
            String b = batch == null || batch.isEmpty() ? "b" + atMs : batch;
            prefs.edit()
                    .putLong(KEY_SEQ, seq)
                    .putString(EVENT + seq, atMs + "|" + want + "|" + b + "|" + base)
                    .commit();
            trimEventsIfNeeded();
            return seq;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 从「foo +2」还原成「foo」；不满足「有后缀、后缀全是数字、计数表里确实是 foo，
     * 且没有一条叫 foo +2 的」就原样返回。
     *
     * <p>最后那个反向判定是关键：用户本来就有张图叫「日报 +2」，
     * 它的计数键是 n:"日报 +2"，不能被错剥成「日报」。
     */
    private String baseLabel(String sent) {
        int sp = sent.lastIndexOf(" +");
        if (sp <= 0 || sp + 2 >= sent.length()) {
            return sent;
        }
        String tail = sent.substring(sp + 2);
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return sent;
            }
        }
        String head = sent.substring(0, sp);
        boolean hasHead = prefs.contains(PREFIX + head);
        boolean hasSent = prefs.contains(PREFIX + sent);
        return hasHead && !hasSent ? head : sent;
    }

    /** 最近一次成功应用的手工修改令牌（空 = 还没应用过）。 */
    public synchronized String appliedEdits() {
        if (prefs == null) {
            return "";
        }
        try {
            String v = strOf(KEY_EDITS, "");
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 应用模块 App 写下的一组改 / 删动作，并把受影响的计数重算一遍。
     *
     * <p>三种动作都**幂等**（删一条、改名字、改次数再应用一次结果不变），
     * 所以整套动作每次重新应用一遍是安全的 —— 不需要逐条记录「哪条已应用」。
     *
     * <p>计数重算口径：受影响的名字取「台账里剩下的最大次数」，一条不剩就删掉计数键
     * （下一次发就是第 1 次）。其余名字不动 —— 台账有 600 条上限，
     * 全量重算会让早被淘汰的老名字突然归零。
     *
     * @return 是否真的改动了台账
     */
    public synchronized boolean applyEdits(Set<String> ops) {
        if (prefs == null || ops == null || ops.isEmpty()) {
            return false;
        }
        try {
            ensureMigrated();
            Set<String> dirty = new HashSet<>();
            SharedPreferences.Editor ed = prefs.edit();
            boolean changed = false;
            for (String raw : ops) {
                if (raw == null) {
                    continue;
                }
                String[] p = raw.split("\\|", 3);
                if (p.length < 2) {
                    continue;
                }
                long seq = parseLong(p[1], 0L);
                if (seq <= 0L) {
                    continue;
                }
                String key = EVENT + seq;
                Event ev = parseEvent(seq, strOf(key, null));
                if (ev == null) {
                    continue;
                }
                if ("d".equals(p[0])) {
                    ed.remove(key);
                    dirty.add(ev.label);
                    changed = true;
                } else if ("l".equals(p[0]) && p.length >= 3) {
                    String label = p[2];
                    if (label.isEmpty() || label.equals(ev.label)) {
                        continue;
                    }
                    ed.putString(key, ev.at + "|" + ev.n + "|" + ev.batch + "|" + label);
                    dirty.add(ev.label);
                    dirty.add(label);
                    changed = true;
                } else if ("n".equals(p[0]) && p.length >= 3) {
                    int n = (int) parseLong(p[2], -1L);
                    if (n < 0 || n == ev.n) {
                        continue;
                    }
                    ed.putString(key, ev.at + "|" + n + "|" + ev.batch + "|" + ev.label);
                    dirty.add(ev.label);
                    changed = true;
                }
            }
            if (!changed) {
                return false;
            }
            ed.commit();
            rebuildCounters(dirty);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 记下这一批动作已经应用过（回传时带给模块 App，用来判断哪些动作已在台账里）。 */
    public synchronized void markEditsApplied(String token) {
        if (prefs == null || token == null || token.isEmpty()) {
            return;
        }
        try {
            prefs.edit().putString(KEY_EDITS, token).commit();
        } catch (Throwable ignored) {
            // 记不上只会让模块 App 多应用一次（幂等），不影响数据
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 防御式读取：同一份 prefs 里的键可能被更早的版本或手工注入写成别的类型
     * （例如 {@code __migrated} 被写成字符串），直接 {@code getBoolean} 会抛
     * ClassCastException，而调用方大多只 catch Throwable 兜底，
     * 结果就是整条台账静默消失、记录页退回合成的旧记录。
     * 这里统一按实际落盘的类型转换，读不懂才落默认值。
     */
    private Object rawOf(String key) {
        if (prefs == null) {
            return null;
        }
        try {
            return prefs.getAll().get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private String strOf(String key, String def) {
        Object v = rawOf(key);
        return v == null ? def : String.valueOf(v);
    }

    private long longOf(String key, long def) {
        Object v = rawOf(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? 1L : 0L;
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // 落默认值
            }
        }
        return def;
    }

    private boolean boolOf(String key, boolean def) {
        Object v = rawOf(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue() != 0L;
        }
        if (v != null) {
            String s = String.valueOf(v).trim();
            return "1".equals(s) || "true".equalsIgnoreCase(s);
        }
        return def;
    }

    /** 令牌变了就整体清空（含旧的 __reset），再把新令牌记下来。 */
    private boolean applyResetIfNeeded(String resetToken) {
        String wanted = resetToken == null ? "" : resetToken;
        String applied = strOf(KEY_RESET, "");
        if (wanted.equals(applied)) {
            return false;
        }
        prefs.edit()
                .clear()
                .putString(KEY_RESET, wanted)
                .commit();
        return true;
    }

    /**
     * 旧版只有聚合计数表；第一次用到时按它合成台账（每条计数一次事件），
     * 否则升级上来的用户打开记录页会是空的。
     *
     * <p>合成的批次名是 {@link #BATCH_MIGRATED}，界面单独显示成「旧记录」。
     * 只做一次（{@link #KEY_MIGRATED}）。
     */
    private void ensureMigrated() {
        if (boolOf(KEY_MIGRATED, false)) {
            return;
        }
        SharedPreferences.Editor ed = prefs.edit();
        ed.putBoolean(KEY_MIGRATED, true);
        long seq = longOf(KEY_SEQ, 0L);
        int budget = MAX_EVENTS;
        for (Row r : rawRows()) {
            int total = Math.min(r.count, Math.max(0, budget));
            for (int i = 1; i <= total; i++) {
                seq++;
                ed.putString(EVENT + seq, r.lastMs + "|" + i + "|" + BATCH_MIGRATED + "|" + r.label);
            }
            budget -= total;
        }
        ed.putLong(KEY_SEQ, seq);
        ed.commit();
    }

    /** 解析全部计数键（不排序、不迁移）。 */
    private List<Row> rawRows() {
        List<Row> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            Object v = e.getValue();
            if (!(v instanceof String)) {
                continue;
            }
            String raw = (String) v;
            int bar = raw.indexOf('|');
            String head = bar > 0 ? raw.substring(0, bar) : raw;
            long ts = 0L;
            if (bar > 0) {
                ts = parseLong(raw.substring(bar + 1), 0L);
            }
            out.add(new Row(key.substring(PREFIX.length()), (int) parseLong(head, 0L), ts));
        }
        return out;
    }

    /** 解析全部台账键（不排序、不迁移）。 */
    private List<Event> rawEvents() {
        List<Event> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(EVENT)) {
                continue;
            }
            long seq = parseLong(key.substring(EVENT.length()), 0L);
            Event ev = parseEvent(seq, e.getValue());
            if (ev != null) {
                out.add(ev);
            }
        }
        return out;
    }

    /** {@code "<毫秒>|<次数>|<批次>|<名字>"}；名字里允许再有竖线，所以只切三刀。 */
    private static Event parseEvent(long seq, Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        String raw = (String) value;
        String[] p = raw.split("\\|", 4);
        if (p.length < 4) {
            return null;
        }
        long at = parseLong(p[0], 0L);
        int n = (int) parseLong(p[1], 1L);
        String batch = p[2].isEmpty() ? "b" + at : p[2];
        String label = p[3];
        if (label.isEmpty()) {
            return null;
        }
        return new Event(seq, at, label, Math.max(n, 0), batch);
    }

    /** 受影响的名字：计数键改成「台账里剩下的最大次数」，一条不剩就删键。 */
    private void rebuildCounters(Set<String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        Map<String, Integer> maxN = new HashMap<>();
        Map<String, Long> lastAt = new HashMap<>();
        for (Event ev : rawEvents()) {
            if (!labels.contains(ev.label)) {
                continue;
            }
            Integer cur = maxN.get(ev.label);
            if (cur == null || ev.n > cur) {
                maxN.put(ev.label, ev.n);
            }
            Long la = lastAt.get(ev.label);
            if (la == null || ev.at > la) {
                lastAt.put(ev.label, ev.at);
            }
        }
        SharedPreferences.Editor ed = prefs.edit();
        for (String label : labels) {
            Integer n = maxN.get(label);
            if (n == null || n <= 0) {
                ed.remove(PREFIX + label);
                continue;
            }
            Long at = lastAt.get(label);
            ed.putString(PREFIX + label, n + "|" + (at == null ? 0L : at));
        }
        ed.commit();
    }

    /** 台账超过 {@link #MAX_EVENTS} 条时，按时间从旧到新删掉多出来的那些。 */
    private void trimEventsIfNeeded() {
        List<Event> all = rawEvents();
        if (all.size() <= MAX_EVENTS) {
            return;
        }
        all.sort(Comparator.comparingLong(ev -> ev.at));
        int excess = all.size() - MAX_EVENTS;
        SharedPreferences.Editor ed = prefs.edit();
        for (int i = 0; i < excess && i < all.size(); i++) {
            ed.remove(EVENT + all.get(i).seq);
        }
        ed.commit();
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
        int n = (int) parseLong(head, 0L);
        return n < 0 ? 0 : n;
    }

    private static long parseLong(String raw, long def) {
        if (raw == null) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return def;
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
                    ts = parseLong(s.substring(bar + 1), 0L);
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
