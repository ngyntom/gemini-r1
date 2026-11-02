package vn.r1.geminibot;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Web UI điều khiển bot trong mạng LAN: http://<IP của R1>:8088
 *
 * API (JSON):
 *   GET  /api/status            trạng thái, cấu hình, log
 *   GET  /api/mic.wav           5 giây micro vừa thu (để kiểm tra mic)
 *   POST /api/config            {key: value, ...} lưu cấu hình
 *   POST /api/say    {text}     đọc một câu
 *   POST /api/ask    {text}     hỏi Gemini bằng chữ, đọc câu trả lời
 *   POST /api/listen            nghe ngay, không cần từ đánh thức
 *   POST /api/pause  {paused}   tạm dừng / tiếp tục nghe
 *   POST /api/reset             xoá trí nhớ hội thoại
 *   POST /api/volume {level}    âm lượng 0..max
 *   POST /api/upload?name=x.tflite   (body = file) tải model microWakeWord lên loa
 *   POST /api/install           (body = file APK) cập nhật app
 *   POST /api/led    {state}    thử đèn LED
 *   POST /api/ha_test           kiểm tra kết nối Home Assistant
 *   GET  /api/ha/devices[?all=1]  thiết bị đã lưu (all=1: mọi entity để chọn)
 *   POST /api/ha/selection {devices: {entity_id: tên gọi khác}}  lưu thiết bị cho bot
 *   POST /api/ha/service {entity_id, service}   bật / tắt thiết bị
 */
class WebServer {
    static final int PORT = 8088;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_APK = 120 * 1024 * 1024;

    private final BotService bot;
    private ServerSocket server;
    private volatile boolean running;

    WebServer(BotService bot) {
        this.bot = bot;
    }

