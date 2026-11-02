package vn.r1.geminibot;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Google Cloud Text-to-Speech: nhanh hơn Gemini TTS nhiều (thường < 1 s), chỉnh được tốc độ.
 * Cần bật "Cloud Text-to-Speech API" trong Google Cloud cho project của key.
 */
final class GoogleTts {
    private static final String URL_TTS = "https://texttospeech.googleapis.com/v1/text:synthesize";

    private GoogleTts() {}

    /** Trả về PCM 16-bit mono ở tần số rate. */
    static byte[] synth(Config cfg, String text, int rate) throws Exception {
        String voice = cfg.googleVoice;
        String lang = voice.length() >= 5 ? voice.substring(0, 5) : "vi-VN";
        JSONObject body = new JSONObject()
                .put("input", new JSONObject().put("text", text))
                .put("voice", new JSONObject().put("languageCode", lang).put("name", voice))
                .put("audioConfig", new JSONObject()
                        .put("audioEncoding", "LINEAR16")
                        .put("sampleRateHertz", rate)
                        .put("speakingRate", cfg.ttsRate));

        HttpURLConnection c = (HttpURLConnection) new URL(URL_TTS).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        GoogleStt.authorize(cfg, c, cfg.googleTtsKey.isEmpty() ? cfg.apiKey : cfg.googleTtsKey);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) throw new Exception("Google TTS HTTP " + code + ": " + resp);
            byte[] wav = Base64.decode(new JSONObject(resp).getString("audioContent"), Base64.DEFAULT);
            return pcmFromWav(wav);
        } finally {
            c.disconnect();
        }
    }

    /** LINEAR16 trả về file WAV; lấy phần dữ liệu của chunk "data". */
    private static byte[] pcmFromWav(byte[] wav) {
        if (wav.length < 12 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') return wav;
        int p = 12;
        while (p + 8 <= wav.length) {
            int size = (wav[p + 4] & 0xff) | (wav[p + 5] & 0xff) << 8 | (wav[p + 6] & 0xff) << 16
                    | (wav[p + 7] & 0xff) << 24;
            if (wav[p] == 'd' && wav[p + 1] == 'a' && wav[p + 2] == 't' && wav[p + 3] == 'a') {
                return Arrays.copyOfRange(wav, p + 8, Math.min(wav.length, p + 8 + size));
            }
            p += 8 + size + (size & 1);
        }
        return Arrays.copyOfRange(wav, 44, wav.length);
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }
}
