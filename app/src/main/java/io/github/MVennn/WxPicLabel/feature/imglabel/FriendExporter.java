package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.database.Cursor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.MVennn.WxPicLabel.util.Reflect;

/**
 * 从**当前登录的微信账号**里读取好友与群聊。
 *
 * <p>这里不写死任何 wxid / 群 id —— 读的是运行期 `rcontact` 表里属于这个账号的数据，
 * 所以换个人装同一个模块，看到的就是他自己的好友与群。
 *
 * <p>数据源是微信自己的联系人存储 `com.tencent.mm.storage.k4`：
 *
 * <pre>
 *   好友  k4#K()   select … from rcontact
 *                  where type &amp; 1 != 0 and type &amp; 32 = 0 and type &amp; 8 = 0 and verifyFlag &amp; 8 = 0
 *   群聊  k4#r()   select … from rcontact where type &amp; 8 = 0 and username like '%@chatroom'
 * </pre>
 *
 * <p>实例走官方 getter `h2#Fj()`（`new k4(...)`），而不是服务定位器 —— 全 dex 扫
 * `const-class k4` + `fi5/n0#c` 零命中，k4 没注册在服务定位器里。
 *
 * <p>只读 Cursor 的列（username / nickname / conRemark），不碰实体 getter：
 * `z3` 的 getter 混淆严重，混着 i18n 与地图逻辑，读列最稳。
 */
public final class FriendExporter {

    /** 每批回传条数上限（与 FriendBridgeProvider.CHUNK_SIZE 保持一致）。 */
    public static final int CHUNK_SIZE = 200;

    /** 好友 */
    public static final int KIND_FRIEND = 0;
    /** 群聊 */
    public static final int KIND_CHATROOM = 1;

    /** 一条候选会话。 */
    public static final class Entry {
        public final String id;
        public final String name;
        public final int kind;

        Entry(String id, String name, int kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }

        @Override
        public String toString() {
            return (kind == KIND_CHATROOM ? "room:" : "friend:") + id + "=" + name;
        }
    }

    /** 日志出口；目标进程侧传模块的 log，避免这个类依赖 Xposed。 */
    public interface Logger {
        void log(String msg);
    }

    /**
     * 微信内置的系统会话 —— 它们在 rcontact 里也满足「好友」的位条件，
     * 但通讯录里不显示，勾选名单里也不该出现。
     *
     * <p>这份名单**跨账号固定**（微信自己写死的功能号），与登录的是哪个微信号无关。
     */
    private static final String[] SYSTEM_IDS = {
            "filehelper", "weixin", "medianote", "floatbottle", "newsapp",
            "qqmail", "fmessage", "notification_messages", "tmessage", "qmessage",
            "brands", "officialaccounts", "weixinreminder", "voicevoipapp",
            "subscripmessage", "mphelper", "weibo", "qqsync", "shakeapp",
    };

    private static boolean isSystem(String id) {
        for (String s : SYSTEM_IDS) {
            if (s.equalsIgnoreCase(id)) {
                return true;
            }
        }
        // @ 开头的是群 / 企业微信 / 陌生人，都不属于「好友」
        return id.startsWith("@");
    }

    private FriendExporter() {
    }

    /** 抓一份完整名单；任何一环失败都只少一类数据，不抛异常。 */
    public static List<Entry> fetch(ClassLoader cl, Logger log) {
        List<Entry> out = new ArrayList<>();
        Object stg = contactStorage(cl, log);
        if (stg == null) {
            return out;
        }
        read(stg, "K", KIND_FRIEND, out, log);
        read(stg, "r", KIND_CHATROOM, out, log);
        return out;
    }

    // ------------------------------------------------------------------ 取实例

    private static Object contactStorage(ClassLoader cl, Logger log) {
        Class<?> h2 = Reflect.findClass("com.tencent.mm.plugin.messenger.foundation.h2", cl);
        Class<?> iface = Reflect.findClass("gp3.c4", cl);
        Object svc = null;
        if (iface != null) {
            svc = locate(iface, cl);
        }
        if (svc == null && h2 != null) {
            svc = locate(h2, cl);
        }
        if (svc == null) {
            log.log("friends: contact storage service not resolved");
            return null;
        }
        Object stg = Reflect.invoke(svc, Reflect.findMethod(svc.getClass(), "Fj"));
        if (stg == null) {
            log.log("friends: h2#Fj returned null");
        }
        return stg;
    }

