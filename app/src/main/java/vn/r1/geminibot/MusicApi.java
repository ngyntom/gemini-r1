package vn.r1.geminibot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * API /api/music/* cùng tên, cùng dữ liệu với Voicebot trên Pi (webui.py) để trang Nhạc của Pi
 * (assets/web/static/pages/music.html) chạy trên loa. Những phần loa không có (chọn thiết bị Home
 * Assistant để cast, tài khoản) trả về giá trị rỗng hợp lệ.
 */
final class MusicApi {
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS).connectTimeout(15, TimeUnit.SECONDS).build();

    private MusicApi() {}

    /** Trả true nếu đã trả lời request. */
    static boolean handle(Context ctx, Socket s, String method, String path, Map<String, String> query,
                          Map<String, String> headers, byte[] body) throws Exception {
        if (!path.startsWith("/api/music/")) return false;
        Music m = Music.get(ctx);
        String p = path.substring("/api/music/".length());
        if (method.equals("GET")) {
            switch (p) {
                case "status":
                    json(s, m.status());
                    return true;
                case "status/stream":
                    sse(s, m);
                    return true;
                case "suggest":
                    json(s, new JSONObject().put("suggestions", YouTube.suggest(q(query, "q"))));
                    return true;
                case "search": {
                    String q = q(query, "q");
                    if (q.isEmpty()) {
                        error(s, 400, "Thiếu tên bài hát");
                        return true;
                    }
                    try {
                        JSONArray r = YouTube.search(q, 30);
                        if (r.length() > 0) m.saveList(q, r);
                        json(s, new JSONObject().put("results", r));
                    } catch (Exception e) {
                        BotLog.w("Tìm nhạc '" + q + "': " + e.getMessage());
                        error(s, 500, "Không tìm được: " + e.getMessage());
                    }
                    return true;
                }
                case "list":
                    json(s, m.lastList());
                    return true;
                case "favorites":
                    json(s, new JSONObject().put("results", m.favorites()));
                    return true;
                case "playlists":
                    json(s, new JSONObject().put("playlists", m.playlists()));
                    return true;
                case "queue":
                    json(s, m.queueJson());
                    return true;
                case "kham_pha":
                    json(s, m.explore());
                    return true;
                case "volume":
                    json(s, new JSONObject().put("volume", m.volume()));
                    return true;
                case "thiet_bi": // loa không cast sang thiết bị Home Assistant: chỉ có "loa này"
                    json(s, new JSONObject().put("ha", new JSONArray())
                            .put("noi_phat", new JSONObject().put("loai", "pi")).put("loi", JSONObject.NULL));
                    return true;
                case "related": {
                    String id = q(query, "id");
                    try {
                        json(s, new JSONObject().put("results", YouTube.related(id,
                                new HashSet<>(Arrays.asList(q(query, "bo_qua").split(","))), 10)));
                    } catch (Exception e) {
                        error(s, 500, "Không lấy được bài gợi ý: " + e.getMessage());
                    }
                    return true;
                }
                case "channel": {
                    String id = q(query, "id");
                    if (!id.matches("^UC[A-Za-z0-9_-]{22}$")) {
                        error(s, 400, "id kênh không hợp lệ");
                        return true;
                    }
                    try {
                        JSONObject r = YouTube.channel(id, 30);
                        if (r.getJSONArray("results").length() > 0) {
                            m.saveList("Kênh: " + r.optString("channel", id), r.getJSONArray("results"));
                        }
                        json(s, r);
                    } catch (Exception e) {
                        error(s, 500, "Không lấy được video của kênh: " + e.getMessage());
                    }
                    return true;
                }
            }
            if (p.startsWith("kham_pha/")) {
                json(s, new JSONObject().put("bai", m.exploreRow(p.substring(9), "1".equals(query.get("moi")))));
                return true;
            }
            if (p.startsWith("stream_info/")) {
                JSONObject cl = null;
                try {
                    YouTube.Audio a = YouTube.audio(p.substring(12), false);
                    cl = new JSONObject().put("codec", a.codec).put("kbps", a.kbps).put("khz", JSONObject.NULL);
                } catch (Exception ignored) {
                }
                json(s, new JSONObject().put("chat_luong", cl != null ? cl : JSONObject.NULL));
                return true;
            }
            if (p.startsWith("stream/") || p.startsWith("stream_aac/")) {
                proxy(s, p.substring(p.indexOf('/') + 1), headers.get("range"));
                return true;
            }
            return false;
        }

        JSONObject req = body.length == 0 ? new JSONObject() : new JSONObject(new String(body, "UTF-8"));
        JSONObject ok = new JSONObject().put("ok", true);
        switch (p) {
            case "pause":
                json(s, ok.put("paused", m.pause()));
                return true;
            case "resume":
                m.resume();
                json(s, ok);
                return true;
            case "seek":
                if (!req.has("seconds")) {
                    error(s, 400, "Thiếu seconds");
                    return true;
                }
                json(s, ok.put("seeked", m.seek(req.optDouble("seconds", 0))));
                return true;
            case "stop":
                json(s, ok.put("message", m.stop()));
                return true;
            case "next":
                m.next();
                json(s, ok.put("message", "Bài tiếp theo"));
                return true;
            case "back":
                m.back();
                json(s, ok.put("message", "Bài trước"));
                return true;
            case "play": {
                String q = req.optString("query").trim();
                if (q.isEmpty()) {
                    error(s, 400, "Thiếu tên bài hát");
                    return true;
                }
                json(s, ok.put("message", m.playSearch(q)));
                return true;
            }
            case "play_item": {
                JSONArray entries = req.optJSONArray("entries");
                String msg = m.playList(req.optString("query"), entries != null ? entries : new JSONArray(),
                        req.optInt("index", -1), Math.max(0, req.optDouble("bat_dau_tu", 0)),
                        req.optBoolean("goi_y", true), req.optString("may_id"));
                json(s, ok.put("message", msg));
                return true;
            }
            case "repeat":
                json(s, ok.put("lap", m.setRepeat(req.optString("lap"))));
                return true;
            case "shuffle":
                json(s, new JSONObject().put("ok", m.shuffle(req.optBoolean("bat"))));
                return true;
            case "queue/goi_y":
                json(s, new JSONObject().put("ok", m.setAutoSuggest(req.optBoolean("bat"))));
                return true;
            case "queue/add":
                json(s, new JSONObject().put("ok", m.insert(req.optJSONObject("bai"), req.optBoolean("tiep_theo", true))));
                return true;
            case "history": {
                JSONObject bai = req.optJSONObject("bai");
                if (bai != null) m.history(bai);
                json(s, ok);
                return true;
            }
            case "favorites":
                json(s, new JSONObject().put("results", m.setFavorite(req.optJSONObject("bai"), req.optBoolean("them"))));
                return true;
            case "playlists":
                try {
                    JSONObject pl = m.createPlaylist(req.optString("ten"), req.optJSONArray("bai"));
                    json(s, new JSONObject().put("playlist", pl).put("playlists", m.playlists()));
                } catch (IllegalArgumentException e) {
                    error(s, 400, e.getMessage());
                }
                return true;
            case "volume": {
                int vol;
                if (req.has("level")) vol = m.setVolume(req.optInt("level", m.volume()));
                else if ("up".equals(req.optString("action"))) vol = m.setVolume(m.volume() + 5);
                else if ("down".equals(req.optString("action"))) vol = m.setVolume(m.volume() - 5);
                else {
                    error(s, 400, "cần 'level' hoặc action 'up'/'down'");
                    return true;
                }
                json(s, ok.put("volume", vol));
                return true;
            }
            case "noi_phat":
                json(s, ok.put("noi_phat", new JSONObject().put("loai", "pi")));
                return true;
            case "prefetch":
                json(s, ok);
                return true;
        }
        if (p.startsWith("playlists/")) {
            try {
                json(s, new JSONObject().put("playlists", m.editPlaylist(p.substring(10), req.optString("ten", null),
                        req.optBoolean("xoa"), req.optJSONObject("bai"), req.optBoolean("them", true))));
            } catch (IllegalArgumentException e) {
                error(s, 400, e.getMessage());
            }
            return true;
        }
        return false;
    }

    /** Trạng thái phát dạng SSE: gửi khi đổi, kiểm tra mỗi 1 s, tối đa 10 phút (trình duyệt tự nối lại). */
    private static void sse(Socket s, Music m) throws Exception {
        s.setSoTimeout(0);
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.flush();
        String last = null;
        long end = System.currentTimeMillis() + 600000;
        int idle = 0;
        while (System.currentTimeMillis() < end) {
            String now = m.status().toString();
            if (!now.equals(last)) {
                last = now;
                out.write(("data: " + now + "\n\n").getBytes("UTF-8"));
                out.flush();
                idle = 0;
            } else if (++idle >= 15) {
                out.write(": ping\n\n".getBytes("UTF-8")); // giữ kết nối, phát hiện trình duyệt đã đóng
                out.flush();
                idle = 0;
            }
            Thread.sleep(1000);
        }
    }

    /** "Máy này": trình duyệt tự phát, loa chuyển tiếp luồng YouTube (giữ Range để tua được). */
    private static void proxy(Socket s, String vid, String range) throws Exception {
        if (!YouTube.validId(vid)) {
            error(s, 400, "id không hợp lệ");
            return;
        }
        s.setSoTimeout(0);
        Response r = null;
        for (int i = 0; i < 2; i++) {
            YouTube.Audio a = YouTube.audio(vid, i > 0);
            Request.Builder b = new Request.Builder().url(a.url).header("User-Agent", a.userAgent);
            if (range != null) b.header("Range", range);
            r = HTTP.newCall(b.build()).execute();
            if (r.code() != 403 && r.code() != 410) break;
            r.close();
        }
        try {
            StringBuilder head = new StringBuilder("HTTP/1.1 " + r.code() + " OK\r\n")
                    .append("Content-Type: ").append(r.header("Content-Type", "audio/mp4")).append("\r\n")
                    .append("Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n");
            for (String h : new String[]{"Content-Length", "Content-Range"}) {
                if (r.header(h) != null) head.append(h).append(": ").append(r.header(h)).append("\r\n");
            }
            OutputStream out = s.getOutputStream();
            out.write(head.append("\r\n").toString().getBytes("UTF-8"));
            InputStream in = r.body().byteStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            r.close();
        }
    }

    private static String q(Map<String, String> query, String k) {
        String v = query.get(k);
        return v == null ? "" : v.trim();
    }

    private static void json(Socket s, JSONObject o) throws Exception {
        byte[] b = o.toString().getBytes("UTF-8");
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }

    private static void error(Socket s, int code, String msg) throws Exception {
        byte[] b = new JSONObject().put("error", msg).toString().getBytes("UTF-8");
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "
                + b.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
