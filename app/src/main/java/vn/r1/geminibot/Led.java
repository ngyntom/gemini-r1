package vn.r1.geminibot;

/**
 * Đèn LED của R1 đổi màu theo trạng thái bot: nghe = xanh dương, nghĩ = cam nhấp nháy,
 * nói = xanh lá, chờ = tắt.
 *
 * Driver nhận chuỗi lệnh ở /sys/class/leds/multi_leds0/color (định dạng giống
 * /system/etc/lights_effects.conf). App thường bị SELinux chặn ghi sysfs, nên ghi qua
 * shell của adbd (LocalAdb).
 */
final class Led {
    private static final String DEV = "/sys/class/leds/multi_leds0/color";
    private static final String RING = "24 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39";

    private static volatile boolean enabled = true;
    /** Trạng thái thử từ Web UI (/api/led), null = theo bot. */
    static volatile String test;
    private static String shown = "";
    private static String lastError = "";

    private Led() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Luồng theo dõi BotLog.state, chỉ ghi khi trạng thái (và màu) thay đổi. */
    static void start() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String st = test != null ? test : BotLog.state;
                    String key = enabled || test != null ? look(st) : "off";
                    if (!key.equals(shown)) {
                        String saved = current;
                        String cmd = effect(key);
                        String out;
                        try {
                            out = LocalAdb.shell("echo '" + cmd + "' > " + DEV + " && echo OK", 3000);
                        } catch (Exception e) {
                            current = saved; // chưa ghi được -> màu đang hiển thị vẫn là màu cũ
                            throw e;
                        }
                        if (!out.contains("OK")) {
                            current = saved;
                            throw new Exception("ghi đèn lỗi: " + out.trim());
                        }
                        shown = key;
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

    /** Gộp trạng thái bot thành kiểu đèn (idle, paused, starting... đều là tắt). */
    private static String look(String state) {
        switch (state) {
            case "listening":
            case "thinking":
            case "speaking":
            case "error":
                return state;
            default:
                return "off";
        }
    }

    private static String effect(String look) {
        switch (look) {
            case "listening": return solid("0050ff");
            case "thinking": return breathe("201000", "ff8000");
            case "speaking": return solid("00c040");
            case "error": return solid("ff0000");
            default: return off();
        }
    }

    /** Màu đang hiển thị; driver bỏ qua lệnh chuyển từ một màu sang chính nó. */
    private static String current = "000000";

    private static String solid(String rgb) {
        String from = rgb.equals(current) ? (rgb.equals("000000") ? "010101" : "000000") : current;
        current = rgb;
        return "1 150 1 1 1 " + from + " " + rgb + " 150 0 " + RING;
    }

    private static String breathe(String lo, String hi) {
        current = hi;
        return "1 1200 99999999 1 0 " + lo + " " + hi + " 600 1 " + RING;
    }

    private static String off() {
        return solid("000000");
    }
}
