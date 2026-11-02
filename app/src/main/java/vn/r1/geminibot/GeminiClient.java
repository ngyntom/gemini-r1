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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
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
        String devices = null;
        Set<String> deviceIds = new HashSet<>();
        if (cfg.haEnabled()) {
            try {
                devices = HomeAssistant.entities(cfg, deviceIds);
            } catch (Exception e) {
                BotLog.w("Không lấy được thiết bị Home Assistant: " + e.getMessage());
            }
        }

        JSONObject body = new JSONObject();
        body.put("system_instruction", new JSONObject()
                .put("parts", new JSONArray().put(text(systemPrompt(cfg, devices)))));

        JSONArray contents = new JSONArray();
        for (int i = 0; i < history.length(); i++) contents.put(history.get(i));
        contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(userPart)));
        body.put("contents", contents);

        JSONObject answerSchema = new JSONObject()
                .put("type", "OBJECT")
                .put("properties", new JSONObject()
                        .put("heard", new JSONObject().put("type", "STRING"))
                        .put("reply", new JSONObject().put("type", "STRING")))
                .put("required", new JSONArray().put("heard").put("reply"));
        JSONObject gen = new JSONObject().put("thinkingConfig", thinkingConfig(cfg.model));
        body.put("generationConfig", gen);

        int base = contents.length();
        Answer a;
        try {
            a = devices == null
                    ? askJson(cfg, body, gen, answerSchema)
                    : askWithTools(cfg, body, contents, answerSchema, deviceIds);
        } catch (Exception e) {
            // Model không nhận kiểu tắt suy nghĩ này (vd. bản Pro bắt buộc suy nghĩ) -> gửi lại không kèm
            String m = String.valueOf(e.getMessage());
            if (!m.contains("HTTP 400") || !m.toLowerCase(Locale.ROOT).contains("think")) throw e;
            BotLog.w("Model " + cfg.model + " không nhận thinkingConfig, gửi lại không kèm");
            gen.remove("thinkingConfig");
            while (contents.length() > base) contents.remove(contents.length() - 1);
            a = devices == null
                    ? askJson(cfg, body, gen, answerSchema)
                    : askWithTools(cfg, body, contents, answerSchema, deviceIds);
        }

        if (!a.reply.isEmpty()) {
            history.put(new JSONObject().put("role", "user")
                    .put("parts", new JSONArray().put(text(a.heard.isEmpty() ? "(không rõ lời)" : a.heard))));
            history.put(new JSONObject().put("role", "model")
                    .put("parts", new JSONArray().put(text(a.reply))));
            while (history.length() > MAX_HISTORY) history.remove(0);
        }
        return a;
    }

    /**
     * Tắt / giảm suy nghĩ cho nhanh. Gemini 2.x: thinkingBudget 0. Gemini 3 trở lên báo lỗi 400 với
     * thinkingBudget 0 (thử: gemini-3.5-flash-lite) và dùng thinkingLevel "minimal" (~1.2–1.3 s).
     */
    private static JSONObject thinkingConfig(String model) throws Exception {
        return model.startsWith("gemini-2")
                ? new JSONObject().put("thinkingBudget", 0)
                : new JSONObject().put("thinkingLevel", "minimal");
    }

    /** Không có Home Assistant: trả lời thẳng bằng JSON {heard, reply}. */
    private Answer askJson(Config cfg, JSONObject body, JSONObject gen, JSONObject schema) throws Exception {
        gen.put("responseMimeType", "application/json").put("responseSchema", schema);
        JSONObject j = new JSONObject(firstPart(post(cfg, cfg.model, body)).getString("text"));
        return answer(j);
    }

    /**
     * Có Home Assistant: Gemini phải gọi hàm. ha_call -> loa gọi HA rồi gửi kết quả lại;
     * respond -> câu trả lời cuối. (Gemini không cho dùng JSON mode cùng lúc với tools.)
     */
    private Answer askWithTools(Config cfg, JSONObject body, JSONArray contents, JSONObject answerSchema,
                                Set<String> deviceIds) throws Exception {
        JSONObject haSchema = new JSONObject()
                .put("type", "OBJECT")
                .put("properties", new JSONObject()
                        .put("domain", new JSONObject().put("type", "STRING")
                                .put("description", "vd. light, switch, fan, cover, climate, media_player, scene, script"))
                        .put("service", new JSONObject().put("type", "STRING")
                                .put("description", "vd. turn_on, turn_off, toggle, open_cover, close_cover, set_temperature"))
                        .put("entity_id", new JSONObject().put("type", "STRING")
                                .put("description", "entity_id trong danh sách; nhiều thiết bị thì cách nhau bằng dấu phẩy"))
                        .put("data_json", new JSONObject().put("type", "STRING")
                                .put("description", "tham số thêm dạng JSON, vd. {\"brightness_pct\":50} hoặc {\"temperature\":26}; không có thì để {}"))
                        .put("user_words", new JSONObject().put("type", "STRING")
                                .put("description", USER_WORDS_DESC)))
                .put("required", new JSONArray().put("domain").put("service").put("entity_id").put("user_words"));
        body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray()
                .put(new JSONObject().put("name", "ha_call")
                        .put("description", "Điều khiển một thiết bị trong nhà qua Home Assistant")
                        .put("parameters", haSchema))
                .put(new JSONObject().put("name", "respond")
                        .put("description", "Câu trả lời cuối cùng cho người dùng (luôn gọi sau cùng)")
                        .put("parameters", answerSchema)))));
        body.put("toolConfig", new JSONObject().put("functionCallingConfig", new JSONObject().put("mode", "ANY")));

        boolean called = false;
        boolean nagged = false;
        for (int round = 0; round < 4; round++) {
            JSONObject content = post(cfg, cfg.model, body).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content");
            JSONArray parts = content.getJSONArray("parts");
            JSONArray responses = new JSONArray();
            JSONObject respond = null;
            boolean allOk = true;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject fc = parts.getJSONObject(i).optJSONObject("functionCall");
                if (fc == null) continue;
                JSONObject args = fc.optJSONObject("args");
                if (args == null) args = new JSONObject();
                if ("respond".equals(fc.optString("name"))) {
                    respond = args;
                    continue;
                }
                called = true;
                JSONObject result;
                try {
                    String dataJson = args.optString("data_json", "").trim();
                    result = HomeAssistant.call(cfg, deviceIds, args.optString("domain"), args.optString("service"),
                            args.optString("entity_id"), dataJson.isEmpty() ? null : new JSONObject(dataJson),
                            args.optString("user_words"));
                } catch (Exception e) {
                    result = new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage()));
                }
                if (!result.optBoolean("ok")) {
                    allOk = false;
                    BotLog.w("Home Assistant lỗi: " + result.optString("error"));
                }
                responses.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", fc.optString("name")).put("response", result)));
            }
            // Gemini hay trả lời "Đã tắt đèn…" mà quên gọi ha_call -> bắt gọi thật (một lần)
            if (respond != null && !called && !nagged && claimsAction(respond.optString("reply"))) {
                nagged = true;
                BotLog.w("Gemini báo đã điều khiển nhưng chưa gọi Home Assistant, yêu cầu gọi lại");
                contents.put(content);
                contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                        .put(new JSONObject().put("functionResponse", new JSONObject()
                                .put("name", "respond")
                                .put("response", new JSONObject().put("ok", false).put("error",
                                        "Chưa gọi ha_call nên thiết bị CHƯA được điều khiển. Hãy gọi ha_call với "
                                                + "entity_id đúng rồi mới respond; không tìm thấy thiết bị thì nói rõ."))))));
                continue;
            }
            // Đã có câu trả lời và mọi lệnh đều chạy được -> xong. Lệnh lỗi thì để Gemini nói lại cho đúng.
            if (respond != null && allOk) return answer(respond);
            if (responses.length() == 0) {
                if (respond != null) return answer(respond);
                String t = parts.getJSONObject(0).optString("text", "");
                Answer a = new Answer();
                a.heard = "";
                a.reply = t.trim();
                return a;
            }
            // Gemini cần đúng một functionResponse cho mỗi functionCall, kể cả respond bị bỏ qua
            if (respond != null) {
                responses.put(new JSONObject().put("functionResponse", new JSONObject()
                        .put("name", "respond")
                        .put("response", new JSONObject().put("ok", false)
                                .put("error", "Có lệnh Home Assistant bị lỗi; trả lời lại theo kết quả thật."))));
            }
            contents.put(content);
            contents.put(new JSONObject().put("role", "user").put("parts", responses));
        }
        throw new Exception("Gemini gọi Home Assistant quá nhiều lần");
    }

    /** Câu trả lời có vẻ báo đã điều khiển thiết bị ("Đã bật…", "Đã tắt…", "Đã mở…"). */
    private static boolean claimsAction(String reply) {
        String r = reply.toLowerCase(new Locale("vi", "VN"));
        return r.matches("(?s)(.*[\\s,.!])?đã\\s+(bật|tắt|mở|đóng|chỉnh|đặt|chạy|kích hoạt|khoá|khóa|dừng|tăng|giảm).*");
    }

    private static Answer answer(JSONObject j) {
        Answer a = new Answer();
        a.heard = j.optString("heard", "").trim();
        a.reply = j.optString("reply", "").trim();
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

    /**
     * Luôn thêm vào sau persona: với danh sách thiết bị HA dài, model nhỏ (3.5 Flash Lite) tưởng mình
     * chỉ điều khiển nhà và từ chối "Kể chuyện cổ tích"; persona mặc định "ngắn gọn" cũng làm truyện cụt.
     */
    static final String CAPABILITIES = "Bạn là trợ lý đa năng: trò chuyện, trả lời mọi câu hỏi, kể chuyện "
            + "(cổ tích, truyện cười, truyện cho trẻ em), đọc thơ, giải thích, gợi ý. Câu hỏi thường thì trả lời "
            + "ngắn; khi được nhờ kể chuyện, đọc thơ hay giải thích chi tiết thì nói đầy đủ, mạch lạc (truyện "
            + "khoảng 200–350 từ), dùng câu văn tự nhiên để đọc thành tiếng.";

    static final String USER_WORDS_DESC = "nguyên văn cụm từ người dùng dùng để gọi riêng thiết bị này, "
            + "vd. \"đèn phòng ba\"; mỗi thiết bị khác loại thì gọi ha_call riêng";

    /** Quy tắc chọn thiết bị (dùng chung cho Gemini thường và Gemini Live). */
    static final String DEVICE_RULES = "Chỉ điều khiển đúng LOẠI thiết bị người dùng nói: \"đèn\" là light "
            + "(hoặc switch tên có chữ đèn), \"quạt\" là fan, \"máy lạnh\" / \"điều hoà\" là climate, \"rèm\" là cover, "
            + "\"ổ cắm\" là switch. Không có thiết bị khớp cả loại lẫn tên / phòng thì KHÔNG gọi ha_call và KHÔNG "
            + "chọn thiết bị khác loại (vd. nói \"đèn phòng ba\" mà chỉ có máy lạnh phòng ba thì không được tắt máy lạnh): "
            + "nói là không tìm thấy và kể vài thiết bị cùng loại gần nhất.";

    private static String systemPrompt(Config cfg, String devices) {
        StringBuilder sb = new StringBuilder(cfg.persona);
        sb.append("\n\n").append(CAPABILITIES);
        sb.append("\n\nThời gian hiện tại (giờ Việt Nam): ").append(LocalAnswer.nowForPrompt())
          .append(". Khi được hỏi giờ / ngày, luôn dùng thời gian này, không lặp lại giờ trong các câu trả lời trước.");
        sb.append("\n\nMỗi tin nhắn của người dùng là một đoạn ghi âm từ micro của loa, ")
          .append("thu ngay sau khi họ gọi bạn bằng từ đánh thức (hoặc ngay sau khi bạn vừa trả lời). ")
          .append("Trả về JSON: \"heard\" = nội dung bạn nghe được (chép lại nguyên văn), ")
          .append("\"reply\" = câu trả lời sẽ được đọc lên. ")
          .append("Nếu đoạn ghi âm chỉ là tiếng ồn, nhạc, TV, không rõ lời, hoặc rõ ràng không nói ")
          .append("với bạn thì để reply rỗng.");
        if (devices != null) {
            sb.append("\n\nNgoài việc trò chuyện ở trên, bạn còn điều khiển được thiết bị trong nhà qua Home Assistant. ")
              .append("Danh sách thiết bị (entity_id | tên | trạng thái hiện tại):\n").append(devices)
              .append("\nKhi người dùng muốn bật / tắt / chỉnh thiết bị, gọi hàm ha_call với entity_id đúng ")
              .append("trong danh sách (chọn theo tên gần nhất với lời nói; \"tất cả đèn\" thì liệt kê các entity_id). ")
              .append("Hỏi trạng thái thì trả lời theo danh sách, không cần gọi ha_call. ")
              .append(DEVICE_RULES).append(' ')
              .append("Mọi yêu cầu khác (trò chuyện, kể chuyện, hỏi đáp, đọc thơ…) không liên quan thiết bị: ")
              .append("trả lời bình thường như trên, chỉ cần gọi respond, KHÔNG từ chối. ")
              .append("Cuối cùng luôn gọi hàm respond với heard và reply; xác nhận điều khiển thì ngắn, ")
              .append("vd. \"Đã bật đèn phòng khách.\" Nếu ha_call báo lỗi hoặc không tìm thấy thiết bị thì nói rõ.");
        }
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
