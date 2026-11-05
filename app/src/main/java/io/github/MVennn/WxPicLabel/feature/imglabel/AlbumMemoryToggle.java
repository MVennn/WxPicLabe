package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 相册「文件名」开关 —— 在 {@code AlbumPreviewUI} 底部「原图」右边注入一个同款开关行。
 *
 * <p>微信 8.0.80 的「原图」不是一个 CheckBox，而是
 * {@code RelativeLayout(原图行) [WeImageView(圆圈图标) + TextView("原图")]}，
 * 原图行自身在底栏里 {@code CENTER_IN_PARENT}。所以这里不能克隆参数直接塞进去
 * （会与原图完全重叠），而是：
 *
 * <ol>
 *   <li>自建一行 {@code LinearLayout[ImageView(圆点图标) + TextView("文件名")]}，
 *       字号 / 颜色 / 字体 / 内边距 / 行高全部照抄原图行；</li>
 *   <li>插进底栏（原图行的父容器），同样居中；</li>
 *   <li>布局完成后按两行实测宽度做水平错位：原图左移 (间距+本行宽)/2，
 *       本行右移 (原图行宽+间距)/2 —— 视觉上「原图在左、文件名在右」，整体仍居中。</li>
 * </ol>
 *
 * <p>行为上它是「发送图片文件名」的现场开关：
 *
 * <ul>
 *   <li>勾选 → 这一批照常接管、补文件名；</li>
 *   <li>不勾选 → 微信原生发送，不带文件名；</li>
 *   <li>状态落在微信进程自己的 SharedPreferences（{@link #PREFS} / {@link #KEY}），
 *       退出相册 / 重启微信都不会被重置，只有手动点它才会变。</li>
 * </ul>
 *
 * <p>显隐跟随模块配置：总开关（feature_img_label）关闭时完全不注入；
 * 「仅选中的会话生效」开着时，名单外的会话不注入（那里功能本来就不生效）。
 */
public final class AlbumMemoryToggle {

    private AlbumMemoryToggle() {
    }

    // ------------------------------------------------------------------ 状态

    /** 状态存在微信进程自己的偏好文件里：模块 App 与 remote preferences 都够不着它。 */
    public static final String PREFS = "wxpiclabel_state";
    public static final String KEY = "album_memory_on";
    /** 出厂默认勾选 —— 保持「装上就能用」的历史行为，不勾才是用户主动的选择。 */
    public static final boolean DEFAULT_ON = true;

    /** 进程内缓存，避免每次拦截都读盘；点击时同步更新。 */
    private static volatile Boolean cached;

    /** 发文件名功能此刻是否应该生效（模块侧闸门用）。 */
    public static boolean isOn(Context ctx) {
        Boolean c = cached;
        if (c != null) {
            return c;
        }
        boolean v = DEFAULT_ON;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            v = p.getBoolean(KEY, DEFAULT_ON);
        } catch (Throwable ignored) {
            // 读不到就按默认
        }
        cached = v;
        return v;
    }

    public static void setOn(Context ctx, boolean on) {
        cached = on;
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY, on).apply();
        } catch (Throwable ignored) {
            // 缓存仍然生效；落盘失败只影响重启后的恢复
        }
    }

    // ------------------------------------------------------------------ 注入

    /** 回调接口：宿主模块的日志方法直接传 {@code module::log}。 */
    public interface LogFn {
        void log(String msg);
    }

    private static final String TAG_MARK = "wxpl_filename_toggle";
    /** 两行之间的水平间距（dp）：太近会和「原图」连成一个整体，12dp 是并排看最舒服的距离 */
    private static final int GAP_DP = 12;

    /**
     * 在给定的相册页里注入「文件名」开关。
     *
     * <p>{@code show} 同时控制两件事：没注入过时才创建；注入过但本轮不该显示
     * （总开关 / 名单判定变了）时置 {@code GONE}，恢复时再 {@code VISIBLE}。
     *
     * @return 结果描述（日志用）；{@code null} 表示成功。
     */
    public static String inject(Activity act, boolean show, LogFn log) {
        try {
            if (act == null || act.getWindow() == null) {
                return "album: no window";
            }
            if (!enabledByProp()) {
                return null;   // 排障开关：debug.wxpiclabel.albumtoggle=0 时不注入
            }
            View root = act.getWindow().getDecorView();
            dumpIfRequested(root);

            View existing = findTagged(root);
            View srcText = findTextMatch(root, new String[]{"原图", "Full Image"});
            if (srcText == null) {
                if (existing != null) {
                    existing.setVisibility(View.GONE);
                }
                return "album: 原图 view not found";
            }
            View srcRow = (View) srcText.getParent();
            if (srcRow == null || !(srcRow.getParent() instanceof ViewGroup)) {
                return "album: 原图 row has no parent group";
            }
            ViewGroup bar = (ViewGroup) srcRow.getParent();

            View mine = existing;
            if (mine == null) {
                if (!show) {
                    return null;   // 该会话不显示：什么都不注入
                }
                mine = buildRow(act, srcRow, srcText, log);
                if (mine == null) {
                    return "album: build row failed";
                }
                bar.addView(mine, barParams(srcRow, dp(act, GAP_DP)));
                log.log("filename toggle: injected into " + bar.getClass().getName()
                        + " after " + srcRow.getClass().getName());
            }
            mine.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) {
                srcRow.setTranslationX(0f);
                return null;
            }
            applyOffset(srcRow, mine, act);
            return null;
        } catch (Throwable t) {
            return "album: inject failed: " + t;
        }
    }

    /**
     * 把原图行与本行整体居中排开：原图左移 (间距 + 本行宽) / 2，本行右移 (原图行宽 + 间距) / 2。
     *
     * <p>用 translationX 而不是改 LayoutParams：微信自己会按选中数量调原图行的
     * 可见性与内边距，改参数会被它覆盖甚至抛类型转换异常；位移是纯渲染属性，不参与测量。
     */
    /**
     * 把「原图 + 文件名」这一对整体摆正。
     *
     * <p>本行的布局位置是「原图行右侧 + 间距」（见 {@link #barParams}），两行在布局坐标里
     * 是并排的；这里只把**一对**一起平移，让这对的中心落在底栏中心。
     * 平移量随布局重算 —— 微信勾上「原图」后底栏会多出一行、整体变高，原图行自己会挪，
     * 本行因为上下都对齐到原图行，会跟着一起挪，不会各走各的。
     */
    private static void applyOffset(final View srcRow, final View mine, Activity act) {
        final int gap = dp(act, GAP_DP);
        final ViewGroup bar = (ViewGroup) mine.getParent();
        mine.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int w1 = srcRow.getWidth();
                int w2 = mine.getWidth();
                if (w1 <= 0 || w2 <= 0 || bar.getWidth() <= 0) {
                    return;   // 还没量出来，等下一次布局
                }
                float dx = (bar.getWidth() - (w1 + gap + w2)) / 2f - srcRow.getLeft();
                srcRow.setTranslationX(dx);
                mine.setTranslationX(dx);
                StringBuilder kids = new StringBuilder();
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View k = bar.getChildAt(i);
                    kids.append(' ').append(k.getClass().getSimpleName())
                            .append('/').append(k.getVisibility())
                            .append('/').append(box(k));
                }
                android.util.Log.i("WxPicLabel", "filename toggle: geom bar=" + h(bar)
                        + " src=" + box(srcRow) + " mine=" + box(mine) + " dx=" + (int) dx
                        + " kids:" + kids);
            }
        });
    }

    private static String h(View v) {
        return v == null ? "-" : (v.getWidth() + "x" + v.getHeight());
    }

    private static String box(View v) {
        return v == null ? "-" : (v.getLeft() + "," + v.getTop() + "," + v.getRight() + "," + v.getBottom());
    }

    // ------------------------------------------------------------------ 造行

    /**
     * 造一行与「原图」同构的开关：圆点图标 + 文字。
     *
     * <p>图标是自绘的（{@link DotIcon}）：未选 = 白色圆环，已选 = 微信绿实心 + 白色对勾。
     * 不读微信的 svg 资源（res/raw 下的 svg 只有微信自己的 SVG 渲染器能画），
     * 这样版本升级也不会因为资源 id 漂移而画不出来。
     */
    private static View buildRow(final Activity act, View srcRow, View srcText, final LogFn log) {
        try {
            final Context ctx = act;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag(TAG_MARK);

            // 图标：尺寸照抄原图行的 WeImageView（26×行高）
            View srcIcon = srcRow instanceof ViewGroup ? ((ViewGroup) srcRow).getChildAt(0) : null;
            final ImageView icon = new ImageView(ctx);
            final DotIcon drawable = new DotIcon(ctx, isOn(ctx));
            icon.setImageDrawable(drawable);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                    srcIcon != null && srcIcon.getLayoutParams() != null
                            ? srcIcon.getLayoutParams().width : LinearLayout.LayoutParams.WRAP_CONTENT,
                    srcRow.getLayoutParams() != null
                            ? srcRow.getLayoutParams().height : LinearLayout.LayoutParams.WRAP_CONTENT);
            iconLp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(icon, iconLp);

            // 文字：样式照抄「原图」那个 TextView
            TextView label = new TextView(ctx);
            label.setText(labelFor(srcText));
            if (srcText instanceof TextView) {
                TextView t = (TextView) srcText;
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize());
                try {
                    label.setTextColor(t.getTextColors());
                } catch (Throwable ignored) {
                }
                try {
                    label.setTypeface(t.getTypeface());
                } catch (Throwable ignored) {
                }
                label.setIncludeFontPadding(t.getIncludeFontPadding());
                label.setPadding(t.getPaddingLeft(), t.getPaddingTop(),
                        t.getPaddingRight(), t.getPaddingBottom());
            }
            label.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textLp.leftMargin = dp(act, 5);
            row.addView(label, textLp);

            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                boolean next = !isOn(ctx);
                setOn(ctx, next);
                drawable.setOn(next);
                if (log != null) {
                    log.log("filename toggle: " + next);
                }
            });
            return row;
        } catch (Throwable t) {
            if (log != null) {
                log.log("filename toggle: build failed: " + t);
            }
            return null;
        }
    }

    /**
     * 底栏里本行的参数：**上下都对齐到原图行**、水平贴在它右边一个间距的位置。
     *
     * <p>不用 {@code CENTER_IN_PARENT}：底栏是 wrap 高度且贴底，勾上「原图」后微信会
     * 让底栏多出一行（72 → 140px），居中元素会整体上跳；对齐到原图行则两行始终同一水平线。
     */
    private static ViewGroup.LayoutParams barParams(View srcRow, int gap) {
        try {
            ViewGroup.LayoutParams sp = srcRow.getLayoutParams();
            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    sp != null ? sp.height : ViewGroup.LayoutParams.WRAP_CONTENT);
            int id = srcRow.getId();
            if (id != View.NO_ID) {
                lp.addRule(RelativeLayout.RIGHT_OF, id);
                lp.addRule(RelativeLayout.ALIGN_TOP, id);
                lp.addRule(RelativeLayout.ALIGN_BOTTOM, id);
                lp.leftMargin = gap;
            } else {
                lp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            }
            return lp;
        } catch (Throwable t) {
            return new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** 中文相册给「文件名」，英文相册给「Filename」。 */
    private static String labelFor(View src) {
        if (src instanceof TextView) {
            String t = ((TextView) src).getText().toString();
            if (t != null && t.startsWith("Full")) {
                return "Filename";
            }
        }
        return "文件名";
    }

    // ------------------------------------------------------------------ 查找

    /** 找本模块注入过的那一行（按 tag 标记，页面复用时幂等）。 */
    private static View findTagged(View root) {
        if (TAG_MARK.equals(root.getTag())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findTagged(g.getChildAt(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** 深度优先找文本精确等于 candidates 的第一个 TextView（跳过输入框）。 */
    private static TextView findTextMatch(View root, String[] candidates) {
        if (root instanceof TextView && !(root instanceof EditText)) {
            String t = ((TextView) root).getText().toString();
            for (String c : candidates) {
                if (c.equals(t)) {
                    return (TextView) root;
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findTextMatch(g.getChildAt(i), candidates);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static int dp(Context ctx, int value) {
        try {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    ctx.getResources().getDisplayMetrics()));
        } catch (Throwable t) {
            return value * 2;
        }
    }

    // ------------------------------------------------------------------ 图标

    /** 与「原图」同款观感的圆点：未选白色圆环，已选微信绿实心 + 白色对勾。 */
    static final class DotIcon extends Drawable {

        private static final int GREEN = 0xFF07C160;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF oval = new RectF();
        private final float sizePx;
        private boolean on;

        DotIcon(Context ctx, boolean on) {
            this.on = on;
            float d = ctx.getResources().getDisplayMetrics().density;
            sizePx = 20f * d;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.4f * d);
            ring.setColor(0xCCFFFFFF);
            solid.setStyle(Paint.Style.FILL);
            solid.setColor(GREEN);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(2f * d);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
            tick.setColor(Color.WHITE);
        }

        void setOn(boolean on) {
            this.on = on;
            invalidateSelf();
        }

        @Override
        public int getIntrinsicWidth() {
            return (int) sizePx;
        }

        @Override
        public int getIntrinsicHeight() {
            return (int) sizePx;
        }

        @Override
        public void draw(Canvas c) {
            RectF r = bounds();
            float cx = r.centerX();
            float cy = r.centerY();
            float radius = sizePx / 2f - 1f;
            if (on) {
                c.drawCircle(cx, cy, radius, solid);
                path.reset();
                path.moveTo(cx - radius * 0.45f, cy + radius * 0.02f);
                path.lineTo(cx - radius * 0.1f, cy + radius * 0.38f);
                path.lineTo(cx + radius * 0.48f, cy - radius * 0.32f);
                c.drawPath(path, tick);
            } else {
                c.drawCircle(cx, cy, radius, ring);
            }
        }

        private RectF bounds() {
            oval.set(getBounds());
            return oval;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------ talker

    /**
     * 相册这一批要发给谁：先看相册自己的 Intent（GalleryUI_ToUser），
     * 再扫进程里活着的聊天页（Chat_User）。都拿不到返回 {@code null} ——
     * 调用方按「放行」处理，跟 ScopeGate 的口径一致。
     */
    public static String talkerOfAlbum(Activity act) {
        try {
            Intent it = act.getIntent();
            String t = it == null ? null : it.getStringExtra("GalleryUI_ToUser");
            if (t != null && !t.isEmpty()) {
                return t;
            }
        } catch (Throwable ignored) {
        }
        return chatTalkerFromActivities();
    }

    /** 扫 ActivityThread.mActivities：还活着的聊天页 Intent 里带 Chat_User。 */
    private static String chatTalkerFromActivities() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getDeclaredMethod("currentActivityThread");
            Object thread = m.invoke(null);
            Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof java.util.Map)) {
                return null;
            }
            String hit = null;
            for (Object rec : new ArrayList<Object>(((java.util.Map<?, ?>) map).values())) {
                if (rec == null) {
                    continue;
                }
                try {
                    Method ga = rec.getClass().getMethod("getActivity");
                    Object a = ga.invoke(rec);
                    if (!(a instanceof Activity)) {
                        continue;
                    }
                    Intent it = ((Activity) a).getIntent();
                    String t = it == null ? null : it.getStringExtra("Chat_User");
                    if (t != null && !t.isEmpty()) {
                        hit = t;   // 取最后一个活着的聊天页
                    }
                } catch (Throwable ignored) {
                    // 这条记录不是 ActivityClientRecord
                }
            }
            return hit;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 探针

    /** 排障开关：debug.wxpiclabel.albumtoggle=0 关掉注入（默认开）。 */
    private static boolean enabledByProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, "debug.wxpiclabel.albumtoggle", "");
            return !(v instanceof String) || !"0".equals(((String) v).trim());
        } catch (Throwable t) {
            return true;
        }
    }

    /** debug.wxpiclabel.uidump=1 时把整棵视图树打到日志，用于定位布局结构。 */
    private static void dumpIfRequested(View root) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            Object v = get.invoke(null, "debug.wxpiclabel.uidump", "");
            if (!(v instanceof String) || !"1".equals(((String) v).trim())) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            dumpView(root, 0, sb);
            // 日志按行打印，先把视图分隔符拍平，保证整段都能被 grep 到
            String flat = sb.toString().replace("\n", "  ||  ");
            int chunk = 3500;
            for (int i = 0; i < flat.length(); i += chunk) {
                android.util.Log.i("WxPicLabel", "uidump#" + (i / chunk) + ": "
                        + flat.substring(i, Math.min(flat.length(), i + chunk)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void dumpView(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) {
            sb.append(' ');
        }
        sb.append(v.getClass().getSimpleName()).append('@')
                .append(Integer.toHexString(System.identityHashCode(v)));
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String name = v.getResources().getResourceEntryName(id);
                sb.append(" id=").append(name);
            }
        } catch (Throwable ignored) {
        }
        if (v instanceof TextView) {
            sb.append(" text=\"").append(((TextView) v).getText()).append('"');
        }
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (p != null) {
            sb.append(" lp=").append(p.getClass().getSimpleName())
                    .append('(').append(p.width).append('x').append(p.height);
            if (p instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) p;
                sb.append(" grav=").append(ll.gravity).append(" weight=").append(ll.weight);
            }
            if (p instanceof RelativeLayout.LayoutParams) {
                try {
                    Field rf = RelativeLayout.LayoutParams.class.getDeclaredField("mRules");
                    rf.setAccessible(true);
                    int[] rules = (int[]) rf.get(p);
                    sb.append(" rules=");
                    for (int i = 0; i < rules.length; i++) {
                        if (rules[i] != 0) {
                            sb.append(i).append(':').append(rules[i]).append(' ');
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (p instanceof FrameLayout.LayoutParams) {
                sb.append(" grav=").append(((FrameLayout.LayoutParams) p).gravity);
            }
            if (p instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams ml = (ViewGroup.MarginLayoutParams) p;
                sb.append(" mar=").append(ml.leftMargin).append(',').append(ml.topMargin)
                        .append(',').append(ml.rightMargin).append(',').append(ml.bottomMargin);
            }
            sb.append(')');
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpView(g.getChildAt(i), depth + 1, sb);
            }
        }
    }
}
