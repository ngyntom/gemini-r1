package vn.r1.geminibot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Cấu hình lưu trong SharedPreferences, đặt qua intent extras khi start service. */
public class Config {
    private static final String PREFS = "gemini_r1";
    static final String[] KEYS = {"api_key", "model", "tts_model", "voice", "wake_engine",
            "mww_model", "mww_cutoff", "wake_word", "follow_up", "persona", "mic_gain",
            "tts_engine", "google_voice", "google_tts_key", "tts_rate", "led", "wake_sound", "wake_reply", "local_time",
            "ha_url", "ha_token", "stt_engine", "live", "live_model", "live_idle", "gcloud_sa", "search", "city", "barge_in", "bot_self", "user_call", "group_members", "group_stereo", "music_led", "music_led_inner", "ha_dash_order", "ha_dash_icons", "ha_notify"};

    public String apiKey;
    public String model;
    public String ttsModel;
    public String voice;
    /** "mww" (microWakeWord) hoặc "vosk". */
    public String wakeEngine;
    /** Tên model có sẵn (hey_jarvis, okay_nabu, hey_mycroft, alexa) hoặc đường dẫn .tflite. */
    public String mwwModel;
    /** Ngưỡng xác suất 0..1; 0 = dùng giá trị trong file .json của model. */
    public float mwwCutoff;
    /** Vosk: một hoặc nhiều cụm từ đánh thức, cách nhau bằng dấu phẩy. */
    public String wakeWord;
    /** Số giây nghe tiếp câu sau mà không cần gọi lại từ đánh thức (0 = tắt). */
    public int followUpSec;
    /** Lúc bot đang nói vẫn nghe từ đánh thức để ngắt lời (mic thu cả tiếng loa, cần nói to hơn). */
    public boolean bargeIn;
    public String persona;
    /** Xưng hô: bot tự xưng (vd. "em") và gọi người dùng (vd. "anh"); rỗng = để Gemini tự chọn. */
    public String botSelf;
    public String userCall;
    /** Nhóm loa phát đồng bộ: IP các loa R1 khác (cách nhau bằng dấu phẩy) và chế độ stereo. */
    public String groupMembers;
    public boolean groupStereo;
    /** Đèn viền nháy theo nhạc khi đang phát nhạc. */
    public boolean musicLed;
    /** Đèn trong đổi màu theo nhạc (cùng với viền). */
    public boolean musicLedInner;
    /** Hệ số khuếch đại micro (1 = giữ nguyên), cho mic thu nhỏ. */
    public float micGain;
    /** "gfree" (Google Dịch, miễn phí), "google" (Cloud TTS, cần key Cloud) hoặc "gemini". */
    public String ttsEngine;
    public String googleVoice;
    /** Key riêng cho Cloud TTS; rỗng = dùng api_key. */
    public String googleTtsKey;
    /** Tốc độ đọc 0.5..2 (1 = bình thường). */
    public float ttsRate;
    public boolean led;
    /** Âm báo khi đánh thức: "chime", "voice" (đọc wakeReply) hoặc "none". */
    public String wakeSound;
    public String wakeReply;
    /** Hỏi giờ / ngày / thứ thì trả lời tại loa (Vosk offline), không gửi Gemini. */
    public boolean localTime;
    /** Home Assistant, vd. http://192.168.2.10:8123 (rỗng = không dùng). */
    public String haUrl;
    /** Long-lived access token của Home Assistant. */
    public String haToken;
    /** Dịch vụ notify của HA gửi mã quên mật khẩu Web UI (vd. mobile_app_iphone), như HA_NOTIFY_SERVICE của Pi. */
    public String haNotify;

    /** "google" = Cloud Speech-to-Text rồi gửi chữ cho Gemini; "gemini" = gửi audio cho Gemini nghe. */
    public String sttEngine;
    /** Sau từ đánh thức thì trò chuyện liên tục qua Gemini Live. */
    public boolean live;
    public String liveModel;
    /** Live: im lặng quá số giây này thì kết thúc, quay lại chờ từ đánh thức. */
    public int liveIdleSec;

    /** File JSON service account Google Cloud (STT / TTS); rỗng = dùng API key. */
    public String gcloudSa;

    /** Cho Gemini tìm kiếm web khi cần thông tin thực tế (giá vàng, xổ số, thời tiết, tin tức…). */
    public boolean search;
    /** Thành phố của loa; hỏi thời tiết / "ở đây" hiểu theo thành phố này. */
    public String city;
    /**
     * Thiết bị Home Assistant đã chọn cho bot: entity_id -> tên gọi khác ("" = dùng tên gốc).
     * Rỗng = chưa chọn, dùng mọi thiết bị điều khiển được như trước.
     */
    public JSONObject haDevices;

