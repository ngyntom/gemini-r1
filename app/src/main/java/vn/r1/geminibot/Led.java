package vn.r1.geminibot;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Đèn của R1 theo trạng thái bot. Loa có 2 nhóm đèn (xem /system/etc/lights_effects.conf):
 *  - viền ngoài: 24 LED trắng, id 16..39 = bit 15..38 của mặt nạ, chỉnh được độ sáng từng LED;
 *  - đèn trong: LED màu id 1..15, chỉ gọi được các hiệu ứng có sẵn bằng "lights_test show <mã>"
 *    (ghi thẳng mặt nạ 1..15 làm loa khởi động lại).
 *
 *   nghe  = viền sáng + đèn trong thở xanh dương (203)
 *   nghĩ  = sao chổi chạy vòng trên viền + đèn trong nhịp xanh lá (script nền trên loa,
 *           gửi từng khung qua adb thì quá chậm)
 *   nói   = viền sáng + đèn trong xanh lá (501)
 *   lỗi   = đèn trong đỏ (500)
 *   rảnh  = tắt hết (close từng hiệu ứng đèn trong)
 *
 * App thường không chạy được lights_test nên gọi qua shell của adbd (LocalAdb).
 */
final class Led {
    /** Bit 15..38 = 24 đèn của viền. */
    private static final String RING = "7fffff8000";
    private static final String PID = "/data/local/tmp/gr1_led.pid";
    /**
     * Tắt đèn trong bằng "close" từng hiệu ứng. KHÔNG dùng "show 504": đó là hiệu ứng "luôn bật màu đen",
     * nó đè lên và che mọi màu bật sau (thử trên loa: 504 rồi 501 thì không sáng).
     */
    private static final String INNER_OFF =
            "lights_test close 203; lights_test close 500; lights_test close 501; lights_test close 504";
    /** Lúc app mới chạy: đóng hết hiệu ứng đèn trong có thể còn chạy (khởi động loa, app cũ…). */
    private static final String INNER_ALL_OFF = "for i in 200 202 203 204 206 251 252 253 254 255 500 501 502 504 "
            + "510 511 512 513 514 515; do lights_test close $i; done";

    private static volatile boolean enabled = true;
    /** Trạng thái thử từ Web UI (/api/led), null = theo bot. */
    static volatile String test;
    private static String shown = "";
    private static String lastError = "";
    private static File comet;

    private Led() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /** Luồng theo dõi BotLog.state, chỉ ra lệnh khi kiểu đèn thay đổi. */
    static void start(Context ctx) {
        try {
            comet = writeComet(ctx);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng sao chổi: " + e.getMessage());
        }
        final long started = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            long lastOff = 0;
            while (true) {
                try {
                    String st = test != null ? test : BotLog.state;
                    String mode = enabled || test != null ? mode(st) : "off";
                    // 30 s đầu: hiệu ứng khởi động của loa có thể bật đèn sau khi app đã tắt -> tắt lại vài lần
                    long now = System.currentTimeMillis();
                    if ("off".equals(mode) && now - started < 30000 && now - lastOff > 5000) {
                        lastOff = now;
                        LocalAdb.shell(command("off").replace(INNER_OFF, INNER_ALL_OFF) + "\necho OK", 6000);
                        shown = "off";
                    }
                    if (!mode.equals(shown)) {
                        // Xuống dòng, không dùng ";": lệnh "nghĩ" kết thúc bằng "&" và "& ;" là lỗi cú pháp sh
                        String out = LocalAdb.shell(command(mode) + "\necho OK", 4000);
                        if (!out.contains("OK")) throw new Exception("lights_test lỗi: " + out.trim());
                        shown = mode;
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

    private static String mode(String state) {
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

    /** Lệnh shell chuyển sang kiểu đèn mới; luôn dừng sao chổi cũ trước. */
    private static String command(String mode) {
        String stop = "kill $(cat " + PID + " 2>/dev/null) 2>/dev/null; rm -f " + PID + "; ";
        switch (mode) {
            // Lệnh viền để cuối: lệnh lights_test cuối của sao chổi vừa bị dừng có thể chạy trễ ~40 ms
            case "listening":
                return stop + INNER_OFF + "; lights_test show 203; lights_test set " + RING + " ffffff";
            case "speaking":
                return stop + INNER_OFF + "; lights_test show 501; lights_test set " + RING + " ffffff";
            case "error":
                return stop + INNER_OFF + "; lights_test set " + RING + " 0; lights_test show 500";
            case "thinking":
                if (comet != null) {
                    return stop + INNER_OFF + "; lights_test set " + RING + " 0; trap '' HUP; sh "
                            + comet.getAbsolutePath() + " </dev/null >/dev/null 2>&1 &";
                }
                return stop + INNER_OFF + "; lights_test set " + RING + " ffffff";
            default:
                return stop + INNER_OFF + "; lights_test set " + RING + " 0";
        }
    }

    /**
     * Script sao chổi: một đèn sáng chạy vòng kéo đuôi mờ dần. Tự dừng sau ~200 vòng
     * (vài phút) phòng khi app chết mà không kịp tắt.
     */
    private static File writeComet(Context ctx) throws Exception {
        String[] tail = {"ffffff", "606060", "181818", "0"};
        StringBuilder sb = new StringBuilder("echo $$ > " + PID + "\nn=0\ng=0\nwhile [ $n -lt 200 ]; do\n");
        for (int k = 0; k < 24; k++) {
            // Đèn trong nhịp xanh lá (~1 s sáng / 1 s tắt): loa không có hiệu ứng thở xanh lá
            if (k % 6 == 0) {
                sb.append("if [ $g = 0 ]; then lights_test show 501; g=1; else lights_test close 501; g=0; fi\n");
            }
            for (int t = tail.length - 1; t >= 0; t--) {
                int led = (k - t + 24) % 24;
                sb.append("lights_test set ").append(Long.toHexString(1L << (15 + led))).append(' ')
                  .append(tail[t]).append(t == 0 ? "\n" : "; ");
            }
        }
        sb.append("n=$((n+1))\ndone\nlights_test close 501\nlights_test set ").append(RING).append(" 0\nrm -f ")
          .append(PID).append('\n');
        File dir = ctx.getExternalCacheDir();
        if (dir == null) throw new Exception("không có bộ nhớ ngoài");
        File f = new File(dir, "led_comet.sh");
        FileOutputStream os = new FileOutputStream(f);
        os.write(sb.toString().getBytes("UTF-8"));
        os.close();
        return f;
    }
}
