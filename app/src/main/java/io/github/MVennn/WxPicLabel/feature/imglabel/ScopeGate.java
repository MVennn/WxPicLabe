package io.github.MVennn.WxPicLabel.feature.imglabel;

import java.util.Set;

/**
 * 会话白名单判定 —— 「增加 文件名 开关」只在勾选的好友 / 群聊里生效。
 *
 * <p>名单本身存在 remote preferences（模块 App 写、微信进程只读），
 * 这里只做纯判定，不碰任何 IO，方便在拦截点（主线程）上同步调用。
 *
 * <p>三条放行规则，方向一致：**宁可多生效，不可整体失效**。
 *
 * <ol>
 *   <li>开关关着 —— 回到「所有会话都带文件名」的默认行为；</li>
 *   <li>开关开着但名单是空的 —— 用户还没选人，按不限制处理；</li>
 *   <li>拿不到 talker（版本漂移 / 字段改名）—— 放行，让功能照常工作。</li>
 * </ol>
 */
public final class ScopeGate {

    private ScopeGate() {
    }

    public static boolean allowed(boolean onlySelected, Set<String> ids, String talker) {
        if (!onlySelected) {
            return true;
        }
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        if (talker == null || talker.isEmpty()) {
            return true;
        }
        return ids.contains(talker);
    }
}
