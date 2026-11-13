package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 用纯 Android 视图层把文字发进当前聊天。
 *
 * <p>微信 8.0.80 起 {@code com.tencent.mm.modelmulti} 包已不存在，按类名反射找发送入口全部落空，
 * 因此改走不依赖任何微信内部类的路子：拿当前 Activity 的输入框 → setText → 点「发送」。
 * 目标是当前正在看的会话，天然不会串号，也就不需要 talker。
 */
public final class UiSender {

    private static final String TAG = "WxPicLabel";
    private static final String[] SEND_TEXTS = {"发送", "Send"};
    private static final int MAX_DEPTH = 40;

    /** 前几次失败时把 View 树打进日志，用于校准选择器。 */
    private static int dumpsLeft = 3;

    /** true = 不看 isShown（后台聊天页的 View 全部 isShown()==false，但照样能 setText/点发送）。 */
    private static boolean LOOSE;

    /** setText 之后等多久点「发送」（毫秒，由模块按属性调整）。 */
    public static int preClickMs = 120;
    /** 点完「发送」后等多久收尾（毫秒）。 */
    public static int postClickMs = 200;

    /** 队列元素：文案 + 发完后的回调（用于放行被闸住的下一张图）。 */
    private static final class Item {
        final String content;
        final Runnable done;

        Item(String content, Runnable done) {
            this.content = content;
            this.done = done;
        }
    }

    /** 待发送队列：一次只推进一条，避免前一条被后一条 setText 覆盖。 */
    private static final java.util.ArrayDeque<Item> QUEUE = new java.util.ArrayDeque<>();
    private static boolean draining;
    private static Handler ui;

    private UiSender() {
    }

    /**
     * 发一条文字到当前聊天。
     *
     * @return 是否已把动作排到 UI 线程（真正点击结果看日志 ui-send: click=）
     */
    public static boolean send(Handler main, String content) {
        return send(main, content, null);
    }

