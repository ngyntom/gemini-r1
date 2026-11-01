package vn.r1.geminibot;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nhận từ đánh thức tiếng Việt offline bằng Vosk. Bộ nhận dạng chỉ được phép
 * nghe các cụm từ đánh thức (grammar) nên nhẹ và ít nhận nhầm.
 */
class VoskWakeWord implements WakeEngine {
    private final Context ctx;
    private Model model;
    private Recognizer recognizer;
    private List<String> phrases = new ArrayList<>();

    VoskWakeWord(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public void configure(Config cfg) throws Exception {
        if (model == null) {
            BotLog.i("Đang nạp model Vosk...");
            model = new Model(ModelInstaller.ensure(ctx).getAbsolutePath());
            BotLog.i("Model Vosk sẵn sàng");
        }
        List<String> norm = new ArrayList<>();
        for (String p : cfg.wakePhrases()) norm.add(normalize(p));
        if (norm.equals(phrases) && recognizer != null) return;
        BotLog.wakeName = cfg.wakeWord;

        JSONArray grammar = new JSONArray();
        for (String p : norm) grammar.put(p);
        grammar.put("[unk]");
        if (recognizer != null) recognizer.close();
        recognizer = new Recognizer(model, VoiceRecorder.RATE, grammar.toString());
        phrases = norm;
    }

    @Override
    public String waitFor(VoiceRecorder mic) {
        recognizer.reset();
        while (true) {
            short[] frame = mic.readFrame();
            if (frame == null) return null;
            if (mic.takeTrigger()) return "web";
            mic.trackNoise(frame);
            boolean done = recognizer.acceptWaveForm(frame, frame.length);
            String text = done ? field(recognizer.getResult(), "text")
                    : field(recognizer.getPartialResult(), "partial");
            String hit = match(text);
            if (hit != null) {
                recognizer.reset();
                return hit;
            }
        }
    }

    @Override
    public void close() {
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
        recognizer = null;
        model = null;
        phrases = new ArrayList<>();
    }

    private String match(String text) {
        if (text.isEmpty()) return null;
        String padded = " " + normalize(text) + " ";
        for (String p : phrases) {
            if (padded.contains(" " + p + " ")) return p;
        }
        return null;
    }

    private static String field(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception e) {
            return "";
        }
    }

    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