    void start() {
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                BotLog.i("Web UI: cổng " + PORT);
                while (running) {
                    final Socket s = server.accept();
                    new Thread(() -> handle(s), "web-client").start();
                }
            } catch (IOException e) {
                if (running) BotLog.e("Web server dừng", e);
            }
        }, "web-server").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
            int len = 0;
            if (headers.containsKey("content-length")) len = Integer.parseInt(headers.get("content-length"));
            if (method.equals("POST") && target.equals("/api/install")) {
                installApk(s, in, len);
                return;
            }
            if (len > MAX_BODY) {
                send(s, 413, "text/plain", "Too large".getBytes("UTF-8"));
                return;
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }

            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) query.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            if (MusicApi.handle(bot, s, method, path, query, headers, body)) return;
            route(s, method, path, query, body);
        } catch (Exception e) {
            try {
                sendJson(s, 500, new JSONObject().put("error", String.valueOf(e.getMessage())));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void route(Socket s, String method, String path, Map<String, String> query, byte[] body)
            throws Exception {
        if (method.equals("GET") && (path.equals("/") || path.equals("/index.html"))) {
            send(s, 200, "text/html; charset=utf-8", readAsset("web/index.html"));
            return;
        }
        // Trang Nhạc (chép từ Voicebot trên Pi) và CSS của nó: assets/web/static/...
        if (method.equals("GET") && path.startsWith("/static/") && !path.contains("..")) {
            String type = path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8" : "text/html; charset=utf-8";
            try {
                send(s, 200, type, readAsset("web" + path));
            } catch (IOException e) {
                send(s, 404, "text/plain", "Not found".getBytes("UTF-8"));
            }
            return;
        }
        if (method.equals("GET") && path.equals("/api/status")) {
            sendJson(s, 200, status());
            return;
        }
        if (method.equals("GET") && path.equals("/api/mic.wav")) {
            send(s, 200, "audio/wav", bot.recentMicWav());
            return;
        }
        if (method.equals("GET") && path.equals("/api/ha/devices")) {
            Config c = Config.load(bot);
            if (!c.haEnabled()) {
                sendJson(s, 400, new JSONObject().put("error", "Chưa cấu hình Home Assistant"));
                return;
            }
            try {
                sendJson(s, 200, new JSONObject()
                        .put("devices", HomeAssistant.devices(c, "1".equals(query.get("all")))));
            } catch (Exception e) {
                sendJson(s, 502, new JSONObject().put("error", String.valueOf(e.getMessage())));
            }
            return;
        }
        // Nhóm loa: loa khác hỏi tên / phiên bản (lúc tìm loa), và tìm các loa R1 khác trong mạng
        if (method.equals("GET") && path.equals("/api/group/info")) {
            sendJson(s, 200, new JSONObject().put("ten", Group.deviceName())
                    .put("version", bot.getPackageManager().getPackageInfo(bot.getPackageName(), 0).versionName));
            return;
        }
        if (method.equals("GET") && path.equals("/api/group/scan")) {
            sendJson(s, 200, new JSONObject().put("loa", Group.scan()));
            return;
        }
        if (method.equals("GET") && path.equals("/api/wifi/scan")) {
            sendJson(s, 200, new JSONObject().put("networks", Wifi.scan(bot)));
            return;
        }
        if (method.equals("GET") && path.equals("/favicon.ico")) {
            send(s, 204, "image/x-icon", new byte[0]); // trình duyệt tự xin, trước đây trả 404
            return;
        }
        if (!method.equals("POST")) {
            send(s, 404, "text/plain", "Not found".getBytes("UTF-8"));
            return;
        }

        if (path.equals("/api/upload")) {
            String name = new File(query.containsKey("name") ? query.get("name") : "custom.tflite").getName();
            if (!name.endsWith(".tflite") && !name.endsWith(".json")) {
                sendJson(s, 400, new JSONObject().put("error", "Chỉ nhận file .tflite hoặc .json"));
                return;
            }
            File dir = new File(bot.getFilesDir(), "mww");
            dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(body);
            fos.close();
            BotLog.i("Đã tải lên " + f.getPath() + " (" + body.length + " byte)");
            sendJson(s, 200, new JSONObject().put("ok", true).put("path", f.getPath()));
            return;
        }

        JSONObject req = body.length == 0 ? new JSONObject() : new JSONObject(new String(body, "UTF-8"));
        switch (path) {
            case "/api/config":
                Config.saveFromJson(bot, req);
                BotLog.i("Đã lưu cấu hình từ Web UI");
                bot.configChanged();
                break;
            case "/api/say":
            case "/api/ask":
                if (Config.load(bot).apiKey.isEmpty()) {
                    sendJson(s, 400, new JSONObject().put("error", "Chưa có API key"));
                    return;
                }
                if (path.equals("/api/ask")) bot.askTextAsync(req.optString("text", ""));
                else bot.sayAsync(req.optString("text", ""));
                break;
            case "/api/ha_test": {
                Config c = Config.load(bot);
                if (!c.haEnabled()) {
                    sendJson(s, 400, new JSONObject().put("error", "Chưa đặt địa chỉ hoặc token Home Assistant"));
                    return;
                }
                try {
                    String msg = HomeAssistant.test(c);
                    BotLog.i("Home Assistant: " + msg);
                    sendJson(s, 200, new JSONObject().put("ok", true).put("message", msg));
                } catch (Exception e) {
                    sendJson(s, 502, new JSONObject().put("error", String.valueOf(e.getMessage())));
                }
                return;
            }
            case "/api/ha/selection": { // {devices: {entity_id: tên gọi khác}}: thiết bị đã lưu cho bot
                JSONObject sel = req.optJSONObject("devices");
                Config.saveHaDevices(bot, sel != null ? sel : new JSONObject());
                BotLog.i("Đã lưu " + (sel != null ? sel.length() : 0) + " thiết bị Home Assistant cho bot");
                break;
            }
            case "/api/ha/service": { // {entity_id, service}: bật / tắt từ tab Thiết bị
                Config c = Config.load(bot);
                try {
                    HomeAssistant.uiService(c, req.optString("entity_id"), req.optString("service", "toggle"));
                } catch (Exception e) {
                    sendJson(s, 502, new JSONObject().put("error", String.valueOf(e.getMessage())));
                    return;
                }
                break;
            }
            case "/api/led": // thử đèn: {state: listening|thinking|speaking|error|paused|idle}, rỗng = theo bot
                Led.test = req.optString("state", "").isEmpty() ? null : req.optString("state");
                break;
            case "/api/wifi/connect": { // {ssid, password, secure}: đổi mạng, không vào được thì tự quay lại mạng cũ
                String ssid = req.optString("ssid", "").trim();
                if (ssid.isEmpty()) {
                    sendJson(s, 400, new JSONObject().put("error", "Thiếu tên mạng"));
                    return;
                }
                Wifi.connectAsync(bot, ssid, req.optString("password", ""), req.optString("secure", "WPA"));
                break;
            }
            case "/api/airplay": // {on}: bật = cài bản sửa + khởi động lại loa, tắt = về bản gốc
                Extras.setAirplay(bot, req.optBoolean("on"));
                break;
            case "/api/bluetooth": // {on}
                Extras.setBluetooth(req.optBoolean("on"));
                break;
            case "/api/listen":
                bot.triggerListen();
                break;
            case "/api/pause":
                bot.setPaused(req.optBoolean("paused", true));
                break;
            case "/api/reset":
                bot.resetHistory();
                break;
            case "/api/volume": {
                AudioManager am = (AudioManager) bot.getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int level = Math.max(0, Math.min(max, req.optInt("level", max / 2)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
                break;
            }
            default:
                send(s, 404, "text/plain", "Not found".getBytes("UTF-8"));
                return;
        }
        sendJson(s, 200, new JSONObject().put("ok", true));
    }

    /** Nhận APK (body = file, ghi thẳng ra đĩa) rồi tự cài. */
    private void installApk(Socket s, InputStream in, int len) throws Exception {
        if (len <= 0 || len > MAX_APK) {
            sendJson(s, 413, new JSONObject().put("error", "File APK rỗng hoặc quá lớn"));
            return;
        }
        File f = Updater.apkFile(bot);
        FileOutputStream fos = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        int left = len;
        while (left > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, left));
            if (n < 0) break;
            fos.write(buf, 0, n);
            left -= n;
        }
        fos.close();
        if (left > 0) {
            sendJson(s, 400, new JSONObject().put("error", "Tải lên bị ngắt giữa chừng"));
            return;
        }
        BotLog.i("Đã nhận APK " + len / 1024 + " KB");
        try {
            sendJson(s, 200, new JSONObject().put("ok", true).put("message", Updater.install(bot, f)));
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            sendJson(s, 500, new JSONObject().put("error",
                    "Không nối được adb của loa. Máy tính đang kết nối adb? Chạy 'adb disconnect' rồi thử lại."));
        }
    }

    private JSONObject status() throws Exception {
        Updater.reportLastInstall(bot); // cài lỗi thì app không bị tắt -> báo kết quả ngay
        AudioManager am = (AudioManager) bot.getSystemService(Context.AUDIO_SERVICE);
        JSONArray uploaded = new JSONArray();
        File[] files = new File(bot.getFilesDir(), "mww").listFiles();
        if (files != null) {
            for (File f : files) if (f.getName().endsWith(".tflite")) uploaded.put(f.getPath());
        }
        return new JSONObject()
                .put("state", BotLog.state)
                .put("version", bot.getPackageManager().getPackageInfo(bot.getPackageName(), 0).versionName)
                .put("paused", bot.isPaused())
                .put("led_test", Led.test == null ? "" : Led.test)
                .put("airplay", Extras.airplayOn(bot))
                .put("bluetooth", Extras.bluetoothOn())
                .put("extras_busy", Extras.busy)
                .put("wifi_switch", Wifi.switchState)
                .put("wifi_switch_msg", Wifi.switchMessage)
                .put("wake_name", BotLog.wakeName)
                .put("mic_level", BotLog.micLevel)
                .put("wake_score", BotLog.wakeScore)
                .put("sys", SysInfo.read(bot))
                .put("last_heard", BotLog.lastHeard)
                .put("last_reply", BotLog.lastReply)
                .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
                .put("volume_max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                .put("config", Config.toJson(bot))
                .put("builtin_models", new JSONArray(bot.getAssets().list("mww")))
                .put("uploaded_models", uploaded)
                .put("log", BotLog.tail());
    }

    private byte[] readAsset(String name) throws IOException {
        InputStream is = bot.getAssets().open(name);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("Header quá dài");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static void sendJson(Socket s, int code, JSONObject o) throws IOException {
        send(s, code, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"));
    }

    private static void send(Socket s, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = s.getOutputStream();
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
