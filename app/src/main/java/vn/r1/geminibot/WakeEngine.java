package vn.r1.geminibot;

/** Bộ nhận từ đánh thức offline (Vosk hoặc microWakeWord). */
interface WakeEngine {
    /** Áp cấu hình hiện tại; chỉ nạp lại model khi cấu hình thay đổi. */
    void configure(Config cfg) throws Exception;

    /** Chặn cho tới khi nghe thấy từ đánh thức. Trả về tên từ khớp, hoặc null nếu mic dừng. */
    String waitFor(VoiceRecorder mic);

    void close();
}
