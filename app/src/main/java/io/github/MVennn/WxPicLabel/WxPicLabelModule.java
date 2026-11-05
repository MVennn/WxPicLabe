package io.github.MVennn.WxPicLabel;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.MVennn.WxPicLabel.core.ConfigKeys;
import io.github.MVennn.WxPicLabel.feature.imglabel.RepeatCounter;
import io.github.MVennn.WxPicLabel.feature.imglabel.UiSender;
import io.github.MVennn.WxPicLabel.util.Reflect;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 微信「发图带文件名」——相册只当选图器，发送交给本模块。
 *
 * <p>生效链路（微信 8.0.80 / versionCode 3220 实测）：
 *
 * <pre>
 *  相册点「发送」
 *     └─ on5.f#a(qa0.k params, …, Runnable 回调)      ← 拦这里，不放行微信的批量发送
 *          ├─ 调它自己的 Runnable，让相册界面收工（聊天页回到前台）
 *          └─ 逐张重放：params.g 只留第 k 条 → 调 on5.f#a → 等 xm5.y#Fj（转圈消失）→ UiSender 发文件名
 * </pre>
 *
 * <p>兜底策略：任何一环不可用（类名漂移、重放抛异常、开关关闭）都退回微信原生行为，
 * 并把这批文件名交给 fallback 队列，等聊天页回来按固定间隔补发 —— 图一定发得出去，
 * 最差退化成「图先字后」。
 */
public class WxPicLabelModule extends XposedModule {

    private static final String TAG = "WxPicLabel";
    private static final String WECHAT = "com.tencent.mm";
    /** 调试属性统一前缀：debug.wxpiclabel.xxx */
    private static final String PROP = "debug.wxpiclabel.";

    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile ClassLoader wxLoader;
    private volatile ClassLoader realLoader;
    /** 已挂过的钩子（按 类名#方法/参数个数@loader 去重，避免重复安装）。 */
    private final Set<String> hooked = new HashSet<>();

    // ------------------------------------------------------------------ 接管状态

    /** 我们自己重放 on5.f#a 时的递归保护。 */
    private final ThreadLocal<Boolean> replayGuard = new ThreadLocal<>();
    private volatile Method entryMethod;
    private volatile Object entryThis;
    private volatile Object[] entryArgs;
    private volatile Method vjMethod;
    private volatile Object vjService;
    /** 单张图「发送成功」事件计数（xm5.y#Fj）。 */
    private final AtomicInteger imageSent = new AtomicInteger();
    /** 连续几张小图等不到成功事件；到 2 次就不再等，直接按固定间隔走。 */
    private volatile int sentMisses;
    /** 接管期间禁止兜底队列抢发。 */
    private volatile long takeoverUntil;

    // ------------------------------------------------------------------ 兜底队列

    private volatile ArrayList<String> pendingLabels;
    private volatile int pendingIndex;
    private volatile long pendingAt;

    // ------------------------------------------------------------------ 配置与计数

