package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.util.List;

/**
 * 把「同名图片计数表」回传给模块 App（走 {@code FriendBridgeProvider} 的 {@code /records}）。
 *
 * <p>计数表落在微信进程自己的 SharedPreferences 里（跨 uid 不可写），模块 App 读不到，
 * 所以「发送记录」页面必须靠这条通道拿数据 —— 与好友名单同一套分片协议：
 * 每批 200 条一条 insert，最后一片到达时 Provider 按序拼成完整 JSON 全量覆盖。
 *
 * <p>任何异常只打日志，不影响发送链路。
 */
public final class RecordExporter {

    /** 与 FriendExporter 保持一致，避开 Binder ~1MB 事务上限。 */
    private static final int CHUNK_SIZE = 200;

    private static final String URI_RECORDS = "content://io.github.MVennn.WxPicLabel.bridge/records";

    private RecordExporter() {
    }

    public static void push(Context ctx, List<RepeatCounter.Row> rows, FriendExporter.Logger log) {
        if (ctx == null) {
            return;
        }
        try {
            int size = rows == null ? 0 : rows.size();
            int total = Math.max(1, (size + CHUNK_SIZE - 1) / CHUNK_SIZE);
            Uri uri = Uri.parse(URI_RECORDS);
            for (int i = 0; i < total; i++) {
                StringBuilder sb = new StringBuilder();
                int end = Math.min(size, (i + 1) * CHUNK_SIZE);
                for (int k = i * CHUNK_SIZE; k < end; k++) {
                    RepeatCounter.Row r = rows.get(k);
                    sb.append('{')
                            .append("\"label\":\"").append(FriendExporter.jsonEscape(r.label))
                            .append("\",\"count\":").append(r.count)
                            .append(",\"last\":").append(r.lastMs)
                            .append("},");
                }
                ContentValues v = new ContentValues();
                v.put("chunk", sb.toString());
                v.put("index", i);
                v.put("total", total);
                ctx.getContentResolver().insert(uri, v);
            }
            log.log("records: pushed " + size + " in " + total + " chunk(s)");
        } catch (Throwable t) {
            log.log("records: push failed: " + t);
        }
    }
}
