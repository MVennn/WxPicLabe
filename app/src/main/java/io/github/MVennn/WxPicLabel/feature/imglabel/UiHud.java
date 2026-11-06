package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.app.Activity;
import android.content.Context;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * 发送期间的「现场提示」：进度 Toast + 屏幕常亮。
 *
 * <p>两件事都必须在主线程做，调用方（接管线程）统一 post 进来。
 *
 * <ul>
 *   <li><b>进度</b>：复用同一个系统 Toast 实例（`setText` 后 `show`），
 *       不复用会排出一长串 Toast 队列，关掉聊天都还在弹。</li>
 *   <li><b>常亮</b>：给当前前台 Activity 的窗口加 `FLAG_KEEP_SCREEN_ON`，
 *       不用 WakeLock —— 不需要宿主权限，Activity 销毁时系统自动回收。
 *       相册页 → 聊天页切换时换绑到新的前台 Activity，旧的先清掉。</li>
 * </ul>
 *
 * <p>两个能力各自可关（设置页「发送提示」两个开关），关掉的那个在这里直接短路，
 * 不会因为「开关关了但还在 addFlags」把屏幕一直点亮。
 */
public final class UiHud {

    private static final String TAG = "WxPicLabel";

    private static Toast toast;
    private static WeakReference<Activity> pinned = new WeakReference<>(null);
    private static boolean active;
    private static boolean toastOn;
    private static boolean keepOn;

    private UiHud() {
    }

    /** 开始一轮：按配置决定要不要弹 Toast / 要不要常亮，并绑上当前前台 Activity。 */
    public static void begin(Context ctx, boolean toastEnabled, boolean keepScreenOn) {
        toastOn = toastEnabled;
        keepOn = keepScreenOn;
        active = true;
        hold(ctx);
    }

    /** 发一张之前调一次：Activity 换了就换绑（相册 → 聊天）。 */
    public static void hold(Context ctx) {
        if (!active || !keepOn) {
            return;
        }
        Activity top = topActivity();
        Activity old = pinned.get();
        if (top == null) {
            return;
        }
        if (old == top) {
            return;
        }
        clearFlag(old);
        addFlag(top);
        pinned = new WeakReference<>(top);
    }

    /** 进度提示：`正在发送 3/12`。 */
    public static void progress(Context ctx, int done, int total) {
        if (!active || !toastOn || ctx == null) {
            return;
        }
        try {
            String text = "正在发送 " + done + "/" + total;
            if (toast == null) {
                toast = Toast.makeText(ctx.getApplicationContext(), text, Toast.LENGTH_SHORT);
            }
            toast.setText(text);
            toast.show();
        } catch (Throwable t) {
            android.util.Log.i(TAG, "hud: toast failed: " + t);
        }
    }

    /** 结束：撤掉常亮、收起 Toast。 */
    public static void end() {
        active = false;
        clearFlag(pinned.get());
        pinned = new WeakReference<>(null);
        try {
            if (toast != null) {
                toast.cancel();
            }
        } catch (Throwable ignored) {
            // 取消失败不影响
        }
        toast = null;
        toastOn = false;
        keepOn = false;
    }

    private static void addFlag(Activity act) {
        try {
            if (act != null && act.getWindow() != null) {
                act.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                android.util.Log.i(TAG, "hud: keep-screen-on " + act.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            android.util.Log.i(TAG, "hud: add flag failed: " + t);
        }
    }

    private static void clearFlag(Activity act) {
        try {
            if (act != null && act.getWindow() != null) {
                act.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                android.util.Log.i(TAG, "hud: keep-screen-off " + act.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            android.util.Log.i(TAG, "hud: clear flag failed: " + t);
        }
    }

    /** 当前未暂停的 Activity（框架类，字段名稳定）。 */
    private static Activity topActivity() {
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
                Object a = readField(rec, "activity");
                if (!(a instanceof Activity)) {
                    continue;
                }
                Activity act = (Activity) a;
                if (act.isFinishing() || act.getWindow() == null) {
                    continue;
                }
                if (Boolean.FALSE.equals(readField(rec, "paused"))) {
                    best = act;
                }
            }
            return best;
        } catch (Throwable t) {
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
}
