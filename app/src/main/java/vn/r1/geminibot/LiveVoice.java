package vn.r1.geminibot;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Câu chào lúc gọi từ đánh thức và câu tạm biệt của Gemini Live, đọc bằng CHÍNH model Live (cùng giọng
 * lúc trò chuyện) - như live_mode.py của Voicebot trên Pi. Câu đáp cũ đọc bằng bộ đọc thường (gTTS / Cloud
 * TTS) nên "Dạ?" nghe khác hẳn giọng Live ngay sau đó. Tạo 1 lần ở nền, lưu file theo mã băm câu + giọng +
 * model (đổi giọng / model thì tự tạo lại), lần sau phát ngay từ file: không trễ, không tốn lượt gọi API.
 */
final class LiveVoice {
    private static final String URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    static final int RATE = 24000;

    // Như CAU_CHAO_DS / CAU_TAM_BIET_* của Voicebot: nhiều câu, chọn ngẫu nhiên cho đỡ nhàm
    static final String[] CHAO = {"Dạ, em nghe nè!", "Dạ, có em đây!", "Em đây, cần gì nói em nghe nè.", "Dạ, em nghe đây ạ!"};
    private static final String[] TAM_BIET = {"Dạ vâng, có gì cứ gọi em liền nha!", "Dạ, vậy em nghỉ nha, cần gì cứ gọi em!",
            "Vâng ạ, khi nào cần là em có mặt liền!"};
    private static final String[] TAM_BIET_SANG = {"Dạ vâng, chúc cả nhà một ngày thật vui nha!"};          // 5h-10h
    private static final String[] TAM_BIET_KHUYA = {"Dạ, chúc ngủ ngon nha, cần gì cứ gọi em!",             // 21h-4h
            "Vâng ạ, khuya rồi nghỉ ngơi sớm nha!"};

    private static final Random RANDOM = new Random();
    private static volatile boolean dangTao;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();

    private LiveVoice() {
    }

    /** Câu chào ngẫu nhiên đã tạo sẵn; null = chưa có (đang tạo / lỗi) -> người gọi dùng câu đáp cũ. */
    static byte[] chao(Context ctx, Config cfg) {
        return ngauNhien(ctx, cfg, CHAO);
    }

    /** Câu tạm biệt ngẫu nhiên theo buổi (sáng / khuya có câu riêng). */
    static byte[] tamBiet(Context ctx, Config cfg) {
        int gio = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        List<String> ds = new ArrayList<>();
        if (gio >= 21 || gio < 5) {
            for (String c : TAM_BIET_KHUYA) ds.add(c);
            ds.add(TAM_BIET[0]);
        } else {
            for (String c : TAM_BIET) ds.add(c);
            if (gio >= 5 && gio < 10) for (String c : TAM_BIET_SANG) ds.add(c);
        }
        return ngauNhien(ctx, cfg, ds.toArray(new String[0]));
    }

    private static byte[] ngauNhien(Context ctx, Config cfg, String[] ds) {
        List<File> co = new ArrayList<>();
        for (String c : ds) {
            File f = file(ctx, cfg, c);
            if (f.isFile() && f.length() > 0) co.add(f);
        }
        if (co.isEmpty()) {
            taoSan(ctx, cfg); // thiếu thì tạo nền cho lần sau
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(co.get(RANDOM.nextInt(co.size())));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray(); // PCM nhị phân: không đọc qua chuỗi (LocalAdb.readAll = UTF-8, hỏng dữ liệu)
        } catch (Exception e) {
            return null;
        }
    }

    private static File file(Context ctx, Config cfg, String cau) {
        File dir = new File(ctx.getFilesDir(), "live_cau");
        dir.mkdirs();
        return new File(dir, ma(cau + "|" + cfg.voice + "|" + cfg.liveModel) + ".pcm");
    }

