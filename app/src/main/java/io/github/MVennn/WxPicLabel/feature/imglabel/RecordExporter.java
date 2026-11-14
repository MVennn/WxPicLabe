package io.github.MVennn.WxPicLabel.feature.imglabel;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.util.List;

/**
 * 把「发送记录」整份回传给模块 App（走 {@code FriendBridgeProvider} 的 {@code /records}）。
 *
 * <h2>为什么整份文档、而不是每条一行</h2>
 *
 * 记录页要按时间段分组、要搜索、要能改能删，所以除了老的聚合计数表（{@code records}）
 * 还需要逐次发送台账（{@code events}）。台账和计数表必须**同一时刻**的同一份快照，
 * 分两条通道回传会出现「计数说 5 次、台账只有 4 条」的错位。
 *
 * <h2>为什么按字符切片</h2>
 *
 * 以前是「每 200 条一段 JSON 数组元素」，Provider 用逗号拼起来。整份文档没法这么切，
 * 所以改成**按字符切原始 JSON 文本**，Provider 只负责按 {@code index} 顺序拼接 ——
 * 它不需要懂 JSON 结构。{@link #CHUNK_CHARS} 的取法见字段注释。
 *
 * <h2>失败即降级</h2>
 *
 * 任何异常只打日志。发送链路与记录显示互不牵连。
 */
public final class RecordExporter {

    /**
     * 每片最多多少**字符**（UTF-16 code unit）。
     *
     * <p>Binder 事务上限约 1MB，这里留两个数量级的余量：一份 600 条台账的文档约 40KB，
     * 切完十来片，每片远小于上限；同时片数不至于多到让 commit 变慢。
     */
    private static final int CHUNK_CHARS = 3000;

    private static final String URI_RECORDS =
            "content://" + io.github.MVennn.WxPicLabel.core.BridgeProtocol.AUTHORITY
                    + "/" + io.github.MVennn.WxPicLabel.core.BridgeProtocol.PATH_RECORDS;

    private RecordExporter() {
    }

    /**
     * 回传一份完整快照。
     *
     * @param rows   聚合计数表（名称 / 次数 / 最后时间），可为 null
     * @param events 逐次发送台账（按时间正序），可为 null
     * @param edited 最近一次已应用的手工修改令牌，空串表示还没应用过
     */
    public static boolean push(Context ctx,
                              List<RepeatCounter.Row> rows,
                              List<RepeatCounter.Event> events,
                              String edited,
                              FriendExporter.Logger log) {
        if (ctx == null) {
            return false;
        }
        try {
            String doc = buildDoc(rows, events, edited);
            int total = Math.max(1, (doc.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
            Uri uri = Uri.parse(URI_RECORDS);
            int from = 0;
            for (int i = 0; i < total; i++) {
                int to = Math.min(doc.length(), from + CHUNK_CHARS);
                // 不要从代理对中间切开：切点落在低位代理前就退一格
                if (to < doc.length() && to > from + 1
                        && Character.isLowSurrogate(doc.charAt(to))
                        && Character.isHighSurrogate(doc.charAt(to - 1))) {
                    to--;
                }
                ContentValues v = new ContentValues();
                v.put("chunk", doc.substring(from, to));
                v.put("index", i);
                v.put("total", total);
                // provider 拒收（进程被冻住 / 规模闸门 / 白名单不符）时返回 null：
                // 这时必须整批放弃，否则最后一片到达时会把「缺片」的文档当成完整快照落盘，
                // 记录页显示的就是一段残缺 JSON。返回 false 让模块侧的退避重试接手。
                if (ctx.getContentResolver().insert(uri, v) == null) {
                    log.log("records: chunk " + i + "/" + total + " rejected, abort push");
                    return false;
                }
                from = to;
            }
            int rowCount = rows == null ? 0 : rows.size();
            int evCount = events == null ? 0 : events.size();
            log.log("records: pushed " + rowCount + " row(s) / " + evCount
                    + " event(s) in " + total + " chunk(s)");
            return true;
        } catch (Throwable t) {
            // 失败必须让调用方知道：模块 App 的 provider 被系统冻住时这里会抛，
            // 一次失败就再也不推的话，记录页会永远是旧数据
            log.log("records: push failed: " + t);
            return false;
        }
    }

    /**
     * 拼整份文档。字段名故意取短名（{@code s/t/l/n/b}），因为台账可能有几百条，
     * 键名长度会直接变成切片数量。
     */
    private static String buildDoc(List<RepeatCounter.Row> rows,
                                   List<RepeatCounter.Event> events,
                                   String edited) {
        // 一次拼完整份文档（可能 40KB）：按行数与事件数预估容量，省掉十几次扩容拷贝。
        // 估小了只是退化成原来那种边拼边长，估大了也就多占一块临时数组。
        int cap = 256;
        if (rows != null) {
            cap += rows.size() * 64;
        }
        if (events != null) {
            cap += events.size() * 96;
        }
        StringBuilder sb = new StringBuilder(Math.max(1024, cap));
        sb.append("{\"at\":").append(System.currentTimeMillis())
                .append(",\"v\":")
                .append(io.github.MVennn.WxPicLabel.core.BridgeProtocol.SCHEMA)
                .append(",\"edited\":\"").append(FriendExporter.jsonEscape(edited == null ? "" : edited))
                .append("\",\"records\":[");
        if (rows != null) {
            for (int i = 0; i < rows.size(); i++) {
                RepeatCounter.Row r = rows.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"label\":\"").append(FriendExporter.jsonEscape(r.label))
                        .append("\",\"count\":").append(r.count)
                        .append(",\"last\":").append(r.lastMs)
                        .append('}');
            }
        }
        sb.append("],\"events\":[");
        if (events != null) {
            for (int i = 0; i < events.size(); i++) {
                RepeatCounter.Event ev = events.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"s\":").append(ev.seq)
                        .append(",\"t\":").append(ev.at)
                        .append(",\"l\":\"").append(FriendExporter.jsonEscape(ev.label))
                        .append("\",\"n\":").append(ev.n)
                        .append(",\"b\":\"").append(FriendExporter.jsonEscape(ev.batch))
                        .append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