    /** remote preferences（目标进程侧只读）；框架不支持时为 null，一律走默认值。 */
    private volatile SharedPreferences cfgPrefs;
    /** 同名图片计数仓库（落在微信自己的 SharedPreferences 里）。 */
    private volatile RepeatCounter repeat;
    /** 本批「选图条目 → 要发的文字」缓存，防止同一个条目被计两次次数。 */
    private volatile IdentityHashMap<Object, String> batchTexts;

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!WECHAT.equals(param.getPackageName())) {
            return;
        }
        wxLoader = param.getDefaultClassLoader() != null
                ? param.getDefaultClassLoader()
                : WxPicLabelModule.class.getClassLoader();
        log("wechat loaded");
        installAll(wxLoader, false);
        startInstallPoller();
        startFallbackPoller();
    }

    /**
     * 等 Application 出现后，用它的 ClassLoader 再装一遍钩子。
     *
     * <p>微信的混淆类在壳 loader 和 Tinker {@code DelegateLastClassLoader} 上可能是**两份**，
     * 壳 loader 上装的钩子不会命中真实执行的那份（实测 Vj 钩子曾整轮零命中）。
     */
    private void startInstallPoller() {
        final int[] ticks = {0};
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (++ticks[0] > 40) {
                    log("install poller: give up waiting for application loader");
                    return;
                }
                try {
                    ClassLoader cl = appClassLoader();
                    if (cl != null) {
                        if (cl != realLoader) {
                            realLoader = cl;
                            log("app classloader ready: " + cl.getClass().getName());
                            installAll(cl, true);
                        }
                        return;
                    }
                } catch (Throwable t) {
                    log("install poller failed: " + t);
                }
                main.postDelayed(this, 500L);
            }
        };
        main.postDelayed(poll, 500L);
    }

    /** 装全部钩子；每个都独立 try/catch，任何一个失败只是少一个能力。 */
    private void installAll(ClassLoader cl, boolean real) {
        hookTakeover(cl);
        hookBatchEntry(cl);
        hookImageSent(cl);
        if (real) {
            log("hooks installed on real loader");
        }
    }

    // ------------------------------------------------------------------ 钩子：接管发送

    /**
     * 相册发送总入口 {@code on5.f#a}（返回 void）。
     *
     * <p>不放行 = 微信这一批不发了，改由本模块逐张发；先调它自己的 Runnable 让相册收工，
     * 这样聊天页回到前台，文字才发得出去。
     */
    private void hookTakeover(ClassLoader cl) {
        Class<?> cls = Reflect.findClass("on5.f", cl);
        if (cls == null) {
            log("takeover: on5.f not found, fallback only");
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!"a".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            int paramsIdx = -1;
            int callbackIdx = -1;
            for (int i = 0; i < pt.length; i++) {
                if ("qa0.k".equals(pt[i].getName())) {
                    paramsIdx = i;
                }
                if (Runnable.class.isAssignableFrom(pt[i])) {
                    callbackIdx = i;
                }
            }
            if (paramsIdx < 0 || callbackIdx < 0) {
                continue;
            }
            final int pi = paramsIdx;
            final int ci = callbackIdx;
            if (!markHooked("on5.f#a/" + pt.length + "@" + System.identityHashCode(cls.getClassLoader()))) {
                break;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!takeoverEnabled() || !featureEnabled()
                                    || Boolean.TRUE.equals(replayGuard.get())) {
                                return chain.proceed();
                            }
                            Object params = chain.getArg(pi);
                            Object callback = chain.getArg(ci);
                            try {
                                entryMethod = m;
                                entryThis = chain.getThisObject();
                                entryArgs = chain.getArgs().toArray();
                                if (!ensureReplayReady()) {
                                    log("takeover: replay not ready, let wechat send");
                                    return chain.proceed();
                                }
                                log("takeover: intercepted, params=" + className(params));
                                if (callback instanceof Runnable) {
                                    main.post((Runnable) callback);   // 相册收工，别卡「正在发送中」
                                }
                                startReplay(params);
                                return null;
                            } catch (Throwable t) {
                                log("takeover: intercept failed, fallback to wechat: " + t);
                                return chain.proceed();
                            }
                        });
                log("hooked takeover entry: on5.f#a/" + pt.length);
                return;
            } catch (Throwable t) {
                log("hook takeover failed: " + t);
            }
        }
    }

    /** 备好逐张重放需要的东西：入口参数里的选图列表 + db0.u#Vj 的服务实例。 */
    private boolean ensureReplayReady() {
        if (vjMethod == null) {
            ClassLoader cl = loader();
            Class<?> u = Reflect.findClass("db0.u", cl);
            if (u == null) {
                log("takeover: db0.u not found");
                return false;
            }
            for (Method m : u.getDeclaredMethods()) {
                if ("Vj".equals(m.getName()) && m.getParameterCount() == 1) {
                    m.setAccessible(true);
                    vjMethod = m;
                    break;
                }
            }
            if (vjMethod == null) {
                log("takeover: db0.u#Vj not found");
                return false;
            }
            vjService = locateService(u);
        }
        if (vjService == null) {
            log("takeover: db0.u service not resolved");
            return false;
        }
        return true;
    }

    /** 微信自己的服务定位器 {@code fi5.n0#c(Class)}。 */
    private Object locateService(Class<?> key) {
        Class<?> n0 = Reflect.findClass("fi5.n0", loader());
        if (n0 == null) {
            return null;
        }
        for (Method m : n0.getDeclaredMethods()) {
            if ("c".equals(m.getName()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Class.class) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, key);
                } catch (Throwable t) {
                    log("locateService failed: " + t);
                }
            }
        }
        return null;
    }

    /** 后台线程逐张发：图 k → 等它发送成功 → 文件名 k → 下一张。 */
    private void startReplay(Object params) {
        takeoverUntil = System.currentTimeMillis() + 180_000L;
        UiSender.preClickMs = (int) longProp("preclick", 120L);
        UiSender.postClickMs = (int) longProp("postclick", 150L);

        // 本批的文字缓存：同一个条目只计一次次数
        batchTexts = new IdentityHashMap<>();
        // 兜底队列若已为这一批算过文字（db0.u#Vj 先到），直接复用；
        // 同时把它清掉，免得接管发完以后轮询又把同一批文字补一遍。
        final ArrayList<String> preLabels = pendingLabels;
        pendingLabels = null;

        new Thread(() -> {
            List<?> origin = null;
            Field listField = findListField(params);
            try {
                if (listField == null) {
                    log("takeover: no list field on " + className(params) + ", let wechat send");
                    replayOriginal();
                    return;
                }
                List<?> src = (List<?>) listField.get(params);
                if (src == null || src.isEmpty()) {
                    log("takeover: empty selection, let wechat send");
                    replayOriginal();
                    return;
                }
                origin = new ArrayList<>(src);
                log("takeover: entries=" + origin.size());

                int sent = 0;
                List<Object> failed = new ArrayList<>();
                for (int k = 0; k < origin.size(); k++) {
                    Object entry = origin.get(k);
                    try {
                        ArrayList<Object> one = new ArrayList<>();
                        one.add(entry);
                        listField.set(params, one);
                        replayOne();
                        sent++;
                        waitImageSent(k + 1, origin.size());
                        String label = preLabels != null && preLabels.size() == origin.size()
                                ? preLabels.get(k)
                                : textFor(entry);
                        if (!sendLabel(label, k + 1, origin.size())) {
                            log("takeover: text#" + (k + 1) + " not sent");
                        }
                        sleepQuietly(longProp("gap", 0L));
                    } catch (Throwable t) {
                        log("takeover: image#" + (k + 1) + " failed: " + t);
                        failed.add(entry);
                    }
                }
                listField.set(params, origin);
                takeoverUntil = 0L;
                log("takeover: done, sent=" + sent + " failed=" + failed.size());
                if (!failed.isEmpty()) {
                    // 只把没发出去的交回微信，已发过的绝不重发
                    listField.set(params, failed);
                    replayOriginal();
                }
            } catch (Throwable t) {
                log("takeover: replay aborted: " + t);
                restoreAndFallback(params, listField, origin);
            }
        }, "wxpiclabel-takeover").start();
    }

    /** 调一次微信的发送入口（列表里当前只有一张）。 */
    private void replayOne() throws Throwable {
        Method m = entryMethod;
        Object[] args = entryArgs;
        if (m == null || args == null) {
            throw new IllegalStateException("entry not captured");
        }
        replayGuard.set(Boolean.TRUE);
        try {
            m.setAccessible(true);
            Object ret = Modifier.isStatic(m.getModifiers()) ? m.invoke(null, args) : m.invoke(entryThis, args);
            log("takeover: entry replay -> " + ret);
        } finally {
            replayGuard.remove();
        }
    }

    /** 兜底：把微信原来那次调用补回去，保证图照样能发。 */
    private void replayOriginal() {
        Method m = entryMethod;
        Object[] args = entryArgs;
        if (m == null || args == null) {
            log("takeover: nothing to replay");
            return;
        }
        replayGuard.set(Boolean.TRUE);
        try {
            m.setAccessible(true);
            Object r = Modifier.isStatic(m.getModifiers()) ? m.invoke(null, args) : m.invoke(entryThis, args);
            log("takeover: wechat original send replayed -> " + r);
        } catch (Throwable t) {
            log("takeover: replay original failed: " + t);
        } finally {
            replayGuard.remove();
        }
    }

    /** 异常路径：列表恢复成全部，交回微信，并把文件名交给兜底队列。 */
    private void restoreAndFallback(Object params, Field listField, List<?> origin) {
        try {
            if (listField != null && origin != null) {
                listField.set(params, origin);
            }
        } catch (Throwable ignored) {
            // 恢复失败也只能继续兜底
        }
        takeoverUntil = 0L;
        if (origin != null) {
            ArrayList<String> labels = new ArrayList<>();
            for (Object o : origin) {
                labels.add(textFor(o));   // 命中本批缓存，不会重复计数
            }
            armFallback(labels, 0);
        }
        replayOriginal();
    }

    /** 等这一张图真正发出去（气泡上的转圈消失）。 */
    private void waitImageSent(int idx, int total) {
        if (sentMisses >= 2) {
            sleepQuietly(longProp("intogap", 0L));
            return;
        }
        long start = System.currentTimeMillis();
        int before = imageSent.get();
        long deadline = start + longProp("confirmtimeout", 12_000L);
        while (System.currentTimeMillis() < deadline) {
            if (imageSent.get() > before) {
                sentMisses = 0;
                log("takeover: image#" + idx + "/" + total + " sent-confirmed after "
                        + (System.currentTimeMillis() - start) + "ms");
                sleepQuietly(longProp("confirmgap", 0L));
                return;
            }
            sleepQuietly(60L);
        }
        sentMisses++;
        log("takeover: image#" + idx + "/" + total + " no send-confirm in "
                + (System.currentTimeMillis() - start) + "ms, misses=" + sentMisses);
        sleepQuietly(longProp("intogap", 0L));
    }

    /** 发一条文件名；成功排进 UI 队列就返回 true。 */
    private boolean sendLabel(String label, int idx, int total) {
        final CountDownLatch latch = new CountDownLatch(1);
        boolean queued = UiSender.send(main, label, latch::countDown);
        log("takeover: text#" + idx + "/" + total + " label=" + label + " queued=" + queued);
        if (queued) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    log("takeover: text#" + idx + " timeout");
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 钩子：批量入口 / 成功事件

    /**
     * {@code db0.u#Vj(qa0.k)}：这一批图要开始准备了。
     *
     * <p>只在**兜底**链路用到 —— 接管没跑起来时，靠它的实参拿到选图顺序，
     * 等聊天页回来后按间隔补发文件名。
     */
    private void hookBatchEntry(ClassLoader cl) {
        Class<?> u = Reflect.findClass("db0.u", cl);
        if (u == null) {
            return;
        }
        for (Method m : u.getDeclaredMethods()) {
            if (!"Vj".equals(m.getName()) || m.getParameterCount() != 1) {
                continue;
            }
            if (!markHooked("Vj@" + System.identityHashCode(cl))) {
                continue;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!Boolean.TRUE.equals(replayGuard.get())) {
                                armFromParams(chain.getArg(0));
                            }
                            return chain.proceed();
                        });
                log("hooked batch entry: db0.u#Vj");
            } catch (Throwable t) {
                log("hook Vj failed: " + t);
            }
        }
    }

    /** {@code xm5.y#Fj(f9)}：单张发送成功（气泡转圈消失），接管用它当打字节拍。 */
    private void hookImageSent(ClassLoader cl) {
        Class<?> cls = Reflect.findClass("xm5.y", cl);
        if (cls == null) {
            log("send-succ: xm5.y not found (fallback to fixed gap)");
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (!"Fj".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            int f9Idx = -1;
            for (int i = 0; i < pt.length; i++) {
                if ("com.tencent.mm.storage.f9".equals(pt[i].getName())) {
                    f9Idx = i;
                    break;
                }
            }
            if (f9Idx < 0) {
                continue;
            }
            final int fi = f9Idx;
            if (!markHooked("Fj" + pt.length + "@" + System.identityHashCode(cl))) {
                continue;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object ret = chain.proceed();
                            try {
                                if (isImage(chain.getArg(fi))) {
                                    imageSent.incrementAndGet();
                                }
                            } catch (Throwable ignored) {
                                // 只做计数
                            }
                            return ret;
                        });
                log("hooked send-succ: xm5.y#Fj/" + pt.length + " f9Idx=" + fi);
            } catch (Throwable t) {
                log("hook send-succ failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ 兜底队列

    /** 用 Vj 的实参武装兜底队列（只在接管不生效时才会走到）。 */
    private void armFromParams(Object params) {
        if (params == null || pendingLabels != null || !featureEnabled()) {
            return;
        }
        // 走到这里说明这一批没有接管（接管会先清掉 pendingLabels），
        // 缓存属于上一批，换一份新的再算。
        batchTexts = new IdentityHashMap<>();
        Field f = findListField(params);
        if (f == null) {
            return;
        }
        try {
            List<?> list = (List<?>) f.get(params);
            if (list == null || list.isEmpty()) {
                return;
            }
            ArrayList<String> labels = new ArrayList<>();
            for (Object o : list) {
                labels.add(textFor(o));
            }
            armFallback(labels, 0);
            log("fallback: armed at Vj, labels=" + labels);
        } catch (Throwable t) {
            log("fallback: arm failed: " + t);
        }
    }

    private void armFallback(ArrayList<String> labels, int from) {
        if (labels == null || labels.isEmpty()) {
            return;
        }
        pendingIndex = Math.max(0, from);
        pendingLabels = labels;
        pendingAt = System.currentTimeMillis();
    }

    /** 轮询：接管没在跑、聊天页可用、而且还有没发出去的文件名，就按间隔补发。 */
    private void startFallbackPoller() {
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                try {
                    flushFallback();
                } catch (Throwable t) {
                    log("fallback: flush failed: " + t);
                }
                main.postDelayed(this, 700L);
            }
        };
        main.postDelayed(poll, 1200L);
    }

    private void flushFallback() {
        ArrayList<String> labels = pendingLabels;
        if (labels == null) {
            return;
        }
        if (!featureEnabled()) {
            pendingLabels = null;
            return;
        }
        if (System.currentTimeMillis() < takeoverUntil) {
            return;   // 接管正在逐张发，别抢
        }
        if (System.currentTimeMillis() - pendingAt > 120_000L) {
            log("fallback: expire, dropped " + (labels.size() - pendingIndex));
            pendingLabels = null;
            return;
        }
        if (!UiSender.hasInput()) {
            return;   // 聊天页还没回来
        }
        int i = pendingIndex;
        if (i >= labels.size()) {
            pendingLabels = null;
            return;
        }
        String label = labels.get(i);
        pendingIndex = i + 1;
        pendingAt = System.currentTimeMillis();
        boolean ok = UiSender.send(main, label, null);
        log("fallback: label#" + (i + 1) + "/" + labels.size() + " " + label + " uiSent=" + ok);
        if (pendingIndex >= labels.size()) {
            pendingLabels = null;
        }
    }

    // ------------------------------------------------------------------ 配置与计数

    /** remote preferences（目标进程侧只读）；框架不支持时返回 null，调用方一律走默认值。 */
    private SharedPreferences cfgPrefs() {
        SharedPreferences p = cfgPrefs;
        if (p != null) {
            return p;
        }
        try {
            p = getRemotePreferences(ConfigKeys.GROUP);
            cfgPrefs = p;
            log("config: remote prefs attached");
        } catch (Throwable t) {
            log("config: remote prefs unavailable: " + t);
        }
        return p;
    }

    private boolean cfgBool(String key, boolean def) {
        SharedPreferences p = cfgPrefs();
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private String cfgStr(String key, String def) {
        SharedPreferences p = cfgPrefs();
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, def);
            return v == null ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 「发送图片文件名」总开关：关掉就完全不接管，退回微信原生批量发送。 */
    private boolean featureEnabled() {
        return cfgBool(ConfigKeys.IMG_LABEL, ConfigKeys.DEFAULT_IMG_LABEL);
    }

    /** 计数仓库懒加载；建不出来就返回 null（调用方按第 1 次处理）。 */
    private RepeatCounter repeatCounter() {
        RepeatCounter r = repeat;
        if (r != null) {
            return r;
        }
        try {
            Context ctx = appContext();
            if (ctx == null) {
                return null;
            }
            SharedPreferences store = ctx.getSharedPreferences(
                    "wxpiclabel_repeat", Context.MODE_PRIVATE);
            r = new RepeatCounter(store);
            repeat = r;
        } catch (Throwable t) {
            log("repeat: store unavailable: " + t);
        }
        return r;
    }

    /**
     * 这个选图条目要发的文字：原名，或「原名 +N」。
     *
     * <p>认名字不认内容 —— 第二天重新截的同名图，内容变了也接着数。
     * 同一个条目在一批里可能被问两次（接管算一遍；失败后交回微信、{@code db0.u#Vj}
     * 再算一遍），用按**条目对象身份**的缓存挡住重复计数。
     */
    private String textFor(Object entry) {
        String label = labelOfEntry(entry);
        if (label == null || label.isEmpty() || "unknown".equals(label)) {
            return label;
        }
        IdentityHashMap<Object, String> cache = batchTexts;
        if (cache != null) {
            String hit = cache.get(entry);
            if (hit != null) {
                return hit;
            }
        }
        if (!cfgBool(ConfigKeys.IMG_LABEL_REPEAT_COUNT, ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_COUNT)) {
            return label;
        }
        int n = 1;
        RepeatCounter c = repeatCounter();
        if (c != null) {
            n = c.bump(label, cfgStr(
                    ConfigKeys.IMG_LABEL_REPEAT_RESET, ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_RESET));
        }
        String text = n >= 2 ? label + " +" + n : label;
        if (cache != null) {
            cache.put(entry, text);
        }
        log("repeat: label=" + label + " count=" + n + " text=" + text);
        return text;
    }

    // ------------------------------------------------------------------ 工具

    private ClassLoader loader() {
        ClassLoader cl = realLoader;
        return cl != null ? cl : wxLoader;
    }

    private boolean markHooked(String key) {
        synchronized (hooked) {
            return hooked.add(key);
        }
    }

    private static Field findListField(Object obj) {
        if (obj == null) {
            return null;
        }
        for (Field fd : obj.getClass().getDeclaredFields()) {
            try {
                fd.setAccessible(true);
                if (fd.get(obj) instanceof List) {
                    return fd;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /** 选图片段（qa0.i / String / Uri）→ 文件名。 */
    private String labelOfEntry(Object it) {
        String path = null;
        try {
            if (it instanceof String) {
                path = (String) it;
            } else if (it != null) {
                Object a = Reflect.getFieldByNames(it, "a");
                Object b = Reflect.getFieldByNames(it, "b");
                if (a instanceof String && ((String) a).startsWith("/")) {
                    path = (String) a;
                } else if (b instanceof Uri) {
                    Uri uri = (Uri) b;
                    path = "file".equals(uri.getScheme()) ? uri.getPath() : resolveContentUri(uri);
                }
                if (path == null && a instanceof String && !((String) a).isEmpty()) {
                    path = (String) a;
                }
            }
        } catch (Throwable t) {
            log("labelOfEntry failed: " + t);
        }
        return path == null ? "unknown" : labelOf(path);
    }

    private static boolean isImage(Object msg) {
        if (msg == null) {
            return false;
        }
        try {
            for (Method m : msg.getClass().getDeclaredMethods()) {
                if ("getType".equals(m.getName()) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    Object v = m.invoke(msg);
                    return v instanceof Integer && (Integer) v == 3;
                }
            }
        } catch (Throwable ignored) {
            // 判不出来就当不是
        }
        return false;
    }

    private static String className(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    /** 去掉扩展名，作为发出去的文字。 */
    static String labelOf(String path) {
        if (path == null) {
            return "";
        }
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** content:// 的 Uri 只有媒体库 ID，查库换真实路径。 */
    private static String resolveContentUri(Uri uri) {
        Context ctx = appContext();
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[]{"_data", "_display_name"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int di = c.getColumnIndex("_data");
                if (di >= 0) {
                    String d = c.getString(di);
                    if (d != null && !d.isEmpty()) {
                        return d;
                    }
                }
                int ni = c.getColumnIndex("_display_name");
                if (ni >= 0) {
                    String n = c.getString(ni);
                    if (n != null && !n.isEmpty()) {
                        return "/virtual/" + n;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "resolve uri failed: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassLoader appClassLoader() {
        Context ctx = appContext();
        return ctx == null ? null : ctx.getClassLoader();
    }

    private boolean takeoverEnabled() {
        String v = prop("takeover");
        return v == null || v.isEmpty() || !"0".equals(v.trim());
    }

    private static long longProp(String name, long def) {
        try {
            String v = prop(name);
            if (v == null || v.isEmpty()) {
                return def;
            }
            long n = Long.parseLong(v.trim());
            return n < 0 ? def : n;
        } catch (Throwable t) {
            return def;
        }
    }

    private static String prop(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object v = get.invoke(null, PROP + name);
            return v instanceof String ? (String) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void log(String msg) {
        Log.i(TAG, msg);
        try {
            super.log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) {
            // 模块还没 attach 时忽略
        }
    }
}
