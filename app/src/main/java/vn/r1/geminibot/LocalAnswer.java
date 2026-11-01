package vn.r1.geminibot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.Recognizer;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;

/**
 * Trả lời ngay tại loa, không gửi Gemini, các câu hỏi giờ / ngày / thứ.
 *
 * Câu nói được Vosk (offline) nghe song song trong lúc thu âm, với bộ từ vựng hẹp chỉ gồm
 * các câu hỏi giờ. Chỉ trả lời tại loa khi cả câu khớp (không có từ lạ "[unk]"), còn câu
 * kiểu "mấy giờ trận đấu bắt đầu" vẫn gửi Gemini.
 */
final class LocalAnswer implements VoiceRecorder.FrameSink {
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "ạ", "vậy", "thế", "nhỉ", "nhé", "à", "hả", "ơi", "em", "anh", "chị", "bạn", "tôi", "mình",
            "cho", "hỏi", "xin", "nói", "biết", "đi", "loa", "trợ", "lý", "gemini"));
    private static final Set<String> TIME = set("mấy giờ", "mấy giờ rồi", "bây giờ là mấy giờ",
            "bây giờ mấy giờ", "bây giờ mấy giờ rồi", "bây giờ là mấy giờ rồi", "giờ là mấy giờ",
            "giờ mấy giờ rồi", "bây giờ là mấy giờ vậy", "mấy giờ rồi vậy");
    private static final Set<String> DATE = set("hôm nay ngày mấy", "hôm nay là ngày mấy",
            "hôm nay ngày bao nhiêu", "hôm nay là ngày bao nhiêu", "nay ngày mấy", "ngày mấy",
            "hôm nay là ngày gì", "hôm nay ngày gì");
    private static final Set<String> WEEKDAY = set("hôm nay thứ mấy", "hôm nay là thứ mấy",
            "nay thứ mấy", "thứ mấy", "hôm nay là thứ gì", "hôm nay thứ gì");
    private static final String[] THU = {"", "Chủ Nhật", "thứ Hai", "thứ Ba", "thứ Tư", "thứ Năm",
            "thứ Sáu", "thứ Bảy"};

    private final Context ctx;
    private volatile Recognizer rec;
    private boolean loading;

    LocalAnswer(Context ctx) {
        this.ctx = ctx;
    }

    /** Nạp model Vosk ở luồng nền (lần đầu sẽ tải 32 MB). */
    synchronized void loadAsync() {
        if (rec != null || loading) return;
        loading = true;
        new Thread(() -> {
            try {
                JSONArray g = new JSONArray();
                Set<String> words = new HashSet<>(FILLER);
                for (Set<String> s : Arrays.asList(TIME, DATE, WEEKDAY)) {
                    for (String p : s) words.addAll(Arrays.asList(p.split(" ")));
                }
                for (String w : words) g.put(w);
                g.put("[unk]");
                rec = new Recognizer(ModelInstaller.shared(ctx), VoiceRecorder.RATE, g.toString());
                BotLog.i("Trả lời giờ tại loa: sẵn sàng");
            } catch (Throwable e) {
                BotLog.e("Không nạp được Vosk cho trả lời tại loa", e);
            } finally {
                synchronized (this) {
                    loading = false;
                }
            }
        }, "local-answer").start();
    }

    boolean ready() {
        return rec != null;
    }

    // ---- Nghe song song trong lúc thu âm ----

    @Override
    public synchronized void begin() {
        if (rec != null) rec.reset();
    }

    @Override
    public synchronized void frame(short[] f) {
        if (rec != null) rec.acceptWaveForm(f, f.length);
    }

    /** Câu Vosk nghe được trong lần thu vừa rồi ("" nếu chưa sẵn sàng). */
    synchronized String heard() {
        if (rec == null) return "";
        try {
            return new JSONObject(rec.getFinalResult()).optString("text", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Khớp câu hỏi ----

    /** Câu trả lời nếu cả câu là hỏi giờ / ngày / thứ, ngược lại null. */
    static String answer(String question) {
        String q = VoskWakeWord.normalize(question);
        if (q.isEmpty() || q.contains("unk")) return null;
        StringBuilder core = new StringBuilder();
        for (String w : q.split(" ")) {
            if (FILLER.contains(w)) continue;
            if (core.length() > 0) core.append(' ');
            core.append(w);
        }
        String c = core.toString();
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        if (TIME.contains(c)) return timeText(now);
        if (DATE.contains(c)) return "Hôm nay là " + dateText(now) + ".";
        if (WEEKDAY.contains(c)) return "Hôm nay là " + dateText(now) + ".";
        return null;
    }

    private static String timeText(Calendar now) {
        int h = now.get(Calendar.HOUR_OF_DAY), m = now.get(Calendar.MINUTE);
        String buoi = h < 4 ? "đêm" : h < 11 ? "sáng" : h < 13 ? "trưa" : h < 18 ? "chiều" : h < 22 ? "tối" : "đêm";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return "Bây giờ là " + h12 + " giờ " + (m == 0 ? "đúng" : m + " phút") + " " + buoi + ".";
    }

    private static String dateText(Calendar now) {
        return THU[now.get(Calendar.DAY_OF_WEEK)] + ", ngày " + now.get(Calendar.DAY_OF_MONTH)
                + " tháng " + (now.get(Calendar.MONTH) + 1) + " năm " + now.get(Calendar.YEAR);
    }

    private static Set<String> set(String... s) {
        return new HashSet<>(Arrays.asList(s));
    }
}
