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
            "tts_engine", "google_voice", "google_tts_key", "tts_rate", "led"};

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
    public String persona;
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
        try {
            c.micGain = Math.max(0.1f, Math.min(32f, Float.parseFloat(p.getString("mic_gain", "1").trim())));
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
        c.led = !"0".equals(p.getString("led", "1").trim());
        c.persona = p.getString("persona",
                "Bạn là trợ lý giọng nói trên loa thông minh. Trả lời ngắn gọn, tự nhiên, "
                        + "bằng ngôn ngữ người dùng nói (mặc định tiếng Việt). Không dùng markdown, "
                        + "emoji hay ký hiệu vì câu trả lời sẽ được đọc thành tiếng.");
        return c;
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
                .put("persona", c.persona)
                .put("mic_gain", String.valueOf(c.micGain))
                .put("tts_engine", c.ttsEngine)
                .put("google_voice", c.googleVoice)
                .put("google_tts_key_set", !c.googleTtsKey.isEmpty())
                .put("tts_rate", String.valueOf(c.ttsRate))
                .put("led", c.led ? "1" : "0");
    }

    /** Lưu các khoá có trong JSON từ Web UI. api_key rỗng = giữ nguyên. */
    public static void saveFromJson(Context ctx, JSONObject o) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (String k : KEYS) {
            if (!o.has(k)) continue;
            String v = o.optString(k, "").trim();
            if ((k.equals("api_key") || k.equals("google_tts_key")) && v.isEmpty()) continue;
            if (k.equals("google_tts_key") && v.equals("-")) v = ""; // "-" = xoá key riêng
            e.putString(k, v);
        }
        e.apply();
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
