package vn.r1.geminibot;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Google Cloud Speech-to-Text (API chính thức, key Cloud của người dùng): đổi câu nói thành chữ
 * (~0.5–1 s) để loa tự trả lời câu đơn giản (hỏi giờ…) và gửi chữ cho Gemini, nhanh hơn gửi audio.
 * Cần bật "Cloud Speech-to-Text API" cho project của key; miễn phí 60 phút / tháng.
 */
final class GoogleStt {
    private static final String URL_STT = "https://speech.googleapis.com/v1/speech:recognize";
    /** Key bị từ chối (401/403) thì thôi gọi đến khi đổi key, đỡ tốn thời gian mỗi câu. */
    private static volatile String rejectedKey;

    private GoogleStt() {}

    static String key(Config cfg) {
        return cfg.googleTtsKey.isEmpty() ? cfg.apiKey : cfg.googleTtsKey;
    }

    /** Có nên thử STT không (đã chọn Google và key chưa bị từ chối). */
    static boolean usable(Config cfg) {
        return "google".equals(cfg.sttEngine) && !key(cfg).equals(rejectedKey);
    }

    /** Trả về câu nghe được ("" nếu không có lời). wav = WAV 16 kHz mono 16-bit. */
    static String recognize(Config cfg, byte[] wav) throws Exception {
        byte[] pcm = new byte[Math.max(0, wav.length - 44)];
        System.arraycopy(wav, 44, pcm, 0, pcm.length);
        JSONObject body = new JSONObject()
                .put("config", new JSONObject()
                        .put("encoding", "LINEAR16")
                        .put("sampleRateHertz", VoiceRecorder.RATE)
                        .put("languageCode", "vi-VN")
                        .put("enableAutomaticPunctuation", true))
                .put("audio", new JSONObject().put("content", Base64.encodeToString(pcm, Base64.NO_WRAP)));

        String key = key(cfg);
        HttpURLConnection c = (HttpURLConnection) new URL(URL_STT).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", key);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = LocalAdb.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code == 401 || code == 403) {
                rejectedKey = key;
                throw new Exception("Google STT từ chối key (HTTP " + code + "), dùng Gemini nghe trực tiếp. "
                        + "Cần key Google Cloud (AIza…) đã bật Cloud Speech-to-Text API: " + resp);
            }
            if (code >= 400) throw new Exception("Google STT HTTP " + code + ": " + resp);
            JSONArray results = new JSONObject(resp).optJSONArray("results");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONArray alts = results.getJSONObject(i).optJSONArray("alternatives");
                if (alts != null && alts.length() > 0) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(alts.getJSONObject(0).optString("transcript", "").trim());
                }
            }
            return sb.toString().trim();
        } finally {
            c.disconnect();
        }
    }
}
