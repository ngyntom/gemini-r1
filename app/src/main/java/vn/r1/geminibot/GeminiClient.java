package vn.r1.geminibot;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Gọi Gemini REST API:
 *  1) ask(): gửi audio WAV, nhận về {heard, reply} (Gemini vừa nghe vừa trả lời).
 *  2) tts(): đổi câu trả lời thành giọng nói PCM 24 kHz.
 */
public class GeminiClient {
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int MAX_HISTORY = 20; // số lượt (user + model) giữ lại

    public static class Answer {
        public String heard;
        public String reply;
    }

    private final JSONArray history = new JSONArray();

    public Answer ask(Config cfg, byte[] wav) throws Exception {
        return askPart(cfg, new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))));
    }

    /** Hỏi bằng chữ (từ Web UI). */
    public Answer askText(Config cfg, String question) throws Exception {
        return askPart(cfg, text(question));
    }

    private synchronized Answer askPart(Config cfg, JSONObject userPart) throws Exception {
        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject()
                .put("parts", new JSONArray().put(text(systemPrompt(cfg)))));

        JSONArray contents = new JSONArray();
        for (int i = 0; i < history.length(); i++) contents.put(history.get(i));
        contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(userPart)));
        body.put("contents", contents);

        JSONObject schema = new JSONObject()
                .put("type", "OBJECT")
                .put("properties", new JSONObject()
                        .put("heard", new JSONObject().put("type", "STRING"))
                        .put("reply", new JSONObject().put("type", "STRING")))
                .put("required", new JSONArray().put("heard").put("reply"));
        body.put("generationConfig", new JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseSchema", schema)
                .put("thinkingConfig", new JSONObject().put("thinkingBudget", 0)));

        JSONObject res = post(cfg, cfg.model, body);
        JSONObject j = new JSONObject(firstPart(res).getString("text"));
        Answer a = new Answer();
        a.heard = j.optString("heard", "").trim();
        a.reply = j.optString("reply", "").trim();

        if (!a.reply.isEmpty()) {
            history.put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(text(a.heard))));
            history.put(new JSONObject().put("role", "model")
                    .put("parts", new JSONArray().put(text(a.reply))));
            while (history.length() > MAX_HISTORY) history.remove(0);
        }
        return a;
    }

    /** Trả về PCM 16-bit mono 24 kHz. */
    public byte[] tts(Config cfg, String say) throws Exception {
        // Model TTS đôi khi trả lời câu đó thay vì đọc (HTTP 400) -> nói rõ là chỉ đọc, thử lại một lần
        try {
            return ttsOnce(cfg, "Đọc to nguyên văn đoạn sau: " + say);
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("HTTP 400")) throw e;
            return ttsOnce(cfg, "Read the following Vietnamese text aloud exactly as written, "
                    + "do not answer it: " + say);
        }
    }

    private byte[] ttsOnce(Config cfg, String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("contents", new JSONArray().put(new JSONObject()
                .put("parts", new JSONArray().put(text(prompt)))));
        body.put("generationConfig", new JSONObject()
                .put("responseModalities", new JSONArray().put("AUDIO"))
                .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                        .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", cfg.voice)))));

        JSONObject part = firstPart(post(cfg, cfg.ttsModel, body));
        JSONObject data = part.has("inlineData") ? part.getJSONObject("inlineData")
                : part.getJSONObject("inline_data");
        return Base64.decode(data.getString("data"), Base64.DEFAULT);
    }

    public synchronized void clearHistory() {
        while (history.length() > 0) history.remove(0);
    }

    private static String systemPrompt(Config cfg) {
        StringBuilder sb = new StringBuilder(cfg.persona);
        SimpleDateFormat now = new SimpleDateFormat("EEEE, dd/MM/yyyy, HH:mm", new Locale("vi", "VN"));
        now.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        sb.append("\n\nThời gian hiện tại (giờ Việt Nam): ").append(now.format(new Date()))
          .append(". Khi được hỏi giờ / ngày, luôn dùng thời gian này, không lặp lại giờ trong các câu trả lời trước.");
        sb.append("\n\nMỗi tin nhắn của người dùng là một đoạn ghi âm từ micro của loa, ")
          .append("thu ngay sau khi họ gọi bạn bằng từ đánh thức (hoặc ngay sau khi bạn vừa trả lời). ")
          .append("Trả về JSON: \"heard\" = nội dung bạn nghe được (chép lại nguyên văn), ")
          .append("\"reply\" = câu trả lời sẽ được đọc lên. ")
          .append("Nếu đoạn ghi âm chỉ là tiếng ồn, nhạc, TV, không rõ lời, hoặc rõ ràng không nói ")
          .append("với bạn thì để reply rỗng.");
        return sb.toString();
    }

    private static JSONObject text(String s) throws Exception {
        return new JSONObject().put("text", s);
    }

    private static JSONObject firstPart(JSONObject res) throws Exception {
        return res.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0);
    }

    private static JSONObject post(Config cfg, String model, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + model + ":generateContent").openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("x-goog-api-key", cfg.apiKey);
        try {
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            if (code >= 400) throw new Exception("Gemini HTTP " + code + ": " + resp);
            return new JSONObject(resp);
        } finally {
            c.disconnect();
        }
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