    /** 微信自己的服务定位器 {@code fi5.n0#c(Class)}。 */
    private static Object locate(Class<?> key, ClassLoader cl) {
        Class<?> n0 = Reflect.findClass("fi5.n0", cl);
        if (n0 == null) {
            return null;
        }
        for (Method m : n0.getDeclaredMethods()) {
            if ("c".equals(m.getName()) && m.getParameterCount() == 1
                    && m.getParameterTypes()[0] == Class.class) {
                try {
                    m.setAccessible(true);
                    return m.invoke(null, key);
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 读表

    private static void read(Object stg, String method, int kind, List<Entry> out, Logger log) {
        Cursor c = null;
        try {
            Object v = Reflect.invoke(stg, Reflect.findMethod(stg.getClass(), method));
            if (!(v instanceof Cursor)) {
                log.log("friends: k4#" + method + " not a cursor: " + v);
                return;
            }
            c = (Cursor) v;
            int before = out.size();
            collect(c, kind, out);
            log.log("friends: k4#" + method + " rows=" + (out.size() - before));
        } catch (Throwable t) {
            log.log("friends: k4#" + method + " failed: " + t);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {
                    // 关不掉就算了
                }
            }
        }
    }

    private static void collect(Cursor c, int kind, List<Entry> out) {
        int iu = c.getColumnIndex("username");
        int in = c.getColumnIndex("nickname");
        int ir = c.getColumnIndex("conRemark");
        if (iu < 0) {
            return;
        }
        while (c.moveToNext()) {
            String id = c.getString(iu);
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (kind == KIND_FRIEND && isSystem(id)) {
                continue;
            }
            // 备注优先，其次昵称，最后退回 id —— 与微信通讯录的显示顺序一致
            String name = ir >= 0 ? c.getString(ir) : null;
            if (name == null || name.isEmpty()) {
                name = in >= 0 ? c.getString(in) : null;
            }
            if (name == null || name.isEmpty()) {
                name = id;
            }
            out.add(new Entry(id, name, kind));
        }
    }
    // ------------------------------------------------------------------ 回传

    /**
     * 把抓到的名单分片写进模块 App 的回传 Provider（FriendBridgeProvider）。
     *
     * 每批 [CHUNK_SIZE] 条一条 insert，避开 Binder ~1MB 的事务上限；
     * Provider 侧收到最后一片时按序拼成完整 JSON 全量落盘。
     */
    public static void push(android.content.Context ctx, List<Entry> list, Logger log) {
        if (ctx == null || list == null || list.isEmpty()) {
            return;
        }
        try {
            int total = (list.size() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            android.net.Uri uri = android.net.Uri.parse(
                    "content://io.github.MVennn.WxPicLabel.bridge/friends");
            for (int i = 0; i < total; i++) {
                StringBuilder sb = new StringBuilder();
                int end = Math.min(list.size(), (i + 1) * CHUNK_SIZE);
                for (int k = i * CHUNK_SIZE; k < end; k++) {
                    Entry e = list.get(k);
                    sb.append('{')
                      .append("\"id\":\"").append(jsonEscape(e.id))
                      .append("\",\"name\":\"").append(jsonEscape(e.name))
                      .append("\",\"kind\":").append(e.kind)
                      .append("},");
                }
                android.content.ContentValues v = new android.content.ContentValues();
                v.put("chunk", sb.toString());
                v.put("index", i);
                v.put("total", total);
                ctx.getContentResolver().insert(uri, v);
            }
            log.log("friends: pushed " + list.size() + " in " + total + " chunk(s)");
        } catch (Throwable t) {
            log.log("friends: push failed: " + t);
        }
    }

    /** 最小 JSON 字符串转义：引号、反斜杠与控制字符。 */
    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    b.append("\\\\");
                    break;
                case '"':
                    b.append("\\\"");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}
