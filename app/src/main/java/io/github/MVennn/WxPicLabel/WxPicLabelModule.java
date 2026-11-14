package io.github.MVennn.WxPicLabel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.github.MVennn.WxPicLabel.core.ConfigKeys;
import io.github.MVennn.WxPicLabel.feature.imglabel.AlbumMemoryToggle;
import io.github.MVennn.WxPicLabel.feature.imglabel.FriendExporter;
import io.github.MVennn.WxPicLabel.feature.imglabel.RecordExporter;
import io.github.MVennn.WxPicLabel.feature.imglabel.RenameRules;
import io.github.MVennn.WxPicLabel.feature.imglabel.RepeatCounter;
import io.github.MVennn.WxPicLabel.feature.imglabel.RepeatWindow;
import io.github.MVennn.WxPicLabel.feature.imglabel.ScopeGate;
import io.github.MVennn.WxPicLabel.feature.imglabel.TextFilter;
import io.github.MVennn.WxPicLabel.feature.imglabel.UiHud;
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
    /** 常驻轮询的后台线程：配置检查与记录回传不占主线程（发完图上滑掉帧的根因）。 */
    private volatile HandlerThread pollThread;
    private volatile Handler pollWorker;
    /** android.os.SystemProperties#get(String) 的进程级缓存，省掉每轮两次 Class.forName + getMethod。 */
    private static volatile Method propMethod;
    /** isImage 按消息 Class 缓存命中的 getType() Method；空数组表示查过但没匹配。 */
    private static final ConcurrentHashMap<Class<?>, Method[]> imageTypeMethods = new ConcurrentHashMap<>();
    /**
     * findListField 的按类缓存。
     *
     * <p>原来每批（接管一批 + 兜底一批）都要把参数类的全部字段反射扫一遍、逐个 fd.get
     * 判类型；同一个 qa0.k 类每批都长一个样，扫第二遍纯属白做。命中后仍按实例验一次
     * 值确实是 List —— 该字段在本实例上为 null 时退回全扫描，行为与改前逐字一致。
     */
    private static final ConcurrentHashMap<Class<?>, Field> listFieldCache = new ConcurrentHashMap<>();
    /** talkerOfArg 的 ia5.a#o() 按类缓存；MISSING 哨兵表示这个类没有 0 参 o()。 */
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<Class<?>, Object> talkerArgMethods = new ConcurrentHashMap<>();
    /** ActivityThread#currentApplication 的反射句柄与结果缓存（热路径上不再每次 Class.forName）。 */
    private static volatile Method currentApplicationMethod;
    private static volatile Context appContextCache;
    /** 兜底 flush 同一时刻最多挂一个；onPackageLoaded 重装时轮询只建一次。 */
    private final AtomicBoolean flushPending = new AtomicBoolean(false);
    private final AtomicBoolean pollerStarted = new AtomicBoolean(false);
    /** 好友名单同步的在途闸：并发刷新只跑一条，别为了点两下「刷新」起两条线程抢 rcontact。 */
    private final AtomicBoolean friendSyncing = new AtomicBoolean(false);
    /** 是否主进程（见 onModuleLoaded）。 */
    private volatile boolean mainProcess;

    private volatile ClassLoader wxLoader;
    private volatile ClassLoader realLoader;
    /** 已挂过的钩子（按 类名#方法/参数个数@loader 去重，避免重复安装）。 */
    private final Set<String> hooked = new HashSet<>();

    // ------------------------------------------------------------------ 接管状态

    /** 我们自己重放 on5.f#a 时的递归保护。 */
    private final ThreadLocal<Boolean> replayGuard = new ThreadLocal<>();
    /**
     * 一次接管的完整重放上下文（入口方法 + 目标对象 + 实参）。
     *
     * <p>改前这里是三个 volatile 字段，第二代接管的调用会把第一代的实参就地覆盖：
     * 上一批正在逐张重放时，第二批一进来就把 {@code entryArgs} 换成自己的，
     * 上一批随后 {@code invoke} 出去的就是第二批的参数 —— 轻则图文错位，
     * 重则整批抛「entry not captured」，两边的图都发不出去。
     * 改成每批一份、挂在接管线程的 ThreadLocal 上，两批各用各的，互不覆盖。
     */
    private static final class ReplayCtx {
        final Method method;
        final Object self;
        final Object[] args;

        ReplayCtx(Method method, Object self, Object[] args) {
            this.method = method;
            this.self = self;
            this.args = args;
        }

        boolean ready() {
            return method != null && args != null;
        }
    }

    /** 接管线程自己的重放上下文；replayOne / replayOriginal 都从这里取。 */
    private final ThreadLocal<ReplayCtx> replayCtx = new ThreadLocal<>();

    /**
     * 「现在有一批正在逐张重放」的独占位。
     *
     * <p>只看 {@code takeoverUntil} 存在毫秒级空档：两个入口线程同时进来、都读到
     * 上一批的 0，就会双双接管，抢同一个发送成功计数。改成 CAS：先到先得，
     * 后来的那一批直接排进兜底队列。
     */
    private final AtomicBoolean replayActive = new AtomicBoolean(false);
    private volatile Method vjMethod;
    private volatile Object vjService;
    /** 单张图「发送成功」事件计数（xm5.y#Fj）。 */
    private final AtomicInteger imageSent = new AtomicInteger();
    /**
     * 计数变化的等待闸：钩子侧 notifyAll，接管线程 wait 到有新事件为止。
     *
     * <p>改前是 {@code sleepQuietly(20L)} 忙等，一张图最多等满 confirmtimeout（12 秒）
     * 就是 600 次无意义唤醒；换成等待/唤醒后同一张图通常 0 次空转，唤醒即走。
     */
    private final Object sentSignal = new Object();
    /** 连续几张小图等不到成功事件；到 2 次就不再等，直接按固定间隔走。 */
    private volatile int sentMisses;
    /** 接管期间禁止兜底队列抢发。 */
    private volatile long takeoverUntil;

    /** 最近一次发送的会话 id（接管与兜底共用，白名单判定要用）。 */
    private volatile String lastTalker;
    /** 已处理过的「刷新好友名单」令牌，轮询里比对。 */
    private volatile String lastScopeRefresh;

    // ------------------------------------------------------------------ 兜底队列

    /** 兜底队列：每一格带「文字 + 这次的序号」，见 {@link Draft}。 */
    private volatile ArrayList<Draft> pendingDrafts;
    private volatile int pendingIndex;
    private volatile long pendingAt;

    // ------------------------------------------------------------------ 配置与计数

    /** remote preferences（目标进程侧只读）；框架不支持时为 null，一律走默认值。 */
    private volatile SharedPreferences cfgPrefs;
    /** 上次附着 remote preferences 的时刻（毫秒）。 */
    private volatile long cfgAttachedAt;
    /**
     * 重新附着的周期。
     *
     * <p>目标进程侧拿到的 remote preferences 是**附着那一刻的快照**，不重取的话
     * 模块 App 改的配置这个进程永远读不到 —— 实测「刷新名单」按钮因此长期无效
     * （令牌写进去了，微信进程直到下次重启才「发现」它变了）。
     */
    private static final long CFG_TTL_MS = 3000L;
    /** 重附着在途标记：一次没回来之前不排队第二次（见 cfgPrefs 的注释）。 */
    private final AtomicBoolean cfgAttaching = new AtomicBoolean();
    /** 白名单集合的最近一次读数；「快照时间戳 + 键名」一起比对，快照换了就自然失效。 */
    private volatile String cfgSetKey;
    private volatile long cfgSetStamp = -1L;
    private volatile Set<String> cfgSetValue;
    /** 同名图片计数仓库（落在微信自己的 SharedPreferences 里）。 */
    private volatile RepeatCounter repeat;
    /** 本批「选图条目 → 要发的文字 + 序号」缓存，防止同一个条目被计两次次数。 */
    private volatile IdentityHashMap<Object, Draft> batchTexts;
    /** 「追加次数设置」解析结果缓存（原始字符串没变就复用，翻配置时不用每次重新切段）。 */
    private volatile RepeatWindow windowCache;
    private volatile String windowCacheRaw;
    /** 重命名规则表缓存（原始字符串没变就复用解析结果）。 */
    private volatile java.util.Map<String, String> renameCache;
    private volatile String renameCacheRaw;
    /** 文字过滤关键字表缓存；大小写开关也参与比对，翻一下就重新解析。 */
    private volatile java.util.List<TextFilter.Rule> filterCache;
    private volatile String filterCacheRaw;
    private volatile boolean filterCacheIgnoreCase;
    /** 已处理过的「回传发送记录」令牌。 */
    private volatile String lastRecordDump;
    /** 当前批次的 id：同一批发送的事件归成一组，记录页按它折叠。 */
    private volatile String curBatch;
    /** 已处理过的「改 / 删发送记录」令牌。 */
    private volatile String lastEditsToken;
    /** 已处理过的「清空发送记录」令牌（轮询侧比对；发送链路自己也会比一次）。 */
    private volatile String lastResetToken;
    /** 好友回传重试的下一次时间点；0 = 没有待重试。 */
    private volatile long friendRetryAt;
    /** 好友回传连续失败次数：退避 5s×(1+n)，上限 60s。 */
    private volatile int friendFailures;
    /** 批次自增号：同一毫秒起两批也不会撞 id。 */
    private final java.util.concurrent.atomic.AtomicInteger batchSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log("module loaded, process=" + param.getProcessName());
        // 主进程 = 进程名就是包名（:push / :tools 这类子进程不带 UI）。
        // HUD 的 Toast、兜底轮询、自测通道都只在主进程跑：
        // 子进程各弹一遍 Toast 会把系统 Toast 队列排成一串，进度永远停在 1/N。
        mainProcess = WECHAT.equals(param.getProcessName());
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
        startFriendProbe();
    }

    /**
     * 探针：微信起来 12 秒后（等它自己的库打开）在后台读一次好友与群聊。
     *
     * <p>只打日志、不改任何数据。换成正式的名单回传之前，先用它验证
     * {@link FriendExporter} 在当前微信版本上真的能拿到 rcontact 里的数据。
     */
    private void startFriendProbe() {
        main.postDelayed(() -> requestFriendSync("startup"), 12_000L);
    }

    /**
     * 抓一遍联系人并回传给模块 App。
     *
     * <p>启动后自动跑一次（等微信自己的库打开），设置页点「刷新」时由令牌触发再跑。
     * 抓的是**当前登录账号**的 rcontact，所以换号即换名单。
     */
    /** @return 是否真的起了这一轮抓取（在途闸拒绝时返回 false，调用方据此决定要不要再排期） */
    private boolean requestFriendSync(final String why) {
        // 在途闸：启动探针与「刷新」令牌可能挨着触发（点两下也会），
        // 两条线程同时抓 rcontact 既慢又白占内存，只放第一条进去。
        if (!friendSyncing.compareAndSet(false, true)) {
            log("friends: sync already running, skip reason=" + why);
            return false;
        }
        Thread sync = new Thread(() -> {
            try {
                List<FriendExporter.Entry> list = FriendExporter.fetch(loader(), this::log);
                int friends = 0;
                int rooms = 0;
                for (FriendExporter.Entry e : list) {
                    if (e.kind == FriendExporter.KIND_CHATROOM) {
                        rooms++;
                    } else {
                        friends++;
                    }
                }
                log("friends: fetched " + list.size() + " (friends=" + friends
                        + ", rooms=" + rooms + ") reason=" + why);
                Context ctx = appContext();
                if (ctx == null) {
                    log("friends: no app context, skip push");
                    return;
                }
                if (FriendExporter.push(ctx, list, this::log)) {
                    friendFailures = 0;
                    friendRetryAt = 0L;
                } else {
                    scheduleFriendRetry("push-failed");
                }
            } catch (Throwable t) {
                log("friends: sync failed: " + t);
                // 抓取失败也要重试：启动探针 12 秒时微信自己的库可能还没开
                scheduleFriendRetry("fetch-failed");
            } finally {
                friendSyncing.set(false);
            }
        }, "wxpiclabel-friends-sync");
        // daemon：这条抓取线程不能拖住微信进程退出（改前默认非守护线程）
        sync.setDaemon(true);
        sync.start();
        return true;
    }

    /** 排一次好友回传重试：5s×(1+失败次数)，上限 60s —— 与发送记录回传同一口径。 */
    private void scheduleFriendRetry(String why) {
        int n = friendFailures;
        friendFailures = n + 1;
        long delay = Math.min(60_000L, 5_000L * (1L + n));
        friendRetryAt = System.currentTimeMillis() + delay;
        log("friends: retry in " + delay + "ms reason=" + why);
    }

    /** 轮询侧：到点就再抓一次；在途闸拒绝时 3 秒后重排，不让重试链断掉。 */
    private void checkFriendRetry() {
        long at = friendRetryAt;
        if (at == 0L || System.currentTimeMillis() < at) {
            return;
        }
        if (requestFriendSync("retry")) {
            friendRetryAt = 0L;
        } else {
            friendRetryAt = System.currentTimeMillis() + 3_000L;
        }
    }

    /** 设置页点了「刷新好友名单」就把令牌换一个新值，这里比对后重新抓取。 */
    private void checkScopeRefresh() {
        String refreshMark = cfgStr(ConfigKeys.IMG_LABEL_SCOPE_REFRESH, "");
        if (refreshMark.equals(lastScopeRefresh)) {
            return;
        }
        lastScopeRefresh = refreshMark;
        if (refreshMark.isEmpty()) {
            return;
        }
        log("friends: refresh token changed, re-sync");
        requestFriendSync("refresh-token");
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
        hookAlbumToggle(cl);
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
            // 接管靠「拦截里 return null」吃掉这一批：只认返回 void 的重载。
            // 万一某个微信版本的 a() 返回 boolean/int，返回 null 会让宿主当场拆箱崩掉
            // —— 那等于把一次优化做成微信闪退。返回类型不对就只走兜底队列。
            if (m.getReturnType() != void.class) {
                log("takeover: skip non-void a/" + pt.length + " returns "
                        + m.getReturnType().getName());
                continue;
            }
            final int pi = paramsIdx;
            final int ci = callbackIdx;
            final String hookKey = "on5.f#a/" + pt.length + "@"
                    + System.identityHashCode(cls.getClassLoader());
            if (isHooked(hookKey)) {
                return;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            try {
                                if (!takeoverEnabled()) {
                                    log("takeover: disabled by prop, let wechat send");
                                    return chain.proceed();
                                }
                                if (!featureEnabled()) {
                                    log("takeover: feature off, let wechat send");
                                    return chain.proceed();
                                }
                                if (!memoryOn()) {
                                    log("takeover: memory off, let wechat send");
                                    return chain.proceed();
                                }
                                if (Boolean.TRUE.equals(replayGuard.get())) {
                                    return chain.proceed();
                                }
                            } catch (Throwable t) {
                                // 拦截入口的判定抛异常只能放行 —— 但必须留痕，否则
                                // 「为什么没接管」在日志里是空白的（PROTECTIVE 模式静默吞掉）
                                log("takeover: gate error " + t + ", let wechat send");
                                return chain.proceed();
                            }
                            Object params = chain.getArg(pi);
                            Object callback = chain.getArg(ci);
                            try {
                                // 会话白名单：不在名单里就完全不接管，图照发、只是不带文件名
                                String talker = talkerOf(params);
                                lastTalker = talker;
                                if (!scopeAllowed(talker)) {
                                    log("takeover: talker=" + talker + " not in scope, let wechat send");
                                    return chain.proceed();
                                }
                                if (System.currentTimeMillis() < takeoverUntil) {
                                    // 上一批还在逐张重放：这一批要是也接管，两批会抢同一个
                                    // 「发送成功」计数和同一份列表字段，谁都对不齐。
                                    // 交回微信原样发，文件名排进兜底队列，等上一批收工后补。
                                    log("takeover: previous batch still running, defer text");
                                    armDeferred(params);
                                    return chain.proceed();
                                }
                                ReplayCtx ctx = new ReplayCtx(m, chain.getThisObject(),
                                        chain.getArgs().toArray());
                                if (!ensureReplayReady()) {
                                    log("takeover: replay not ready, let wechat send");
                                    return chain.proceed();
                                }
                                log("takeover: intercepted, params=" + className(params)
                                        + " talker.c=" + talker
                                        + " talker.ia5a=" + talkerOfArg(chain.getArgs()));
                                if (!replayActive.compareAndSet(false, true)) {
                                    // 两个入口线程在同一毫秒撞上：只有一个能真接管
                                    log("takeover: batch claim lost, defer text");
                                    armDeferred(params);
                                    return chain.proceed();
                                }
                                if (callback instanceof Runnable) {
                                    main.post((Runnable) callback);   // 相册收工，别卡「正在发送中」
                                }
                                startReplay(params, ctx);
                                return null;
                            } catch (Throwable t) {
                                // 接管没起来就把独占位还回去，否则后续每一批都会一直排队
                                replayActive.set(false);
                                log("takeover: intercept failed, fallback to wechat: " + t);
                                return chain.proceed();
                            }
                        });
                // 挂成功才记账：改前是先记账再挂，一次 hook 失败就把该 loader 永久放弃，
                // 后面的 installAll 轮次再也不会补挂（表现为「接管能力时有时没有」）
                markHooked(hookKey);
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
    private void startReplay(Object params, ReplayCtx ctx) {
        takeoverUntil = System.currentTimeMillis() + 180_000L;

        // 时序参数每批只读一次：longProp 每次都要 Class.forName + getMethod，
        // 放在逐张循环里等于每张图白做几次反射
        final long gap = longProp("gap", 0L);
        final long intoGap = longProp("intogap", 800L);
        final long confirmGap = longProp("confirmgap", 0L);
        final long confirmTimeout = longProp("confirmtimeout", 12_000L);
        final long drainMax = longProp("drainmax", 6_000L);
        UiSender.preClickMs = (int) longProp("preclick", 120L);
        UiSender.postClickMs = (int) longProp("postclick", 150L);

        // ⚠️ 上一批的「连续等不到确认」绝不能带到这一批。
        // sentMisses >= 2 会让整批都不再等事件，图片一股脑重放出去，上传全压在微信的发送队列里
        // —— 这正是「发完图再发文字还在转圈」的来源，而且它只在一处被清零，
        // 一旦触发，本进程后续每一批都会退化，永远不自愈。
        sentMisses = 0;

        // 本批的批次 id：记录页按它把「一口气发的那一堆」折成一组，
        // 组头就是这一批开始的时间戳 —— 用户点它就展开那一堆图。
        curBatch = newBatchId();

        // 本批的文字缓存：同一个条目只计一次次数
        batchTexts = new IdentityHashMap<>();
        // 兜底队列若已为这一批算过文字（db0.u#Vj 先到），直接复用；
        // 同时把它清掉，免得接管发完以后轮询又把同一批文字补一遍。
        final ArrayList<Draft> preDrafts = pendingDrafts;
        pendingDrafts = null;

        final boolean hudToast = cfgBool(ConfigKeys.IMG_LABEL_PROGRESS_TOAST,
                ConfigKeys.DEFAULT_IMG_LABEL_PROGRESS_TOAST);
        final boolean hudKeep = cfgBool(ConfigKeys.IMG_LABEL_KEEP_SCREEN_ON,
                ConfigKeys.DEFAULT_IMG_LABEL_KEEP_SCREEN_ON);
        main.post(() -> {
            if (mainProcess) {
                UiHud.begin(appContext(), hudToast, hudKeep);
            }
        });

        Thread worker = new Thread(() -> {
            // 本批的重放上下文只认这一条线程：别的批次再进来也覆盖不到它
            replayCtx.set(ctx);
            List<?> origin = null;
            Field listField = findListField(params);
            // 已经整条走完的条目数。必须声明在 try 之外：catch 里要用它决定「从第几个交回微信」，
            // 而 try 块内声明的局部量在 catch 里不可见（Java 作用域）。
            int completed = 0;
            try {
                if (listField == null) {
                    log("takeover: no list field on " + className(params) + ", let wechat send");
                    replayOriginal();
                    releaseBatchRefs();
                    return;
                }
                List<?> src = (List<?>) listField.get(params);
                if (src == null || src.isEmpty()) {
                    log("takeover: empty selection, let wechat send");
                    replayOriginal();
                    releaseBatchRefs();
                    return;
                }
                origin = new ArrayList<>(src);
                log("takeover: entries=" + origin.size());

                int sent = 0;
                List<Object> failed = new ArrayList<>();
                final int total = origin.size();
                for (int k = 0; k < total; k++) {
                    Object entry = origin.get(k);
                    final int idx = k + 1;
                    try {
                        ArrayList<Object> one = new ArrayList<>();
                        one.add(entry);
                        listField.set(params, one);
                        // 计数必须在重放**之前**取：成功事件可能在上一次调用的同步段里就落地，
                        // 基线取晚了一律白等到 confirmtimeout（历史上就是这么拖出两个 12 秒的）
                        int base = imageSent.get();
                        replayOne();
                        sent++;
                        if (mainProcess) {
                            main.post(() -> {
                                UiHud.hold(appContext());
                                UiHud.progress(appContext(), idx, total);
                            });
                        }
                        waitImageSent(base, idx, total, intoGap, confirmGap, confirmTimeout);
                        Draft draft = preDrafts != null && preDrafts.size() == total
                                ? preDrafts.get(k)
                                : draftFor(entry);
                        if (draft.label.isEmpty()) {
                            // 空串 = 文字过滤把这一张筛掉了：图照发，文字不发，也不进记录
                            log("filter: text#" + idx + " skipped by rules");
                        } else if (!sendLabel(draft.text(), idx, total)) {
                            log("takeover: text#" + idx + " not sent");
                        } else {
                            // 确认进了发送队列才记台账：没发出去的不算「第几次」
                            noteSent(draft);
                        }
                        sleepQuietly(gap);
                        completed = k + 1;
                    } catch (Throwable t) {
                        log("takeover: image#" + idx + " failed: " + t);
                        failed.add(entry);
                        completed = k + 1;
                    }
                }
                listField.set(params, origin);
                // 这里**不能**再 pendingDrafts = null：接管期间进来的下一批会被 armDeferred
                // 排进兜底队列（那是**它**的 drafts），批末一巴掌清掉就是把用户的文字吞了。
                // 本批自己的文字在 startReplay 开头（preDrafts）已经取走并清空。
                if (sentMisses > 0) {
                    // 有没等到确认的：等微信把在途上传排空再收工，
                    // 否则用户紧接着发的文字会排在图片上传后面转圈
                    drainInFlight(drainMax);
                }
                if (mainProcess) {
                    main.post(UiHud::end);
                    // 这一批发完就该让记录页看见；推失败由 retryRecordsPush 退避重试
                    pushRecords("batch");
                }
                // 批末收口：后面的兜底发送要归到自己那一组，不能续在这批里
                curBatch = null;
                takeoverUntil = 0L;
                log("takeover: done, sent=" + sent + " failed=" + failed.size()
                        + " misses=" + sentMisses);
                if (!failed.isEmpty()) {
                    // 只把没发出去的交回微信，已发过的绝不重发
                    listField.set(params, failed);
                    replayOriginal();
                }
                // 这一批彻底收工：把抓着的微信对象放开（见 releaseBatchRefs）
                releaseBatchRefs();
            } catch (Throwable t) {
                log("takeover: replay aborted: " + t);
                restoreAndFallback(params, listField, origin, completed);
                releaseBatchRefs();
            }
        }, "wxpiclabel-takeover");
        // daemon：接管线程不参与「微信进程能不能退出」这件事
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 一批走完就把微信那边的对象引用放开。
     *
     * <p>{@code entryArgs} 抓着整批选图参数（qa0.k、会话对象、缩略图 Uri 都在里面），
     * {@code batchTexts} 的键是每一个选图条目对象本身。改前这两份引用都要挂到
     * **下一批**才被覆盖 —— 用户发完图接着聊半小时，那批图和整份参数就一直躺在
     * 微信进程堆里。批末主动断引用，堆上只留 Draft 这种纯字符串的小对象。
     */
    private void releaseBatchRefs() {
        replayCtx.remove();
        batchTexts = null;
        replayActive.set(false);
        // 收工就把「批还在跑」的窗口关掉。早退分支（没找到列表字段、选图为空）不经过批末
        // 那段代码，改前 takeoverUntil 会一直挂到 180s 之后：期间新发的一批全被 defer 进兜底队列，
        // 而 flushFallback 又拿同一个 takeoverUntil 当闸门 —— 表现就是「文字过了三分钟才冒出来」。
        takeoverUntil = 0L;
    }

    /** 调一次微信的发送入口（列表里当前只有一张）。 */
    private void replayOne() throws Throwable {
        ReplayCtx c = replayCtx.get();
        if (c == null || !c.ready()) {
            throw new IllegalStateException("entry not captured");
        }
        replayGuard.set(Boolean.TRUE);
        try {
            c.method.setAccessible(true);
            Object ret = Modifier.isStatic(c.method.getModifiers())
                    ? c.method.invoke(null, c.args)
                    : c.method.invoke(c.self, c.args);
            log("takeover: entry replay -> " + ret);
        } finally {
            replayGuard.remove();
        }
    }

    /** 兜底：把微信原来那次调用补回去，保证图照样能发。 */
    private void replayOriginal() {
        ReplayCtx c = replayCtx.get();
        if (c == null || !c.ready()) {
            log("takeover: nothing to replay");
            return;
        }
        replayGuard.set(Boolean.TRUE);
        try {
            c.method.setAccessible(true);
            Object r = Modifier.isStatic(c.method.getModifiers())
                    ? c.method.invoke(null, c.args)
                    : c.method.invoke(c.self, c.args);
            log("takeover: wechat original send replayed -> " + r);
        } catch (Throwable t) {
            log("takeover: replay original failed: " + t);
        } finally {
            replayGuard.remove();
        }
    }

    /**
     * 异常路径：把**还没发出去的那几项**交回微信，并把它们的文件名交给兜底队列。
     *
     * @param from 已经整条走完的条目数（已重放 + 已处理文字）。改前这里把整份 {@code origin}
     *             塞回列表再 {@code replayOriginal()}，于是中途异常（比如确认等待被中断）会
     *             把前面已经发出去的图**再发一遍**，同时 {@code draftFor} 又把它们的计数
     *             重新 bump 一次 —— 用户看到的是重复的图 + 凭空多出来的「+N」。
     */
    private void restoreAndFallback(Object params, Field listField, List<?> origin, int from) {
        if (origin == null) {
            takeoverUntil = 0L;
            if (mainProcess) {
                main.post(UiHud::end);
            }
            replayOriginal();
            return;
        }
        int size = origin.size();
        int start = Math.max(0, Math.min(from, size));
        List<Object> rest = start == 0 ? new ArrayList<>(origin) : new ArrayList<>(origin.subList(start, size));
        try {
            if (listField != null) {
                listField.set(params, rest);
            }
        } catch (Throwable ignored) {
            // 恢复失败也只能继续兜底：此时微信那边拿的是上一次 set 进去的单条列表
        }
        takeoverUntil = 0L;
        if (mainProcess) {
            main.post(UiHud::end);
        }
        if (start >= size) {
            log("takeover: aborted after last entry, nothing to replay");
            replayOriginal();
            return;
        }
        log("takeover: restore entries " + start + ".." + size + " to wechat, text goes to fallback");
        ArrayList<Draft> drafts = new ArrayList<>();
        for (Object o : rest) {
            drafts.add(draftFor(o));   // 命中本批缓存，不会重复计数
        }
        armFallback(drafts, 0);
        replayOriginal();
    }

    /**
     * 等这一张图真正发出去（气泡上的转圈消失）。
     *
     * @param base 重放**之前**取的计数快照。事件有可能在 `on5.f#a` 的同步段里就落地，
     *             基线取晚了一律白等到超时 —— 这就是历史上「两张图各卡 12 秒」的根因。
     */
    private void waitImageSent(int base, int idx, int total,
                               long intoGap, long confirmGap, long confirmTimeout) {
        if (sentMisses >= 2) {
            // 退化模式：不再等事件，但也**不能零间隔** —— 零间隔会让后续每张图的上传
            // 同时压在微信发送队列里，表现就是「图越多越慢、发完手动发文字还在转圈」
            sleepQuietly(intoGap);
            return;
        }
        long start = System.currentTimeMillis();
        long deadline = start + confirmTimeout;
        boolean confirmed = false;
        synchronized (sentSignal) {
            while (true) {
                if (imageSent.get() > base) {
                    confirmed = true;
                    break;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0L) {
                    break;
                }
                try {
                    // 有上限的等待：即使某次 notify 落在判定与 wait 之间（理论上不会，
                    // 两边都持同一把锁）也最多 200ms 就重新看一次计数，不会白等到 12 秒
                    sentSignal.wait(Math.min(remain, 200L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (confirmed) {
            sentMisses = 0;
            log("takeover: image#" + idx + "/" + total + " sent-confirmed after "
                    + (System.currentTimeMillis() - start) + "ms");
            sleepQuietly(confirmGap);
            return;
        }
        sentMisses++;
        log("takeover: image#" + idx + "/" + total + " no send-confirm in "
                + (System.currentTimeMillis() - start) + "ms, misses=" + sentMisses);
        sleepQuietly(intoGap);
    }

    /**
     * 收尾排空：等到微信不再冒出新的发送成功事件为止。
     *
     * <p>只在「本批有没等到确认的图」时调用 —— 那些图还在上传，用户紧接着发文字
     * 就会排在它们后面转圈。等到连续 800 ms 没有新事件（或到 max）就认为排空了。
     */
    private void drainInFlight(long max) {
        long start = System.currentTimeMillis();
        int last = imageSent.get();
        long lastChange = start;
        while (System.currentTimeMillis() - start < max) {
            int now = imageSent.get();
            if (now != last) {
                last = now;
                lastChange = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastChange > 800L) {
                log("takeover: drain idle after " + (System.currentTimeMillis() - start) + "ms");
                return;
            }
            // 同上：静默期改成等唤醒（最多 80ms 再看一眼），不再 80ms 醒一次空转
            synchronized (sentSignal) {
                if (imageSent.get() == last) {
                    long remain = Math.min(80L, max - (System.currentTimeMillis() - start));
                    if (remain > 0L) {
                        try {
                            sentSignal.wait(remain);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        }
        log("takeover: drain timeout after " + (System.currentTimeMillis() - start) + "ms");
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
        return queued;
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
            final String hookKey = "Vj@" + System.identityHashCode(cl);
            if (isHooked(hookKey)) {
                return;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!Boolean.TRUE.equals(replayGuard.get())) {
                                // 这一路跑在 Binder 线程的 PROTECTIVE 回调里：异常抛出去宿主只看到
                                // 默认返回值（这一批静默不带文件名），日志里一个字都没有。自己兜住。
                                try {
                                    armFromParams(chain.getArg(0));
                                } catch (Throwable t) {
                                    log("fallback: armFromParams failed: " + t);
                                }
                            }
                            return chain.proceed();
                        });
                // 挂成功才记账：先记账再挂，一次失败就再也不会重试
                markHooked(hookKey);
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
            final String hookKey = "Fj" + pt.length + "@" + System.identityHashCode(cl);
            if (isHooked(hookKey)) {
                return;
            }
            try {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object ret = chain.proceed();
                            try {
                                if (isImage(chain.getArg(fi))) {
                                    imageSent.incrementAndGet();
                                    // 计数先加、再唤醒：等待方基于「新值」判断，不会被假唤醒骗走
                                    synchronized (sentSignal) {
                                        sentSignal.notifyAll();
                                    }
                                }
                            } catch (Throwable ignored) {
                                // 只做计数
                            }
                            return ret;
                        });
                markHooked(hookKey);
                log("hooked send-succ: xm5.y#Fj/" + pt.length + " f9Idx=" + fi);
            } catch (Throwable t) {
                log("hook send-succ failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ 钩子：相册「记忆」开关

    /**
     * 相册页（{@code AlbumPreviewUI}）一构造就排注入任务，把「记忆」勾选框加到「原图」右边。
     *
     * <p>挂**构造器**而不是生命周期方法：AlbumPreviewUI 自己没声明
     * onCreate/onResume（都在混淆父类链上），挂基类 {@code Activity#onResume}
     * 实测零命中；构造器不会被覆盖，且每次打开相册都是新实例，天然覆盖每次进入。
     *
     * <p>显隐跟随两道配置：总开关（feature_img_label）关 → 不注入；
     * 「仅选中的会话生效」开着且当前会话不在名单 → 不注入。
     */
    private void hookAlbumToggle(ClassLoader cl) {
        if (isHooked("albumToggle@" + System.identityHashCode(cl))) {
            return;
        }
        try {
            final Class<?> album = Reflect.findClass(
                    "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI", cl);
            if (album == null) {
                // 不打标记：类可能晚一轮才被 loader 加载出来，先标记等于「一次没找到，
                // 以后再也不补挂」，相册里的开关就永久消失了
                log("memory: AlbumPreviewUI not found, album toggle disabled");
                return;
            }
            markHooked("albumToggle@" + System.identityHashCode(cl));
            for (java.lang.reflect.Constructor<?> c : album.getDeclaredConstructors()) {
                try {
                    c.setAccessible(true);
                    hook(c).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object ret = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self instanceof Activity) {
                                        scheduleAlbumInject((Activity) self);
                                    }
                                } catch (Throwable t) {
                                    log("memory: album hook failed: " + t);
                                }
                                return ret;
                            });
                    log("hooked album toggle: AlbumPreviewUI#<init>");
                } catch (Throwable t) {
                    log("hook album ctor failed: " + t);
                }
            }
        } catch (Throwable t) {
            log("hook album toggle failed: " + t);
        }
    }

    /**
     * 构造器里视图还没 setContentView，定时重试注入：
     * 300ms 后第一试，「原图」还没出现就每 400ms 再试，最多 6 次。
     */
    private void scheduleAlbumInject(final Activity act) {
        final int[] tries = {0};
        final Runnable[] task = new Runnable[1];
        task[0] = () -> {
            try {
                boolean show = featureEnabled() && albumScopeAllows(act);
                String res = AlbumMemoryToggle.inject(act, show, this::log);
                if (res != null && res.contains("not found") && ++tries[0] < 6) {
                    main.postDelayed(task[0], 400L);
                    return;
                }
                if (res != null) {
                    log(res);
                }
            } catch (Throwable t) {
                log("memory: inject task failed: " + t);
            }
        };
        main.postDelayed(task[0], 300L);
    }

    /** 相册页对应的会话是否在发送范围里；talker 取不到时按放行处理（与 ScopeGate 口径一致）。 */
    private boolean albumScopeAllows(Activity act) {
        return scopeAllowed(AlbumMemoryToggle.talkerOfAlbum(act));
    }

    /** 「记忆」勾选框是否勾着；上下文拿不到时按勾选处理（宁可多生效，不可整体失效）。 */
    private boolean memoryOn() {
        Context ctx = appContext();
        return ctx == null || AlbumMemoryToggle.isOn(ctx);
    }

    // ------------------------------------------------------------------ 兜底队列

    /** 用 Vj 的实参武装兜底队列（只在接管不生效时才会走到）。 */
    /**
     * 上一批还在逐张重放时进来的新一批：不接管，只把文件名算好塞进兜底队列。
     *
     * <p>为什么不能直接复用 {@link #armFromParams}：那道闸在接管进行中会直接返回，
     * 这一批文字就整批丢了。这里只保留「总开关 / 记忆勾选」两道，文字算完排队，
     * 等上一批收工、{@code takeoverUntil} 归零后由兜底轮询按间隔补发。
     */
    private void armDeferred(Object params) {
        if (params == null || !featureEnabled() || !memoryOn()) {
            return;
        }
        // 用自己的一张表：接管线程那张正在被用，不能共写
        IdentityHashMap<Object, Draft> local = new IdentityHashMap<>();
        try {
            Field f = findListField(params);
            if (f == null) {
                return;
            }
            List<?> list = (List<?>) f.get(params);
            if (list == null || list.isEmpty()) {
                return;
            }
            ArrayList<Draft> drafts = new ArrayList<>();
            for (Object o : list) {
                drafts.add(draftFor(o, local));
            }
            // 队列里已经排着一批（更早的 defer，或刚被 armFromParams 装上的）：
            // 追加到队尾，别把后一批的文字吞掉。此时**不能**重置 pendingIndex ——
            // 前面那批可能已经补发到第 N 格，从 0 重来等于把发过的再发一遍。
            ArrayList<Draft> queued = pendingDrafts;
            if (queued != null) {
                ArrayList<Draft> merged = new ArrayList<>(queued);
                merged.addAll(drafts);
                pendingDrafts = merged;
                log("fallback: deferred append, +" + drafts.size() + " total=" + merged.size()
                        + " at=" + pendingIndex);
                return;
            }
            curBatch = newBatchId();
            armFallback(drafts, 0);
            log("fallback: deferred at takeover, drafts=" + drafts.size());
        } catch (Throwable t) {
            log("fallback: defer failed: " + t);
        }
    }

    private void armFromParams(Object params) {
        if (params != null) {
            lastTalker = talkerOf(params);
        }
        if (params == null || pendingDrafts != null || !featureEnabled()) {
            return;
        }
        // 接管正在跑：这一批的文字由接管逐张发，兜底队列不许插一脚。
        // 插进来还会把 batchTexts 清掉，draftFor 于是重新 bump 一次 —— 文件名会凭空 +2/+3。
        if (System.currentTimeMillis() < takeoverUntil) {
            return;
        }
        // 「记忆/文件名」没勾：连文字都别算 —— RepeatCounter 在 draftFor 里会 bump 次数，
        // 算了却不发，下一次发送的 +N 就凭空跳号
        if (!memoryOn()) {
            return;
        }
        // 白名单外的会话根本不会补文件名，先把名单算一遍只会白白 bump 计数
        // （实测向白名单外的会话发 6 张，+N 计数照样被顶了一次，随后兜底队列又整体丢弃）。
        if (!scopeAllowed(lastTalker)) {
            return;
        }
        // 走到这里说明这一批没有接管（接管会先清掉 pendingDrafts），缓存属于上一批。
        // 用**局部表**算完再一次性换上：改前是先换全局表、再逐条往里填，而这条路径跑在
        // Binder 线程上，接管线程此时若正好问到同一个条目就会「表里没有 → 重新 bump」，
        // 次数凭空 +1。表整个换引用是原子的，换早了没用，换晚了也不影响正确性。
        IdentityHashMap<Object, Draft> local = new IdentityHashMap<>();
        Field f = findListField(params);
        if (f == null) {
            return;
        }
        try {
            List<?> list = (List<?>) f.get(params);
            if (list == null || list.isEmpty()) {
                return;
            }
            ArrayList<Draft> drafts = new ArrayList<>();
            for (Object o : list) {
                drafts.add(draftFor(o, local));
            }
            batchTexts = local;
            // 兜底发送整批共用同一个批次 id，记录页把它们折成一组
            curBatch = newBatchId();
            armFallback(drafts, 0);
            log("fallback: armed at Vj, drafts=" + drafts);
        } catch (Throwable t) {
            log("fallback: arm failed: " + t);
        }
    }

    private void armFallback(ArrayList<Draft> drafts, int from) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        pendingIndex = Math.max(0, from);
        pendingDrafts = drafts;
        pendingAt = System.currentTimeMillis();
    }

    /** 轮询：接管没在跑、聊天页可用、而且还有没发出去的文件名，就按间隔补发。 */
    private void startFallbackPoller() {
        // onPackageLoaded 可能随 ClassLoader 重装被回调多次；轮询线程与首延迟只建一次。
        if (!mainProcess || !pollerStarted.compareAndSet(false, true)) {
            return;
        }
        // 常驻轮询挪到独立 HandlerThread：配置检查、重试、cfgPrefs 重附着、
        // 记录回传的分片 commit 都不再抢主线程，避免发完图上滑 / 使用微信时掉帧。
        HandlerThread thread = new HandlerThread("wxpiclabel-poller");
        thread.start();
        pollThread = thread;
        final Handler worker = new Handler(thread.getLooper());
        pollWorker = worker;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                try {
                    // flushFallback 要读 DecorView（UiSender.hasInput / send），必须回主线程；
                    // 有兜底队列时才 post，CAS 保证同一时刻最多挂一个 flush（不重发），
                    // 有界 latch 等它跑完，保持「先 flush 再 6 check」的顺序。
                    if (pendingDrafts != null && flushPending.compareAndSet(false, true)) {
                        final CountDownLatch flushDone = new CountDownLatch(1);
                        final AtomicReference<Throwable> flushError = new AtomicReference<>();
                        main.post(() -> {
                            try {
                                flushFallback();
                            } catch (Throwable t) {
                                flushError.set(t);   // 搬回 worker，别让异常冒到微信主线程
                            } finally {
                                flushDone.countDown();
                                flushPending.set(false);
                            }
                        });
                        try {
                            flushDone.await(1500L, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        Throwable err = flushError.get();
                        if (err != null) {
                            // 与改前一致：flush 抛错就跳过本轮 6 个 check、记一条日志后自续。
                            log("fallback: flush failed: " + err);
                        } else {
                            runChecks();
                        }
                    } else {
                        runChecks();
                    }
                } catch (Throwable t) {
                    log("fallback: flush failed: " + t);
                }
                worker.postDelayed(this, 700L);
            }
        };
        worker.postDelayed(poll, 1200L);
    }

    /** 配置/记录检查；始终在 worker 上跑。 */
    private void runChecks() {
        checkScopeRefresh();
        checkRecordReset();
        checkRecordEdits();
        checkRecordDump();
        retryRecordsPush();
        checkFriendRetry();
        checkSelfTest();
    }

    // ------------------------------------------------------------------ 自测通道

    /** 上一次触发的自测属性值（同一个值只跑一次，换值才再跑）。 */
    private volatile String lastSelfTestText;
    private volatile String lastSelfTestHud;

    /**
     * 自测通道：`debug.wxpiclabel.text <文本>` / `debug.wxpiclabel.hud <总数>`。
     *
     * <p>`docs/TROUBLESHOOTING.md` 第 7 节一直写着这两个属性，但代码精简那轮把触发点一起删了，
     * 文档与实现脱节；这里补回来，顺带给本轮的发送时序优化留一个可复现入口。
     */
    private void checkSelfTest() {
        String text = prop("text");
        // 调试属性是外部输入：真被写成长字符串塞进微信输入框，会连带把整条发送链路拖慢
        if (text != null && text.length() > 200) {
            log("selftest: text too long (" + text.length() + "), ignored");
            text = null;
        }
        if (text != null && !text.isEmpty() && !text.equals(lastSelfTestText)) {
            lastSelfTestText = text;
            log("selftest: send text=" + text);
            UiSender.send(main, text);
        }
        String hud = prop("hud");
        if (hud != null && !hud.isEmpty() && !hud.equals(lastSelfTestHud)) {
            lastSelfTestHud = hud;
            int total;
            try {
                total = Integer.parseInt(hud.trim());
            } catch (Throwable t) {
                total = 3;
            }
            runHudSelfTest(Math.max(1, Math.min(total, 99)));
        }
    }

    /** 自测：跑一遍进度 Toast + 屏幕常亮，不看配置开关（要验的就是这条链路本身）。 */
    private void runHudSelfTest(int total) {
        log("selftest: hud total=" + total);
        main.post(() -> UiHud.begin(appContext(), true, true));
        for (int i = 1; i <= total; i++) {
            final int n = i;
            main.postDelayed(() -> UiHud.progress(appContext(), n, total), i * 400L);
        }
        main.postDelayed(UiHud::end, (total + 1) * 400L + 1000L);
    }

    private void flushFallback() {
        ArrayList<Draft> drafts = pendingDrafts;
        if (drafts == null) {
            return;
        }
        if (!featureEnabled()) {
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
            }
            return;
        }
        // 「记忆」没勾：这批不带文件名，兜底队列直接清掉
        if (!memoryOn()) {
            log("fallback: memory off, drop " + drafts.size());
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
            }
            return;
        }
        // 兜底队列也要过白名单这道闸，否则「接管没跑起来」时白名单会被绕过
        if (!scopeAllowed(lastTalker)) {
            log("fallback: talker=" + lastTalker + " not in scope, drop " + drafts.size());
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
            }
            return;
        }
        if (System.currentTimeMillis() < takeoverUntil) {
            return;   // 接管正在逐张发，别抢
        }
        if (System.currentTimeMillis() - pendingAt > 120_000L) {
            log("fallback: expire, dropped " + (drafts.size() - pendingIndex));
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
            }
            return;
        }
        if (!UiSender.hasInput()) {
            return;   // 聊天页还没回来
        }
        // 兜底队列是「整表替换」的：这一 tick 里别处可能刚 arm 了下一批
        // （armFallback 换 pendingDrafts 并清零 pendingIndex）。先确认手里这份还是当前批，
        // 再动序号 —— 否则会把新批的第 0 格当成旧批的第 i 格跳过，最坏整批算成「发完了」丢掉。
        if (pendingDrafts != drafts) {
            return;
        }
        int i = pendingIndex;
        if (i >= drafts.size()) {
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
            }
            return;
        }
        Draft draft = drafts.get(i);
        pendingIndex = i + 1;
        pendingAt = System.currentTimeMillis();
        boolean filtered = draft.label.isEmpty();
        boolean ok = !filtered && UiSender.send(main, draft.text(), null);
        if (filtered) {
            log("filter: fallback label#" + (i + 1) + " skipped by rules");
        } else {
            log("fallback: label#" + (i + 1) + "/" + drafts.size() + " " + draft.text() + " uiSent=" + ok);
        }
        if (ok) {
            noteSent(draft);
        }
        if (pendingIndex >= drafts.size()) {
            if (pendingDrafts == drafts) {
                pendingDrafts = null;
                curBatch = null;
            }
            pushRecords("fallback");
        }
    }

    // ------------------------------------------------------------------ 配置与计数

    /**
     * remote preferences（目标进程侧只读）；框架不支持时返回 null，调用方一律走默认值。
     *
     * <p>**TTL 到期不再原地等 binder**：调用方常常就是微信主线程里的发送拦截器，
     * 在那条线上跨进程取一次快照就是一次掉帧。手里已经有旧快照时先把它交出去，
     * 顺手把重附着排到轮询线程（{@link #pollWorker}，700ms 一跳），配置最多晚一个
     * TTL 生效 —— 与改前 3s 的刷新粒度本来就是同一个量级。
     *
     * <p>手里没有快照（进程刚起来那次）才同步取：这一次没法推迟，推迟就等于整批功能读不到配置。
     */
    private SharedPreferences cfgPrefs() {
        SharedPreferences p = cfgPrefs;
        if (p != null && System.currentTimeMillis() - cfgAttachedAt < CFG_TTL_MS) {
            return p;
        }
        if (p != null) {
            Handler w = pollWorker;
            if (w != null) {
                if (cfgAttaching.compareAndSet(false, true)) {
                    try {
                        w.post(this::reattachCfg);
                    } catch (Throwable t) {
                        cfgAttaching.set(false);
                    }
                }
                return p;
            }
        }
        return attachCfg(p);
    }

    /** 真正去取一次快照；取不到就沿用旧值（绝不能因为一次异常把功能判成「关」）。 */
    private SharedPreferences attachCfg(SharedPreferences stale) {
        try {
            SharedPreferences fresh = getRemotePreferences(ConfigKeys.GROUP);
            if (fresh != null) {
                if (stale == null) {
                    log("config: remote prefs attached");
                }
                cfgPrefs = fresh;
                cfgAttachedAt = System.currentTimeMillis();
                return fresh;
            }
        } catch (Throwable t) {
            if (stale == null) {
                log("config: remote prefs unavailable: " + t);
            }
        }
        if (stale != null) {
            // 失败也把时间戳推后：否则每个调用点都会再排一次重取，等于把 binder 掐成死循环
            cfgAttachedAt = System.currentTimeMillis();
        }
        return stale;
    }

    /** 轮询线程上的重附着（失败也不外抛：下一次 TTL 到期再排）。 */
    private void reattachCfg() {
        try {
            attachCfg(cfgPrefs);
        } finally {
            cfgAttaching.set(false);
        }
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

    /** 「增加 文件名 开关」总开关：关掉就完全不接管，退回微信原生批量发送。 */
    private boolean featureEnabled() {
        return cfgBool(ConfigKeys.IMG_LABEL, ConfigKeys.DEFAULT_IMG_LABEL);
    }

    /** 会话白名单判定（规则见 ScopeGate）。 */
    private boolean scopeAllowed(String talker) {
        // 只取一次句柄：改前 cfgBool 与 cfgStringSet 各过一次 TTL，两个键可能落在
        // 前后两份快照上——开关刚打开、名单还是旧的那份，读过的人只会以为「白名单没生效」。
        SharedPreferences p = cfgPrefs();
        boolean only;
        try {
            only = p == null
                    ? ConfigKeys.DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED
                    : p.getBoolean(ConfigKeys.IMG_LABEL_SCOPE_ONLY_SELECTED,
                    ConfigKeys.DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED);
        } catch (Throwable t) {
            only = ConfigKeys.DEFAULT_IMG_LABEL_SCOPE_ONLY_SELECTED;
        }
        if (!only) {
            return true;
        }
        return ScopeGate.allowed(true, cfgStringSet(ConfigKeys.IMG_LABEL_SCOPE_IDS), talker);
    }

    /**
     * 读一个字符串集合。
     *
     * <p>{@code getStringSet} 的契约是「每次返回一份拷贝」，而这条路径在每张图前都要走一遍
     * （scopeAllowed）；一份白名单被拷几十次纯属白造对象。这里按「当前快照时间戳 + 键名」
     * 缓存一份只读副本：快照一换时间戳就变，缓存自然失效，配置改了不会读到旧名单。
     */
    private java.util.Set<String> cfgStringSet(String key) {
        SharedPreferences p = cfgPrefs();
        if (p == null) {
            return java.util.Collections.emptySet();
        }
        long stamp = cfgAttachedAt;
        java.util.Set<String> hit = cfgSetValue;
        if (hit != null && stamp == cfgSetStamp && key.equals(cfgSetKey)) {
            return hit;
        }
        try {
            java.util.Set<String> v = p.getStringSet(key, null);
            java.util.Set<String> out = v == null
                    ? java.util.Collections.emptySet()
                    : java.util.Collections.unmodifiableSet(new HashSet<>(v));
            cfgSetKey = key;
            cfgSetStamp = stamp;
            cfgSetValue = out;
            return out;
        } catch (Throwable t) {
            return java.util.Collections.emptySet();
        }
    }

    /** 重命名规则表；原始字符串没变就复用上一次的解析结果。 */
    private java.util.Map<String, String> renameRules() {
        String raw = cfgStr(ConfigKeys.IMG_LABEL_RENAME_RULES,
                ConfigKeys.DEFAULT_IMG_LABEL_RENAME_RULES);
        java.util.Map<String, String> cached = renameCache;
        if (cached != null && raw.equals(renameCacheRaw)) {
            return cached;
        }
        java.util.Map<String, String> parsed = RenameRules.parse(raw);
        renameCacheRaw = raw;
        renameCache = parsed;
        log("rename: rules=" + parsed.size());
        return parsed;
    }

    /**
     * 把当前记录推回模块 App（「发送记录」页面的数据源）。
     *
     * <p>触发点：一批发完（保持页面新鲜）、设置页点「刷新」、清空 / 改 / 删生效之后。
     * 一次推**整份文档**（聚合计数表 + 逐次台账 + 已应用令牌），两边永远同一时刻。
     */
    /**
     * 记录是否还没送到模块 App（推失败或从没推过）。
     *
     * <p>初值 true：微信进程一起来就先补推一次 —— 否则「上一次推送时模块 App 被系统冻住」
     * 这种一次性失败会让记录页永远是旧数据，用户只能靠再发一张图碰运气。
     */
    private volatile boolean recordsDirty = true;
    /** 下一次允许重试的时间戳（失败后按 5s 起步、最长 60s 退避）。 */
    private volatile long recordsRetryAt;
    private volatile int recordsFailures;

    /**
     * 推一份记录快照到模块 App。
     *
     * <p>**加锁**：批末推送、令牌推送、轮询里的重试可能同时进来，而回传是分片 insert，
     * 两路交错写会让 provider 把两个快照的片拼成一份烂 JSON。
     *
     * <p>统一 hop 到轮询后台线程执行：接管线程（batch）、主线程（fallback）、
     * 轮询线程（token/reset/edits）三处的调用都归到同一条线程，分片 insert 与
     * 同步 commit 不再占主线程，也不跨线程抢同一把锁。
     */
    private void pushRecords(String why) {
        Handler w = pollWorker;
        if (w != null && Looper.myLooper() != w.getLooper()) {
            w.post(() -> doPushRecords(why));
            return;
        }
        doPushRecords(why);
    }

    private synchronized void doPushRecords(String why) {
        if (!mainProcess) {
            return;
        }
        Context ctx = appContext();
        RepeatCounter r = repeatCounter();
        if (ctx == null || r == null) {
            recordsRetryAt = System.currentTimeMillis() + 3000L;
            return;
        }
        try {
            List<RepeatCounter.Row> rows = r.snapshot();
            List<RepeatCounter.Event> events = r.events();
            boolean ok = RecordExporter.push(ctx, rows, events, r.appliedEdits(), this::log);
            log("records: snapshot " + rows.size() + " row(s) / " + events.size()
                    + " event(s) reason=" + why + (ok ? " ok" : " retry-pending"));
            if (ok) {
                recordsDirty = false;
                recordsFailures = 0;
                recordsRetryAt = 0L;
            } else {
                scheduleRecordsRetry();
            }
        } catch (Throwable t) {
            log("records: snapshot failed: " + t);
            scheduleRecordsRetry();
        }
    }

    private void scheduleRecordsRetry() {
        recordsDirty = true;
        long backoff = Math.min(60_000L, 5_000L * (1L + recordsFailures));
        recordsFailures++;
        recordsRetryAt = System.currentTimeMillis() + backoff;
    }

    /** 轮询里的自愈：记录还没送达且退避时间已过，就再推一次。 */
    private void retryRecordsPush() {
        if (!mainProcess || !recordsDirty) {
            return;
        }
        if (System.currentTimeMillis() < recordsRetryAt) {
            return;
        }
        doPushRecords("retry");
    }

    /** 设置页点了「刷新记录」就把令牌换一个新值，这里比对后回传一次。 */
    private void checkRecordDump() {
        String mark = cfgStr(ConfigKeys.IMG_LABEL_RECORD_DUMP,
                ConfigKeys.DEFAULT_IMG_LABEL_RECORD_DUMP);
        if (mark.equals(lastRecordDump)) {
            return;
        }
        lastRecordDump = mark;
        if (mark.isEmpty()) {
            return;
        }
        pushRecords("token");
    }

    /**
     * 设置页点了「清空发送记录」：令牌变了就把台账与计数一起清掉，立刻回传。
     *
     * <p>发送链路自己也会比对令牌（{@code RepeatCounter.bump}），这里补的是
     * 「清完不发送、只想看见记录变空」的情况。
     */
    private void checkRecordReset() {
        String mark = cfgStr(ConfigKeys.IMG_LABEL_REPEAT_RESET,
                ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_RESET);
        if (mark.equals(lastResetToken)) {
            return;
        }
        lastResetToken = mark;
        RepeatCounter r = repeatCounter();
        if (r == null) {
            return;
        }
        if (r.applyReset(mark)) {
            log("records: cleared by token");
            pushRecords("reset");
        }
    }

    /**
     * 设置页改了 / 删了记录：令牌变了就把整组动作重新应用一遍，再回传。
     *
     * <p>动作是幂等的（见 {@link RepeatCounter#applyEdits}），所以不需要跟踪
     * 「哪条已应用」—— 每次令牌变化整体重放，结果一致。
     */
    private void checkRecordEdits() {
        String mark = cfgStr(ConfigKeys.IMG_LABEL_RECORD_EDITS_TOKEN,
                ConfigKeys.DEFAULT_IMG_LABEL_RECORD_EDITS_TOKEN);
        if (mark.equals(lastEditsToken)) {
            return;
        }
        lastEditsToken = mark;
        if (mark.isEmpty()) {
            return;
        }
        RepeatCounter r = repeatCounter();
        if (r == null) {
            return;
        }
        java.util.Set<String> ops = cfgStringSet(ConfigKeys.IMG_LABEL_RECORD_EDITS);
        boolean changed = r.applyEdits(ops);
        r.markEditsApplied(mark);
        log("records: edits applied, ops=" + ops.size() + " changed=" + changed);
        pushRecords("edits");
    }

    /**
     * 记一条发送事件进台账；失败只打日志，不影响发送。
     *
     * <p>序号直接取 {@link Draft} 里那个：它是这张图**当初算文字那一刻**定下的，
     * 不是记账此刻重新推出来的。兜底队列可能隔几十秒才把这一格发出去，
     * 用「现在」判定就会把一张 15:29 的图算进 15:30 起的时段里。
     */
    private void noteSent(Draft draft) {
        RepeatCounter c = repeatCounter();
        if (c == null || draft == null || draft.label.isEmpty()) {
            return;
        }
        long seq = c.record(draft.label, draft.n, curBatch, System.currentTimeMillis());
        // 台账变过 = 记录页该更新了：批末那次推失败还有轮询里的退避重试兜着
        recordsDirty = true;
        if (seq <= 0L) {
            log("records: note failed for " + draft.label);
        }
    }

    /** 批次 id：时间戳 + 进程内自增，同一毫秒起两批也不会撞。 */
    private String newBatchId() {
        return "b" + System.currentTimeMillis() + "-" + batchSeq.incrementAndGet();
    }

    /** 计数仓库懒加载；建不出来就返回 null（调用方按第 1 次处理）。 */
    private RepeatCounter repeatCounter() {
        RepeatCounter r = repeat;
        if (r != null) {
            return r;
        }
        // 两个线程同时第一次进来会各建一个实例：各自的计数缓存互不认账，第 N 次会来回跳。
        // 慢路径只走一次，双检后建，热路径仍然零锁。
        synchronized (this) {
            r = repeat;
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
    }

    /**
     * 当前「追加次数设置」：配置字符串没变就复用解析结果。
     *
     * <p>只缓存**解析**、不缓存判定 —— 「在不在时段里」必须拿这次发送的时刻实时算，
     * 否则跨过 15:30 之后还得等缓存过期才好使。
     */
    private RepeatWindow repeatWindow() {
        String raw = cfgStr(ConfigKeys.IMG_LABEL_REPEAT_WINDOW,
                ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_WINDOW);
        if (raw == null) {
            raw = "";
        }
        RepeatWindow cached = windowCache;
        if (cached != null && raw.equals(windowCacheRaw)) {
            return cached;
        }
        RepeatWindow parsed = RepeatWindow.parse(raw);
        windowCacheRaw = raw;
        windowCache = parsed;
        return parsed;
    }

    /**
     * 这个选图条目要发的文字，连同它在重复计数里的序号（见 {@link Draft}）。
     *
     * <p>认名字不认内容 —— 第二天重新截的同名图，内容变了也接着数。
     * 同一个条目在一批里可能被问两次（接管算一遍；失败后交回微信、{@code db0.u#Vj}
     * 再算一遍），用按**条目对象身份**的缓存挡住重复计数。
     *
     * <p>「追加次数设置」的闸门在 {@code bump} **之前**：不在时段里就压根不进计数表，
     * 序号记 0，文字原样发出去。只在显示层藏号是不够的 —— 那样三点半前发过一次，
     * 三点半后第一次就会被读数顶成第 2 次而 +2。
     */
    private Draft draftFor(Object entry) {
        return draftFor(entry, batchTexts);
    }

    /**
     * 指定缓存表的版本：接管还在跑时进来的那一批不能共写接管线程那张表
     * （{@link IdentityHashMap} 不是线程安全的，两批共写会串味），
     * 所以给它一张自己的表，算完即弃。
     */
    private Draft draftFor(Object entry, IdentityHashMap<Object, Draft> cache) {
        String original = labelOfEntry(entry);
        if (original == null || original.isEmpty() || "unknown".equals(original)) {
            return new Draft(original, 0);
        }
        // 重命名规则：命中就把文件名换成用户指定的文字。
        // 放在计数之前 —— 计数键跟着**实际发出去的那段文字**走，与「认名字不认内容」一致。
        String label = RenameRules.apply(renameRules(), original);
        if (cache != null) {
            Draft hit = cache.get(entry);
            if (hit != null) {
                return hit;
            }
        }
        // 文字过滤：必须在计数**之前**判 —— 先 bump 再丢弃，下次发同一张图会凭空 +N。
        // 空 label 表示「这一张只发图」；调用方据此跳过发文字，也不记台账。
        if (!textAllowed(label, original)) {
            Draft skipped = new Draft("", 0);
            if (cache != null) {
                cache.put(entry, skipped);
            }
            return skipped;
        }
        boolean repeatOn = cfgBool(ConfigKeys.IMG_LABEL_REPEAT_COUNT,
                ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_COUNT);
        int n = 0;
        if (repeatOn && repeatWindow().contains(System.currentTimeMillis())) {
            RepeatCounter c = repeatCounter();
            if (c != null) {
                n = c.bump(label, cfgStr(
                        ConfigKeys.IMG_LABEL_REPEAT_RESET, ConfigKeys.DEFAULT_IMG_LABEL_REPEAT_RESET));
            }
        }
        Draft draft = new Draft(label, n);
        if (cache != null) {
            cache.put(entry, draft);
        }
        log("repeat: label=" + label + " count=" + n + " text=" + draft.text());
        if (repeatOn && n == 0) {
            log("repeat: label=" + label + " missed the window, not counted");
        }
        return draft;
    }

    /**
     * 文字过滤放行判定：这张图该不该带文字发出去。
     *
     * <p>「关闭」直接放行；白名单配了空表也放行 —— 用户刚切到白名单还没写关键字，
     * 这时把整批文字全吞掉只会让人以为功能坏了（设置页的试算区会照实提示「一条都没写」）。
     */
    private boolean textAllowed(String label, String original) {
        int mode = TextFilter.parseMode(cfgStr(
                ConfigKeys.IMG_LABEL_FILTER_MODE, ConfigKeys.DEFAULT_IMG_LABEL_FILTER_MODE));
        if (mode == TextFilter.MODE_OFF) {
            return true;
        }
        java.util.List<TextFilter.Rule> rules = filterRules();
        if (rules.isEmpty()) {
            return true;
        }
        boolean matchOriginal = cfgBool(ConfigKeys.IMG_LABEL_FILTER_MATCH_ORIGINAL,
                ConfigKeys.DEFAULT_IMG_LABEL_FILTER_MATCH_ORIGINAL);
        TextFilter.Rule hit = matchOriginal
                ? TextFilter.find(rules, label, original)
                : TextFilter.find(rules, label);
        boolean allow = mode == TextFilter.MODE_WHITELIST ? hit != null : hit == null;
        if (!allow) {
            log("filter: drop mode=" + (mode == TextFilter.MODE_WHITELIST ? "white" : "black")
                    + " label=" + label + (hit != null ? " hit=" + hit.source : ""));
        }
        return allow;
    }

    /**
     * 文字过滤关键字表；原文与大小写开关都没变就复用上一次的解析结果。
     *
     * <p>缓存比对要带上 ignoreCase：开关一翻，同一张表的匹配结果就变了，只比原文会读旧表。
     */
    private java.util.List<TextFilter.Rule> filterRules() {
        String raw = cfgStr(ConfigKeys.IMG_LABEL_FILTER_RULES,
                ConfigKeys.DEFAULT_IMG_LABEL_FILTER_RULES);
        boolean ignoreCase = cfgBool(ConfigKeys.IMG_LABEL_FILTER_IGNORE_CASE,
                ConfigKeys.DEFAULT_IMG_LABEL_FILTER_IGNORE_CASE);
        java.util.List<TextFilter.Rule> cached = filterCache;
        if (cached != null && raw.equals(filterCacheRaw) && ignoreCase == filterCacheIgnoreCase) {
            return cached;
        }
        java.util.List<TextFilter.Rule> parsed = TextFilter.parseRules(raw, ignoreCase);
        filterCacheRaw = raw;
        filterCacheIgnoreCase = ignoreCase;
        filterCache = parsed;
        log("filter: rules=" + parsed.size() + " ignoreCase=" + ignoreCase);
        return parsed;
    }

    // ------------------------------------------------------------------ talker

    /**
     * 当前这批图要发给谁：{@code qa0.k#c}。
     *
     * <p>写入点是 {@code SendImgProxyUI$$a#run()}，它从相册 Intent 的
     * {@code GalleryUI_ToUser} 取值后写进这个字段；微信自己随后拿它调
     * {@code z3#U4}（= {@code endsWith("@chatroom") || endsWith("@im.chatroom")}）判群聊。
     */
    private static String talkerOf(Object params) {
        if (params == null) {
            return "null";
        }
        Object v = Reflect.getFieldByNames(params, "c");
        return v instanceof String ? (String) v : (v == null ? "null" : String.valueOf(v));
    }

    /** 兜底对照：{@code ia5.a#o()}（微信把它放进统计键 send_to_username）。 */
    private static String talkerOfArg(List<Object> args) {
        for (Object a : args) {
            if (a == null) {
                continue;
            }
            try {
                Class<?> cls = a.getClass();
                Object cached = talkerArgMethods.get(cls);
                if (cached == null) {
                    Method found = null;
                    try {
                        found = cls.getDeclaredMethod("o");
                        found.setAccessible(true);
                    } catch (Throwable ignored) {
                        // 不是那个类：把「没有 o()」也缓存下来，别每次再抛一遍
                    }
                    cached = found == null ? MISSING : found;
                    talkerArgMethods.put(cls, cached);
                }
                if (cached == MISSING) {
                    continue;
                }
                Object v = ((Method) cached).invoke(a);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
                // 调用失败：下一个
            }
        }
        return "-";
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

    /** 只查不记：用于「先确认条件都满足，再落标记」的钩子（见 hookTakeover / hookAlbumToggle）。 */
    private boolean isHooked(String key) {
        synchronized (hooked) {
            return hooked.contains(key);
        }
    }

    private static Field findListField(Object obj) {
        if (obj == null) {
            return null;
        }
        Class<?> cls = obj.getClass();
        Field cached = listFieldCache.get(cls);
        if (cached != null) {
            try {
                if (cached.get(obj) instanceof List) {
                    return cached;
                }
            } catch (Throwable ignored) {
                // 缓存的那个字段读不了：退回全扫描
            }
        }
        for (Field fd : cls.getDeclaredFields()) {
            try {
                fd.setAccessible(true);
                if (fd.get(obj) instanceof List) {
                    listFieldCache.put(cls, fd);
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
            Class<?> cls = msg.getClass();
            Method[] candidates = imageTypeMethods.get(cls);
            if (candidates == null) {
                Method[] found = new Method[0];
                for (Method m : cls.getDeclaredMethods()) {
                    if ("getType".equals(m.getName()) && m.getParameterCount() == 0) {
                        m.setAccessible(true);
                        found = new Method[]{m};
                        break;
                    }
                }
                candidates = imageTypeMethods.putIfAbsent(cls, found);
                if (candidates == null) {
                    candidates = found;
                }
            }
            if (candidates.length == 0) {
                return false;
            }
            Object v = candidates[0].invoke(msg);
            return v instanceof Integer && (Integer) v == 3;
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
        // 手切最后一段：这条路径每张图都走，{@code new File(path).getName()} 为了取个文件名
        // 要先造对象再走一遍路径归一化，而 Android 的 File 只认 '/' 一个分隔符，
        // 手切与它的行为等价（尾部斜杠同样按「没有文件名」处理）。
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        int slash = end > 0 ? path.lastIndexOf('/', end - 1) : -1;
        String name = slash >= 0 ? path.substring(slash + 1, end) : path.substring(0, end);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * content:// → 路径 的查询结果缓存。
     *
     * <p>同一张图在一批里会被问 2~3 次（接管算一遍、失败交回微信再算一遍、相册注入又一遍），
     * 而每次都是跨进程读一次媒体库。按 Uri 字符串缓存最近 64 条，够覆盖一批图的常见规模。
     * 只缓存查到的结果：查不到说明这条 URI 本来就无效，缓存它反而会盖住「文件被重新导入」。
     */
    private static final LruCache<String, String> RESOLVE_CACHE = new LruCache<>(64);
    /** 列名数组提到静态：每次 query 现造 String[] 也是白造。 */
    private static final String[] RESOLVE_PROJECTION = {"_data", "_display_name"};

    /** content:// 的 Uri 只有媒体库 ID，查库换真实路径。 */
    private static String resolveContentUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        String cacheKey = uri.toString();
        String cached = RESOLVE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Context ctx = appContext();
        if (ctx == null) {
            return null;
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, RESOLVE_PROJECTION, null, null, null);
            if (c != null && c.moveToFirst()) {
                int di = c.getColumnIndex("_data");
                if (di >= 0) {
                    String d = c.getString(di);
                    if (d != null && !d.isEmpty()) {
                        RESOLVE_CACHE.put(cacheKey, d);
                        return d;
                    }
                }
                int ni = c.getColumnIndex("_display_name");
                if (ni >= 0) {
                    String n = c.getString(ni);
                    if (n != null && !n.isEmpty()) {
                        String virtual = "/virtual/" + n;
                        RESOLVE_CACHE.put(cacheKey, virtual);
                        return virtual;
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
        // 拿到过就复用：这条路径在接管（逐张）、兜底、记录回传、相册注入里都是热调用，
        // 改前每次 Class.forName + getMethod + invoke 三连，纯属白烧 CPU。
        Context cached = appContextCache;
        if (cached != null) {
            return cached;
        }
        try {
            Method m = currentApplicationMethod;
            if (m == null) {
                m = Class.forName("android.app.ActivityThread").getMethod("currentApplication");
                currentApplicationMethod = m;
            }
            Object app = m.invoke(null);
            if (app instanceof Context) {
                Context ctx = (Context) app;
                appContextCache = ctx;
                return ctx;
            }
            return null;
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
            Method get = propMethod;
            if (get == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                get = sp.getMethod("get", String.class);
                propMethod = get;
            }
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

    /**
     * 一张图这次要发的文字，连同它在重复计数里的序号。
     *
     * <p>为什么要有这个东西：台账要分清「时段内第 2 次」和「时段外根本没进累计」，
     * 而兜底队列是**异步、跨批**发的。等到真正发出去那一刻再去查计数表，拿到的
     * 是后来的状态 —— 一张 15:29 算好的图，15:31 才轮到它发，就该记「未累计」，
     * 不是记「第 1 次」。所以序号必须在算文字的时候就跟着文字一起走。
     */
    private static final class Draft {

        /** 台账主键：重命名之后、还没加「 +N」的名字；空串表示被文字过滤筛掉。 */
        final String label;

        /** 0 = 没进累计（时段外发出 / 重复功能关着）；≥1 = 这次是第几次。 */
        final int n;

        Draft(String label, int n) {
            this.label = label == null ? "" : label;
            this.n = n;
        }

        /** 实际发出去的文字：第 2 次起才带「 +N」，第 1 次与未累计都是原样。 */
        String text() {
            return n >= 2 ? label + " +" + n : label;
        }

        @Override
        public String toString() {
            return text();
        }
    }
}
