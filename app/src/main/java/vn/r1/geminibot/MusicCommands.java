package vn.r1.geminibot;

import android.content.Context;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lệnh nhạc bằng giọng nói, xử lý ngay tại loa (không qua Gemini, như local_fast_path của Pi):
 * "mở bài Lạc Trôi", "phát nhạc Sơn Tùng", "dừng nhạc", "tạm dừng", "phát tiếp", "bài tiếp theo", "bài trước".
 * Bắt buộc có chữ bài / nhạc / ca khúc để không lẫn với "mở đèn", "bật quạt"…
 */
final class MusicCommands {
    private static final Locale VI = new Locale("vi", "VN");
    private static final Pattern PLAY = Pattern.compile(
            "^(?:hãy\\s+)?(?:mở|phát|bật|cho\\s+(?:tôi|mình|em|tao|con|cháu)\\s+nghe|nghe)\\s+"
                    + "(?:cho\\s+(?:tôi|mình|em|tao|con|cháu)\\s+(?:nghe\\s+)?)?(?:bài\\s+hát|ca\\s+khúc|bài|nhạc)"
                    + "(?:\\s+(?:của|do))?\\s*(.*?)\\s*(?:đi|nhé|nha|với)?$");
    private static final Pattern STOP = Pattern.compile("^(?:hãy\\s+)?(?:dừng|tắt|ngừng)\\s+(?:phát\\s+)?nhạc.*");
    private static final Pattern PAUSE = Pattern.compile("^(?:tạm\\s+dừng|dừng\\s+lại)(?:\\s+nhạc)?.*");
    private static final Pattern RESUME = Pattern.compile("^(?:tiếp\\s+tục(?:\\s+phát)?(?:\\s+nhạc|\\s+bài)?|phát\\s+tiếp).*");
    private static final Pattern NEXT = Pattern.compile(
            "^(?:(?:chuyển|qua|sang)\\s+)?(?:bài|bản)\\s+(?:tiếp(?:\\s+theo)?|khác|kế(?:\\s+tiếp)?)(?:\\s+đi)?$|^(?:chuyển|qua)\\s+bài.*");
    private static final Pattern BACK = Pattern.compile("^(?:quay\\s+lại\\s+|phát\\s+lại\\s+)?bài\\s+(?:trước|lúc\\s+nãy).*");

    private MusicCommands() {}

    /** Câu trả lời cho người dùng, hoặc null nếu không phải lệnh nhạc. */
    static String handle(Context ctx, String text) {
        String t = text.toLowerCase(VI).replaceAll("[.,!?]", " ").replaceAll("\\s+", " ").trim();
        Music m = Music.get(ctx);
        try {
            if (STOP.matcher(t).matches()) return m.stop();
            boolean active = m.status().optString("state").matches("playing|paused");
            if (active && PAUSE.matcher(t).matches()) {
                m.pause();
                return "Đã tạm dừng nhạc.";
            }
            if (active && RESUME.matcher(t).matches()) {
                m.resume();
                return "Phát tiếp nhé.";
            }
            if (active && NEXT.matcher(t).matches()) {
                m.next();
                return "Chuyển bài tiếp theo.";
            }
            if (active && BACK.matcher(t).matches()) {
                m.back();
                return "Quay lại bài trước.";
            }
            Matcher pm = PLAY.matcher(t);
            if (pm.matches()) {
                String q = pm.group(1).trim();
                if (q.isEmpty()) {
                    // "mở nhạc": có bài đang tạm dừng thì phát tiếp, không thì mở nhạc đang thịnh hành
                    if (active) {
                        m.resume();
                        return "Phát tiếp nhé.";
                    }
                    q = "nhạc trẻ hot";
                }
                BotLog.i("Lệnh nhạc: tìm \"" + q + "\"");
                String reply = m.playSearch(q);
                m.holdForBot(true); // phát sau khi bot nói xong câu báo
                return reply;
            }
        } catch (Exception e) {
            BotLog.w("Lệnh nhạc lỗi: " + e.getMessage());
            return "Không mở được nhạc: " + e.getMessage();
        }
        return null;
    }
}