    /** Persona kèm dòng xưng hô (nếu đã đặt) - dùng làm system prompt cho cả Gemini thường và Live. */
    public String personaPrompt() {
        if (botSelf.isEmpty() && userCall.isEmpty()) return persona;
        StringBuilder sb = new StringBuilder(persona).append("\n\nXưng hô khi nói tiếng Việt:");
        if (!botSelf.isEmpty()) sb.append(" tự xưng là \"").append(botSelf).append("\";");
        if (!userCall.isEmpty()) sb.append(" gọi người dùng là \"").append(userCall).append("\";");
        sb.append(" luôn giữ đúng cách xưng hô này trong mọi câu trả lời");
        if (!botSelf.isEmpty() && !userCall.isEmpty()) {
            sb.append(" (vd. \"Dạ, ").append(botSelf).append(" chào ").append(userCall).append("\")");
        }
        return sb.append('.').toString();
    }

    public boolean haEnabled() {
        return !haUrl.isEmpty() && !haToken.isEmpty();
    }

    public List<String> wakePhrases() {
        List<String> out = new ArrayList<>();
        for (String p : wakeWord.split(",")) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        if (out.isEmpty()) out.add("trợ lý ơi");
        return out;
    }

    public static Config load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config c = new Config();
        c.apiKey = p.getString("api_key", "");
        c.model = p.getString("model", "gemini-2.5-flash");
        c.ttsModel = p.getString("tts_model", "gemini-2.5-flash-preview-tts");
        c.voice = p.getString("voice", "Kore");
        c.wakeEngine = p.getString("wake_engine", "mww").trim().toLowerCase();
        c.mwwModel = p.getString("mww_model", "hey_jarvis").trim();
        try {
            c.mwwCutoff = Float.parseFloat(p.getString("mww_cutoff", "0").trim());
        } catch (NumberFormatException e) {
            c.mwwCutoff = 0;
        }
        // Phải là từ tiếng Việt có trong từ điển của model Vosk (vd. "Gemini" không có)
        c.wakeWord = p.getString("wake_word", "trợ lý ơi");
        try {
            c.followUpSec = Integer.parseInt(p.getString("follow_up", "6").trim());
        } catch (NumberFormatException e) {
            c.followUpSec = 6;
        }
        c.bargeIn = !"0".equals(p.getString("barge_in", "1").trim());
        try {
            c.micGain =Math.max(0.1f, Math.min(32f, Float.parseFloat(p.getString("mic_gain", "1").trim())));
        } catch (NumberFormatException e) {
            c.micGain = 1;
        }
        c.ttsEngine = p.getString("tts_engine", "gfree").trim().toLowerCase();
        c.googleVoice = p.getString("google_voice", "vi-VN-Neural2-A").trim();
        c.googleTtsKey = p.getString("google_tts_key", "").trim();
        try {
            c.ttsRate = Math.max(0.5f, Math.min(2f, Float.parseFloat(p.getString("tts_rate", "1").trim())));
        } catch (NumberFormatException e) {
            c.ttsRate = 1;
        }
        // Mặc định tắt: trên R1 thử nghiệm, kernel bỏ qua mọi lệnh đèn sau khi khởi động xong
        c.led = "1".equals(p.getString("led", "1").trim());
        c.wakeSound = p.getString("wake_sound", "chime").trim().toLowerCase();
        c.wakeReply = p.getString("wake_reply", "Dạ?").trim();
        if (c.wakeReply.isEmpty()) c.wakeReply = "Dạ?";
        c.localTime = !"0".equals(p.getString("local_time", "1").trim());
        c.haUrl = p.getString("ha_url", "").trim().replaceAll("/+$", "");
        c.haToken = p.getString("ha_token", "").trim();
        c.haNotify = p.getString("ha_notify", "notify").trim().replaceFirst("^notify\\.", "");
        if (c.haNotify.isEmpty()) c.haNotify = "notify";
        c.gcloudSa = p.getString("gcloud_sa", "").trim();
        try {
            c.haDevices = new JSONObject(p.getString("ha_devices", "{}"));
        } catch (Exception e) {
            c.haDevices = new JSONObject();
        }
        c.sttEngine = p.getString("stt_engine", "google").trim().toLowerCase();
        c.live = "1".equals(p.getString("live", "0").trim());
        c.liveModel = p.getString("live_model", "gemini-2.5-flash-native-audio-latest").trim();
        try {
            c.liveIdleSec = Math.max(5, Math.min(300, Integer.parseInt(p.getString("live_idle", "20").trim())));
        } catch (NumberFormatException e) {
            c.liveIdleSec = 20;
        }
        c.persona = p.getString("persona",
                "Bạn là trợ lý giọng nói trên loa thông minh. Trả lời ngắn gọn, tự nhiên, "
                        + "bằng ngôn ngữ người dùng nói (mặc định tiếng Việt). Không dùng markdown, "
                        + "emoji hay ký hiệu vì câu trả lời sẽ được đọc thành tiếng.");
        c.botSelf = p.getString("bot_self", "").trim();
        c.userCall = p.getString("user_call", "").trim();
        c.groupMembers = p.getString("group_members", "").trim();
        c.groupStereo = "1".equals(p.getString("group_stereo", "0").trim());
        c.musicLed = !"0".equals(p.getString("music_led", "1").trim());
        c.musicLedInner = !"0".equals(p.getString("music_led_inner", "1").trim());
        c.search = !"0".equals(p.getString("search", "1").trim());
        c.city = p.getString("city", "Hà Nội").trim();
        return c;
    }

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Cấu hình cho Web UI; API key chỉ trả về 4 ký tự cuối. */
    public static JSONObject toJson(Context ctx) throws Exception {
        Config c = load(ctx);
        return new JSONObject()
                .put("api_key_set", !c.apiKey.isEmpty())
                .put("api_key_hint", c.apiKey.length() > 4 ? "…" + c.apiKey.substring(c.apiKey.length() - 4) : "")
                .put("model", c.model)
                .put("tts_model", c.ttsModel)
                .put("voice", c.voice)
                .put("wake_engine", c.wakeEngine)
                .put("mww_model", c.mwwModel)
                .put("mww_cutoff", String.valueOf(c.mwwCutoff))
                .put("wake_word", c.wakeWord)
                .put("follow_up", String.valueOf(c.followUpSec))
                .put("barge_in", c.bargeIn ? "1" : "0")
                .put("persona", c.persona)
                .put("bot_self", c.botSelf)
                .put("user_call", c.userCall)
                .put("group_members", c.groupMembers)
                .put("group_stereo", c.groupStereo ? "1" : "0")
                .put("music_led", c.musicLed ? "1" : "0")
                .put("music_led_inner", c.musicLedInner ? "1" : "0")
                // Trang Thiết bị (chỉ Web UI dùng): thứ tự nhóm "light,fan,…" và icon riêng {entity_id: tên icon F7}
                .put("ha_dash_order", p(ctx).getString("ha_dash_order", ""))
                .put("ha_dash_icons", p(ctx).getString("ha_dash_icons", "{}"))
                .put("mic_gain", String.valueOf(c.micGain))
                .put("tts_engine", c.ttsEngine)
                .put("google_voice", c.googleVoice)
                .put("google_tts_key_set", !c.googleTtsKey.isEmpty())
                .put("tts_rate", String.valueOf(c.ttsRate))
                .put("led", c.led ? "1" : "0")
                .put("wake_sound", c.wakeSound)
                .put("wake_reply", c.wakeReply)
                .put("local_time", c.localTime ? "1" : "0")
                .put("ha_url", c.haUrl)
                .put("ha_token_set", !c.haToken.isEmpty())
                .put("ha_notify", c.haNotify)
                .put("gcloud_sa_email", c.gcloudSa.isEmpty() ? "" : GoogleAuth.email(c.gcloudSa))
                .put("ha_selected", c.haDevices.length())
                .put("stt_engine", c.sttEngine)
                .put("live", c.live ? "1" : "0")
                .put("live_model", c.liveModel)
                .put("live_idle", String.valueOf(c.liveIdleSec))
                .put("search", c.search ? "1" : "0")
                .put("city", c.city);
    }

    /** Lưu các khoá có trong JSON từ Web UI. api_key rỗng = giữ nguyên. */
    public static void saveFromJson(Context ctx, JSONObject o) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String k : KEYS) {
            if (!o.has(k)) continue;
            String v = o.optString(k, "").trim();
            boolean secret = k.equals("api_key") || k.equals("google_tts_key") || k.equals("ha_token")
                    || k.equals("gcloud_sa");
            if (secret && v.isEmpty()) continue;
            if (!k.equals("api_key") && secret && v.equals("-")) v = ""; // "-" = xoá key / token
            e.putString(k, v);
        }
        e.apply();
    }

    /** Lưu danh sách thiết bị HA đã chọn ({entity_id: tên gọi khác}); rỗng = dùng tất cả. */
    public static void saveHaDevices(Context ctx, JSONObject devices) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("ha_devices", devices.toString()).apply();
    }

    /** Lưu các extras có trong intent (ví dụ --es api_key XXX). */
    public static void saveFromIntent(Context ctx, Intent intent) {
        if (intent == null) return;
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String k : KEYS) {
            if (intent.hasExtra(k)) e.putString(k, intent.getStringExtra(k));
        }
        e.apply();
    }
}