    /** 带完成回调的发送：done 在这条文字真正点完「发送」（或放弃）后执行。 */
    public static boolean send(Handler main, String content, Runnable done) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        ui = main;
        boolean start = false;
        synchronized (QUEUE) {
            QUEUE.add(new Item(content, done));
            if (!draining) {
                draining = true;
                start = true;
            }
        }
        if (start) {
            // 关键：从发图线程/接管线程调进来时不能就地操作 View
            // （setText 会触发光标动画 → Animators may only be run on Looper threads）
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                step();
            } else {
                main.post(UiSender::step);
            }
        }
        return true;
    }

    /** 队列里取一条：setText → 等 350ms 点发送 → 等 600ms 取下一条。 */
    /** 界面暂时取不到时的重试次数（相册收尾、切前后台都会短暂取不到）。 */
    private static int retries;
    /** 零延时模式下 setText 未生效的重试计数。 */
    private static int sameRetry;

    /**
     * 上一轮找到的聊天页 / 输入框 / 发送键。
     *
     * <p>一次发送原本要走 3~4 遍全树递归（找输入框 → 找「发送」→ 兜底找右侧可点节点），
     * 全是主线程 O(视图数)；聊天里每多发一条，树就大一截，于是「图越多越慢」。
     * 微信聊天页的 footer 在切会话时是复用的，同一 Activity 实例内这些引用稳定，
     * 所以只校验「还是同一个 Activity + 还挂在窗口上」，命中就直接用。
     */
    private static WeakReference<Activity> cachedAct = new WeakReference<>(null);
    private static WeakReference<View> cachedRoot = new WeakReference<>(null);
    private static WeakReference<EditText> cachedEt = new WeakReference<>(null);
    private static WeakReference<View> cachedSend = new WeakReference<>(null);
    private static boolean cachedLoose;

    private static void step() {
        Item item;
        synchronized (QUEUE) {
            item = QUEUE.peek();
        }
        String content = item == null ? null : item.content;
        if (content == null || ui == null) {
            draining = false;
            retries = 0;
            return;
        }
        Activity top = currentActivity();
        Activity act = cachedAct.get();
        View root = cachedRoot.get();
        EditText et = cachedEt.get();
        boolean fast = et != null && root != null && act != null
                && !act.isFinishing()
                && et.isAttachedToWindow() && root.isAttachedToWindow()
                && (cachedLoose || act == top);
        if (fast) {
            LOOSE = cachedLoose;
        } else {
            et = null;
            act = top;
            root = act != null && act.getWindow() != null
                    ? act.getWindow().getDecorView() : null;
            LOOSE = false;
            et = root == null ? null : findEditText(root, 0);
            if (et == null) {
                // 相册/预览页盖在聊天页上面时，前台 Activity 没有输入框；
                // 后台的聊天页仍然活着，直接往它的输入框里送，这是「相册里发图时插文字」的关键。
                Activity bg = chatActivity();
                if (bg != null && bg.getWindow() != null) {
                    LOOSE = true;
                    act = bg;
                    root = bg.getWindow().getDecorView();
                    et = findEditText(root, 0);
                }
            }
            if (et != null) {
                cachedAct = new WeakReference<>(act);
                cachedRoot = new WeakReference<>(root);
                cachedEt = new WeakReference<>(et);
                cachedLoose = LOOSE;
                cachedSend = new WeakReference<>(null);
            }
        }
        Log.i(TAG, "ui-send: act=" + (act == null ? "null" : act.getClass().getName())
                + " edit=" + (et == null ? "null" : et.getClass().getName())
                + " loose=" + LOOSE
                + " fast=" + fast
                + " content=" + shorten(content));
        if (et == null) {
            // 相册刚返回时前台可能短暂为空，等界面回来再发（最多 ~9 秒）
            if (++retries <= 30) {
                if (retries == 1) {
                    Log.i(TAG, "ui-send: no chat input yet, retrying");
                }
                ui.postDelayed(UiSender::step, 150L);
                return;
            }
            Log.i(TAG, "ui-send: give up on " + shorten(content));
            Item dropped;
            synchronized (QUEUE) {
                dropped = QUEUE.poll();
            }
            if (dropped != null && dropped.done != null) {
                dropped.done.run();
            }
            retries = 0;
            ui.postDelayed(UiSender::step, 150L);
            return;
        }
        retries = 0;
        // 出队放在点击完成之后（见 finish），这里不能先 poll，否则每条会被消费两次
        final View froot = root;
        final EditText fet = et;
        et.setText(content);
        final long start = SystemClock.uptimeMillis();
        ui.postDelayed(() -> tryClick(froot, fet, content, start,
                start + Math.max(0, preClickMs)), 0L);
    }

    /**
     * 尽快点「发送」：内容一落进输入框就点，最多等到 {@link #preClickMs}（原来是死等）。
     *
     * <p>死等的代价是每条文字固定多花 120 ms；一次 20 张图就是 2.4 秒，纯浪费。
     */
    private static void tryClick(View root, EditText et, String content, long start, long deadline) {
        if (ui == null) {
            return;
        }
        CharSequence now = et.getText();
        boolean same = now != null && content.contentEquals(now.toString().trim());
        if (!same) {
            if (SystemClock.uptimeMillis() < deadline) {
                ui.postDelayed(() -> tryClick(root, et, content, start, deadline), 8L);
                return;
            }
            if (sameRetry < 2) {
                // setText 可能还没被 TextWatcher 处理完：重来一次，绝不丢字
                sameRetry++;
                Log.i(TAG, "ui-send: content not applied yet, retry " + sameRetry);
                et.setText(content);
                final long nd = SystemClock.uptimeMillis() + Math.max(40L, preClickMs);
                ui.postDelayed(() -> tryClick(root, et, content,
                        SystemClock.uptimeMillis(), nd), 40L);
                return;
            }
            sameRetry = 0;
            Log.i(TAG, "ui-send: content never applied, skip " + shorten(content));
            finish(false, et);
            return;
        }
        sameRetry = 0;
        boolean ok = clickSend(root, et);
        Log.i(TAG, "ui-send: click=" + ok + " in " + (SystemClock.uptimeMillis() - start)
                + "ms content=" + shorten(content));
        if (!ok && dumpsLeft-- > 0) {
            dump(root, 0, new StringBuilder());
        }
        finish(ok, et);
    }

    /** 出队 + 回调 + 排下一条。 */
    private static void finish(boolean ok, EditText et) {
        Item finished;
        synchronized (QUEUE) {
            finished = QUEUE.poll();
        }
        if (finished != null && finished.done != null) {
            finished.done.run();
        }
        long deadline = SystemClock.uptimeMillis() + Math.max(0, postClickMs);
        awaitInputCleared(et, deadline);
    }

    /**
     * 下一条要等微信把输入框清空再走，不再死等 {@link #postClickMs}。
     *
     * <p>点完发送微信会同步把输入框置空，清空了就立刻取下一条；只有点没生效时
     * 才会耗满 postClickMs —— 语义与原来一致，只是快的那一半真的变快了。
     */
    private static void awaitInputCleared(EditText et, long deadline) {
        if (ui == null) {
            return;
        }
        boolean cleared;
        try {
            CharSequence now = et == null ? null : et.getText();
            cleared = now == null || now.toString().trim().isEmpty();
        } catch (Throwable t) {
            cleared = true;
        }
        if (cleared || SystemClock.uptimeMillis() >= deadline) {
            ui.postDelayed(UiSender::step, 0L);
            return;
        }
        ui.postDelayed(() -> awaitInputCleared(et, deadline), 8L);
    }

    /** 当前界面是否存在可见的聊天输入框（自测轮询用，不发任何东西）。 */
    public static boolean hasInput() {
        Activity top = currentActivity();
        Activity act = cachedAct.get();
        View root = cachedRoot.get();
        EditText et = cachedEt.get();
        // 缓存快路径：判据与 step() 的 fast path 同源，命中就 O(1) 返回，不走全树。
        // !cachedLoose && act == top 只认前台聊天页；isChatInputFast 对齐 chatActivity() 的 MMEditText 判据，
        // 别把相册/预览页的输入框误判成聊天输入框。
        if (et != null && root != null && act != null
                && !act.isFinishing()
                && et.isAttachedToWindow() && root.isAttachedToWindow()
                && !cachedLoose && act == top
                && isChatInputFast(et)) {
            et.getText();   // 轻量读取，确认缓存的 EditText 仍可用；不发任何东西
            return true;
        }
        // 缓存失效：回退原全树查找，表达式与改前逐字相同（仅变量名 act→top）。
        if (top == null || top.getWindow() == null) {
            return false;
        }
        return findEditText(top.getWindow().getDecorView(), 0) != null;
    }

    /** 聊天页输入框 O(1) 判据：与 chatActivity() 的 MMEditText 判据一致，避免缓存命中还遍历全树。 */
    private static boolean isChatInputFast(EditText et) {
        return et != null && et.getClass().getName().contains("MMEditText");
    }

    /**
     * 找「带聊天输入框的 Activity」，不再限定必须在前台。
     *
     * <p>相册预览页置顶时前台只有相册，聊天页在它下面且所有 View 的 isShown() 都是 false，
     * 但控件树是完整的、点击监听也还在——把文字塞进去照样能发出去。
     */
    private static Activity chatActivity() {
        for (Object rec : activityRecords()) {
            Activity a = (Activity) readField(rec, "activity");
            if (a == null || a.isFinishing() || a.getWindow() == null) {
                continue;
            }
            try {
                View r = a.getWindow().getDecorView();
                if (r == null) {
                    continue;
                }
                EditText et = findEditTextLoose(r, 0);
                if (et == null) {
                    continue;
                }
                boolean chat = findByClassName(r, "MMChattingListView", 0) != null
                        || et.getClass().getName().contains("MMEditText");
                if (chat) {
                    Log.i(TAG, "ui-send: background chat activity = " + a.getClass().getName());
                    return a;
                }
            } catch (Throwable ignored) {
                // 单个页面取不到不影响其余
            }
        }
        return null;
    }

    /** ActivityThread.mActivities 的全部记录（前台后台都在里面）。 */
    private static java.util.List<Object> activityRecords() {
        java.util.List<Object> out = new java.util.ArrayList<>();
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            Field fa = at.getDeclaredField("mActivities");
            fa.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) fa.get(thread);
            if (map != null) {
                out.addAll(map.values());
            }
        } catch (Throwable t) {
            Log.i(TAG, "ui-send: activityRecords failed: " + t);
        }
        return out;
    }

    /** 不看可见性找输入框：只认 EditText 且宽度 > 0。 */
    private static EditText findEditTextLoose(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return null;
        }
        if (v instanceof EditText && v.getWidth() > 0) {
            return (EditText) v;
        }
        if (!(v instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) v;
        EditText best = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            EditText c = findEditTextLoose(g.getChildAt(i), depth + 1);
            if (c == null) {
                continue;
            }
            if (best == null || c.getWidth() > best.getWidth()) {
                best = c;
            }
        }
        return best;
    }

    private static View findByClassName(View v, String simpleName, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return null;
        }
        if (v.getClass().getSimpleName().equals(simpleName)) {
            return v;
        }
        if (!(v instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findByClassName(g.getChildAt(i), simpleName, depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 当前 Activity

    /**
     * 从 ActivityThread 的活动表里取当前未暂停的 Activity。
     *
     * <p>框架类没有混淆，字段名稳定；取不到就返回 null，由调用方按失败处理。
     */
    private static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object thread = current.invoke(null);
            Field fa = at.getDeclaredField("mActivities");
            fa.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) fa.get(thread);
            if (map == null) {
                return null;
            }
            Activity best = null;
            for (Object rec : map.values()) {
                Activity a = (Activity) readField(rec, "activity");
                if (a == null || a.isFinishing()) {
                    continue;
                }
                Object paused = readField(rec, "paused");
                if (Boolean.FALSE.equals(paused)) {
                    best = a;
                }
            }
            return best;
        } catch (Throwable t) {
            Log.i(TAG, "ui-send: currentActivity failed: " + t);
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 找输入框

    private static EditText findEditText(View v, int depth) {
        if (v == null || depth > MAX_DEPTH) {
            return null;
        }
        if (v instanceof EditText && (LOOSE || v.isShown()) && v.getWidth() > 0) {
            return (EditText) v;
        }
        if (!(v instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) v;
        EditText best = null;
        for (int i = 0; i < g.getChildCount(); i++) {
            EditText c = findEditText(g.getChildAt(i), depth + 1);
            if (c == null) {
                continue;
            }
            if (best == null || c.getWidth() > best.getWidth()) {
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ 点发送

    private static boolean clickSend(View root, EditText et) {
        // 缓存命中就少走两遍全树（findByText + findRightOfInput 都带 getLocationOnScreen）
        View cached = cachedSend.get();
        if (cached != null && cached.isAttachedToWindow() && cached.isEnabled()
                && cached.performClick()) {
            return true;
        }
        View btn = findByText(root, 0);
        if (btn == null) {
            btn = findRightOfInput(root, et, 0);
        }
        if (btn == null) {
            return false;
        }
        View clickable = btn;
        for (int i = 0; i < 3 && clickable != null && !clickable.isClickable(); i++) {
            clickable = clickable.getParent() instanceof View ? (View) clickable.getParent() : null;
        }
        if (clickable == null) {
            clickable = btn;
        }
        boolean ok = clickable.performClick();
        if (ok) {
            cachedSend = new WeakReference<>(clickable);
        }
        return ok;
    }

    /** 文字是「发送 / Send」且可见的节点（微信的发送键就是个 TextView）。 */
    private static View findByText(View v, int depth) {
        if (v == null || depth > MAX_DEPTH || (!LOOSE && !v.isShown())) {
            return null;
        }
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String s = cs == null ? "" : cs.toString().trim();
            for (String cand : SEND_TEXTS) {
                if (cand.equals(s)) {
                    return v;
                }
            }
        }
        if (!(v instanceof ViewGroup)) {
            return null;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findByText(g.getChildAt(i), depth + 1);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 兜底：与输入框同一行、在它右侧、可点击的最近节点。 */
    private static View findRightOfInput(View v, EditText et, int depth) {
        if (v == null || depth > MAX_DEPTH || (!LOOSE && !v.isShown())) {
            return null;
        }
        int[] loc = new int[2];
        et.getLocationOnScreen(loc);
        int etLeft = loc[0];
        int etTop = loc[1];
        int etBottom = etTop + et.getHeight();
        View best = null;
        int bestDist = Integer.MAX_VALUE;
        if (v.isClickable() && v != et && v.isEnabled()) {
            int[] l = new int[2];
            v.getLocationOnScreen(l);
            boolean sameRow = l[1] < etBottom && l[1] + v.getHeight() > etTop;
            if (sameRow && l[0] >= etLeft + et.getWidth() - 8) {
                int dist = l[0] - (etLeft + et.getWidth());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = v;
                }
            }
        }
        if (!(v instanceof ViewGroup)) {
            return best;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = findRightOfInput(g.getChildAt(i), et, depth + 1);
            if (c == null) {
                continue;
            }
            int[] l = new int[2];
            c.getLocationOnScreen(l);
            int dist = l[0] - (etLeft + et.getWidth());
            if (best == null || dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ 诊断

    private static void dump(View v, int depth, StringBuilder sb) {
        if (v == null || depth > 18) {
            return;
        }
        for (int i = 0; i < depth; i++) {
            sb.append(' ');
        }
        sb.append(v.getClass().getSimpleName());
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        sb.append(" [").append(l[0]).append(',').append(l[1]).append(',')
                .append(v.getWidth()).append('x').append(v.getHeight()).append(']');
        if (!v.isShown()) {
            sb.append(" hidden");
        }
        if (v.isClickable()) {
            sb.append(" clickable");
        }
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            String s = cs == null ? "" : cs.toString().replace('\n', ' ');
            if (!s.isEmpty()) {
                sb.append(" text=\"").append(shorten(s)).append('"');
            }
        }
        Log.i(TAG, "vtree: " + sb);
        sb.setLength(0);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dump(g.getChildAt(i), depth + 1, sb);
            }
        }
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}