    private static String ma(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format(java.util.Locale.US, "%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** Tạo nền mọi câu CHƯA có file cho giọng / model hiện tại (lúc khởi động, khi đổi giọng). Đang tạo thì bỏ qua. */
    static void taoSan(final Context ctx, final Config cfg) {
        if (!cfg.live || cfg.apiKey.isEmpty() || dangTao) return;
        dangTao = true;
        new Thread(() -> {
            try {
                List<String> tatCa = new ArrayList<>();
                for (String[] ds : new String[][]{CHAO, TAM_BIET, TAM_BIET_SANG, TAM_BIET_KHUYA}) {
                    for (String c : ds) tatCa.add(c);
                }
                for (String c : tatCa) {
                    File f = file(ctx, cfg, c);
                    if (f.isFile() && f.length() > 0) continue;
                    try {
                        byte[] pcm = docQuaLive(cfg, c);
                        File tmp = new File(f.getPath() + ".tmp");
                        FileOutputStream os = new FileOutputStream(tmp);
                        os.write(pcm);
                        os.close();
                        if (!tmp.renameTo(f)) throw new Exception("không lưu được file");
                        BotLog.i("Gemini Live: đã tạo câu \"" + c + "\" bằng giọng " + cfg.voice);
                    } catch (Exception e) {
                        BotLog.w("Gemini Live: không tạo được câu \"" + c + "\": " + e.getMessage());
                        return; // lỗi mạng / key: thử lại lần gọi sau, đừng dội API
                    }
                }
            } finally {
                dangTao = false;
            }
        }, "live-cau").start();
    }

    /** Cho model Live đọc nguyên văn 1 câu, trả PCM 16 bit 24 kHz (như _doc_qua_live_async của Voicebot). */
    private static byte[] docQuaLive(Config cfg, final String cau) throws Exception {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final CountDownLatch xong = new CountDownLatch(1);
        final String[] loi = {null};
        final String setup = new JSONObject().put("setup", new JSONObject()
                .put("model", "models/" + cfg.liveModel)
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", cfg.voice)))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject()
                        .put("text", "Chỉ đọc lại ĐÚNG NGUYÊN VĂN câu tiếng Việt được gửi tới, giọng thân thiện tự "
                                + "nhiên như đang trò chuyện, không thêm hay bớt bất kỳ chữ nào."))))).toString();
        final String hoi = new JSONObject().put("clientContent", new JSONObject()
                .put("turns", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", cau)))))
                .put("turnComplete", true)).toString();
        Request req = new Request.Builder().url(URL).header("x-goog-api-key", cfg.apiKey).build();
        WebSocket ws = HTTP.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket w, Response r) {
                w.send(setup);
            }

            @Override
            public void onMessage(WebSocket w, String text) {
                nhan(w, text);
            }

            @Override
            public void onMessage(WebSocket w, ByteString bytes) {
                nhan(w, bytes.utf8());
            }

            private void nhan(WebSocket w, String text) {
                try {
                    JSONObject m = new JSONObject(text);
                    if (m.has("setupComplete")) {
                        w.send(hoi);
                        return;
                    }
                    JSONObject sc = m.optJSONObject("serverContent");
                    if (sc == null) return;
                    JSONObject turn = sc.optJSONObject("modelTurn");
                    JSONArray parts = turn != null ? turn.optJSONArray("parts") : null;
                    for (int i = 0; parts != null && i < parts.length(); i++) {
                        JSONObject data = parts.getJSONObject(i).optJSONObject("inlineData");
                        if (data != null) pcm.write(Base64.decode(data.getString("data"), Base64.DEFAULT));
                    }
                    if (sc.optBoolean("turnComplete")) xong.countDown();
                } catch (Exception e) {
                    loi[0] = e.getMessage();
                    xong.countDown();
                }
            }

            @Override
            public void onFailure(WebSocket w, Throwable t, Response r) {
                loi[0] = t.getMessage() + (r != null ? " (HTTP " + r.code() + ")" : "");
                xong.countDown();
            }

            @Override
            public void onClosing(WebSocket w, int code, String reason) {
                if (code != 1000) loi[0] = code + " " + reason;
                xong.countDown();
            }
        });
        try {
            if (!xong.await(20, TimeUnit.SECONDS)) throw new Exception("Live không trả lời trong 20 s");
        } finally {
            ws.close(1000, "xong");
        }
        if (pcm.size() == 0) throw new Exception(loi[0] != null ? loi[0] : "Live không trả audio");
        return pcm.toByteArray();
    }
}
