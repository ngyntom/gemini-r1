package vn.r1.geminibot;

/**
 * Đèn viền của R1 theo trạng thái bot: nghe / nói = sáng trắng, nghĩ (chờ Gemini) = nhấp nháy,
 * chờ = tắt.
 *
 * Dùng chương trình có sẵn /system/bin/lights_test (như trang điều khiển EchoService):
 * "lights_test set <mặt nạ đèn> <RRGGBB>". Ghi thẳng vào /sys/class/leds/.../color
 * thì driver bỏ qua. App thường không chạy được nó trực tiếp nên gọi qua shell của
 * adbd (LocalAdb).
 */
final class Led {
    /** Bit 15..38 = 24 đèn của vòng LED. */
    private static final String RING = "7fffff8000";

    private static volatile boolean enabled = true;
    /** Trạng thái thử từ Web UI (/api/led), null = theo bot. */
    static volatile String test;
    private static String shown = "";
    private static String lastError = "";

    private Led() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Luồng theo dõi BotLog.state, chỉ ra lệnh khi kiểu đèn thay đổi. */
    static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String st = test != null ? test : BotLog.state;
                    String rgb = enabled || test != null ? color(st) : "0";
                    if (!rgb.equals(shown)) {
                        String out = LocalAdb.shell("lights_test set " + RING + " " + rgb + " && echo OK", 3000);
                        if (!out.contains("OK")) throw new Exception("lights_test lỗi: " + out.trim());
                        shown = rgb;
                        if (!lastError.isEmpty()) BotLog.i("Đèn LED hoạt động lại");
                        lastError = "";
                    }
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    // adbd bận (máy tính đang nối adb) -> thử lại sau; chỉ ghi log khi lỗi đổi
                    if (!String.valueOf(e).equals(lastError)) {
                        lastError = String.valueOf(e);
                        BotLog.w("Đèn LED: " + lastError);
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "led");
        t.setDaemon(true);
        t.start();
    }

    /** Nửa chu kỳ nhấp nháy lúc đang chờ Gemini. */
    private static final long BLINK_MS = 350;

    private static String color(String state) {
        switch (state) {
            // Viền đèn chỉ có LED trắng, độ sáng theo byte xanh dương: màu khác gần như không sáng,
            // nên hiệu ứng "đang nghĩ" là nhấp nháy bật/tắt.
            case "thinking": return (System.currentTimeMillis() / BLINK_MS) % 2 == 0 ? "ffffff" : "0";
            case "listening":
            case "speaking": return "ffffff";
            default: return "0";
        }
    }
}
