package vn.r1.geminibot;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Đèn của R1 theo trạng thái bot. Loa có 2 nhóm đèn, mỗi nhóm một file của driver
 * (mã hiệu ứng xem /system/etc/lights_effects.conf):
 *  - viền ngoài: 24 LED trắng, id 16..39 -> /sys/class/leds/multi_leds1/color;
 *  - đèn trong: 15 LED màu, id 1..15 -> /sys/class/leds/multi_leds0/color.
 * Ghi thẳng vào file driver một dòng "0 100 1 1 1 000000 <rrggbb> 100 0 <số đèn> <các id...>": bật
 * ngay màu đó cho đúng các đèn trong danh sách, và CÁC LẦN GHI CỘNG DỒN với nhau (sao chổi / quét
 * đánh thức cần đúng tính chất này; thử trên loa). Tắt nhóm bằng dòng "1 200 1 1 1 0 0 200 0 <số
 * đèn> <các id...>". Hiệu ứng lặp (thở, chạy vòng) chỉ gọi được bằng "lights_test show/close <mã>".
 * KHÔNG dùng "lights_test set <mặt nạ> <màu>": firmware hiểu "set <id đèn> <màu>", gọi với mặt nạ
 * làm lights_test crash (SIGSEGV) mà chuỗi lệnh vẫn chạy tiếp ("echo OK" vẫn in) nên app tưởng đèn
 * đã bật - đúng lỗi viền ngoài không bao giờ sáng từng gặp.
 *
 *   nghe  = viền trắng + đèn trong thở xanh dương (show 203)
 *   nghĩ  = sao chổi chạy vòng trên viền + đèn trong nhịp xanh lá (script nền trên loa,
 *           gửi từng khung qua adb thì quá chậm)
 *   nói   = viền trắng + đèn trong xanh lá
 *   lỗi   = viền tắt + đèn trong đỏ
 *   tắt mic = viền tắt + đèn trong đỏ mờ
 *   rảnh  = tắt hết
 * Nghe thấy từ đánh thức thì vệt sáng quét 1 vòng quanh viền (script nền) rồi mới vào hiệu ứng
 * nghe; nút "Nghe ngay" trên Web UI không quét vì bot không nghe thấy từ khóa.
 *
 * App thường không chạy được lights_test / ghi được /sys nên gọi qua shell của adbd (LocalAdb).
 */
final class Led {
    private static final String PID = "/data/local/tmp/gr1_led.pid";
    /** Có file này thì sao chổi tự dừng sau khung hình đang chạy (xem command()). */
    private static final String STOPF = "/data/local/tmp/gr1_led.stop";
    /**
     * Dừng sao chổi: KHÔNG kill ngay - chờ script tự thoát qua STOPF cho khung đang chạy xong rồi
     * mới kill; quá hạn ~2 s (600 vòng "cat /proc/uptime", mỗi vòng ~3 ms) thì kill cứng.
     */
    private static final String STOP = "if [ -f " + PID + " ]; then echo > " + STOPF + "; i=0; while [ -f " + PID
            + " ] && [ $i -lt 600 ]; do i=$((i+1)); cat /proc/uptime >/dev/null; done; kill $(cat " + PID
            + " 2>/dev/null) 2>/dev/null; rm -f " + PID + " " + STOPF + "; fi; ";

    // ---- Driver đèn: ghi thẳng vào /sys thay cho "lights_test set" (crash, xem đầu file) ----
    private static final String RING_COLOR = "/sys/class/leds/multi_leds1/color";
    private static final String INNER_COLOR = "/sys/class/leds/multi_leds0/color";
    private static final String RING_IDS = "24 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39";
    private static final String INNER_IDS = "15 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15";
    /** Nhịp một bước của sao chổi / quét: ~10 lần fork "cat" ~3 ms = ~30 ms (đo trên loa). */
    private static final String PACE = "p=0; while [ $p -lt 10 ]; do p=$((p+1)); cat /proc/uptime >/dev/null; done";

    /** Bật ngay một màu cho cả nhóm đèn. */
    private static String setColor(String file, String ids, String rgb) {
        return "echo '0 100 1 1 1 000000 " + rgb + " 100 0 " + ids + "' > " + file;
    }

    /** Bật một màu cho đúng một đèn; các lần ghi cộng dồn với nhau. */
    private static String setLed(String file, int id, String rgb) {
        return "echo '0 100 1 1 1 000000 " + rgb + " 100 0 1 " + id + "' > " + file;
    }

    /**
     * Tắt cả nhóm NGAY LẬP TỨC (force màu đen). Dòng "1 200…" kiểu mờ dần từng bị driver xếp hàng sau
     * các frame của hiệu ứng thở nên đèn trong tắt muộn hơn viền; force thì hai nhóm tắt đồng thời
     * (thử cả hai trên loa và so sánh).
     */
    private static String blackAll(String file, String ids) {
        return "echo '0 100 1 1 1 000000 000000 100 0 " + ids + "' > " + file;
    }

    private static final String RING_WHITE = setColor(RING_COLOR, RING_IDS, "ffffff");
    private static final String RING_OFF = blackAll(RING_COLOR, RING_IDS);
    private static final String INNER_GREEN = setColor(INNER_COLOR, INNER_IDS, "00ff00");
    private static final String INNER_RED = setColor(INNER_COLOR, INNER_IDS, "ff0000");
    /** Mic tắt (nút bấm / Web UI): đỏ mờ, khác đỏ sáng của lỗi. */
    private static final String INNER_MUTED = setColor(INNER_COLOR, INNER_IDS, "300000");
    /** close cho hiệu ứng lặp (thở) dừng hẳn, rồi ghi đen xoá màu còn đọng. */
    private static final String INNER_OFF = "lights_test close 203; lights_test close 500; lights_test close 501; "
            + "lights_test close 504; " + blackAll(INNER_COLOR, INNER_IDS);
    /** Lúc app mới chạy: đóng hết hiệu ứng của CẢ HAI nhóm có thể còn chạy (loa khởi động, app cũ…). */
    private static final String INNER_ALL_OFF =
            "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 200 201 202 203 204 206 "
            + "251 252 253 254 255 500 501 502 503 504 505 510 511 512 513 514 515; do lights_test close $i; done; "
            + blackAll(INNER_COLOR, INNER_IDS) + "; " + blackAll(RING_COLOR, RING_IDS);

    private static volatile boolean enabled = true;
    /** Trạng thái thử từ Web UI (/api/led), null = theo bot. */
    static volatile String test;
    private static String shown = "";
    private static String lastError = "";
    private static File comet;
    private static File sweep;
    private static File music;
    /** Đèn theo nhạc (Cài đặt → Đèn LED), xem MusicLight. */
    private static volatile boolean musicEnabled = true;

    static void setMusicEnabled(boolean on) {
        musicEnabled = on;
    }
    /** Thời điểm nghe thấy từ đánh thức (wake()); lần vào "listening" kế tiếp trong ~2,5 s sẽ quét sáng viền. */
    private static volatile long wakeAt;

    private Led() {}

    static void setEnabled(boolean on) {
        enabled = on;
    }

    /**
     * BotService gọi NGAY TRƯỚC khi đặt trạng thái "listening" sau từ đánh thức. Gọi trước khi đổi
     * trạng thái: volatile ghi wakeAt rồi mới ghi state, nên luồng đèn thấy "listening" là thấy luôn cờ.
     */
    static void wake() {
        wakeAt = System.currentTimeMillis();
    }

    /** Luồng theo dõi BotLog.state, chỉ ra lệnh khi kiểu đèn thay đổi. */
    static void start(Context ctx) {
        try {
            comet = writeComet(ctx);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng sao chổi: " + e.getMessage());
        }
        try {
            sweep = writeSweep(ctx);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng quét đánh thức: " + e.getMessage());
        }
        try {
            MusicLight.init(ctx);
            music = writeMusic(ctx);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng đèn theo nhạc: " + e.getMessage());
        }
        Thread t = new Thread(() -> {
            long firstOk = 0, lastOff = 0;
            while (true) {
                try {
                    String st = test != null ? test : BotLog.state;
                    String mode = enabled || test != null ? mode(st) : "off";
                    // Tắt hết đèn (cả hiệu ứng còn sót từ lần chạy trước) ngay lần đầu adbd nhận lệnh, rồi lặp lại
                    // vài lần trong 30 s sau đó vì hiệu ứng khởi động của loa có thể bật đèn muộn. Tính từ lần
                    // đầu thành công chứ không từ lúc app chạy: vừa cài bằng adb thì adbd có thể bận ~1 phút.
                    long now = System.currentTimeMillis();
                    if (firstOk == 0 || ("off".equals(mode) && now - firstOk < 30000 && now - lastOff > 5000)) {
                        // command("off") đã gồm ghi đen cả 2 nhóm -> xoá cả màu đọng của app cũ
                        String out = LocalAdb.shell(command("off", false).replace(INNER_OFF, INNER_ALL_OFF) + "\necho OK", 8000);
                        if (!out.contains("OK")) throw new Exception("lights_test lỗi: " + out.trim());
                        if (firstOk == 0) firstOk = now;
                        lastOff = now;
                        shown = "off";
                    }
                    if (!mode.equals(shown)) {
                        // Quét sáng viền khi vào "listening" ngay sau từ đánh thức (cờ đặt trước khi BotService
                        // đổi trạng thái nên tới đây chắc chắn thấy). Cửa sổ 2,5 s: lần nghe tiếp theo (follow-up)
                        // hay bật đèn lại sau đó thì không quét; lệnh lỗi thì cờ còn nguyên -> quét lại khi thử lại.
                        boolean wake = wakeAt != 0 && test == null && "listening".equals(mode)
                                && System.currentTimeMillis() - wakeAt < 2500;
                        // Xuống dòng, không dùng ";": lệnh "nghĩ" kết thúc bằng "&" và "& ;" là lỗi cú pháp sh
                        String out = LocalAdb.shell(command(mode, wake) + "\necho OK", 8000); // quét đánh thức chạy ~1 s
                        if (!out.contains("OK")) throw new Exception("lights_test lỗi: " + out.trim());
                        if (wake) wakeAt = 0;
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
            case "paused":
                return "muted";
            default:
                // Rảnh mà đang phát nhạc: đèn theo nhạc (nếu bật)
                return musicEnabled && music != null && MusicLight.active() ? "music" : "off";
        }
    }

    /** Lệnh shell chuyển sang kiểu đèn mới; luôn dừng sao chổi cũ trước. wake = quét sáng viền trước hiệu ứng nghe. */
    private static String command(String mode, boolean wake) {
        String stop = STOP;
        // Quét đánh thức: script tự dừng sau đúng 1 vòng (~1 s) nên không cần PID/STOPF như sao chổi;
        // chỉ ghi từng đèn vào viền (multi_leds1) nên không đụng đèn trong, INNER_OFF đã tắt đèn trong trước.
        String sweepCmd = wake && sweep != null ? "; sh " + sweep.getAbsolutePath() : "";
        switch (mode) {
            // Viền trước, đèn trong sau (giữ thứ tự an toàn của bản cũ dù 2 nhóm là 2 file riêng).
            case "listening":
                return stop + INNER_OFF + sweepCmd + "; " + RING_WHITE + "; lights_test show 203";
            case "speaking":
                return stop + INNER_OFF + "; " + RING_WHITE + "; " + INNER_GREEN;
            case "error":
                return stop + INNER_OFF + "; " + RING_OFF + "; " + INNER_RED;
            case "muted":
                return stop + INNER_OFF + "; " + RING_OFF + "; " + INNER_MUTED;
            case "music":
                if (music != null) {
                    return stop + INNER_OFF + "; " + RING_OFF + "; rm -f " + STOPF + "; trap '' HUP; sh "
                            + music.getAbsolutePath() + " </dev/null >/dev/null 2>&1 & echo $! > " + PID;
                }
                return stop + INNER_OFF + "; " + RING_OFF;
            case "thinking":
                if (comet != null) {
                    // Ghi PID ngay ở đây chứ không để script tự ghi khi chạy: bấm "về rảnh" liền sau "nghĩ" thì
                    // lệnh dừng tới trước khi script kịp ghi PID, bỏ qua, và sao chổi chạy mãi (thử trên loa).
                    return stop + INNER_OFF + "; " + RING_OFF + "; rm -f " + STOPF + "; trap '' HUP; sh "
                            + comet.getAbsolutePath() + " </dev/null >/dev/null 2>&1 & echo $! > " + PID;
                }
                return stop + INNER_OFF + "; " + RING_WHITE;
            default:
                // Về rảnh: tắt hết cả hai nhóm
                return stop + INNER_OFF + "; " + RING_OFF;
        }
    }

    /**
     * Script sao chổi: đầu sáng chạy vòng kéo đuôi mờ dần (ghi từng đèn, các lần ghi cộng dồn).
     * Tự dừng sau ~200 vòng (~4 phút) phòng khi app chết mà không kịp tắt.
     */
    private static File writeComet(Context ctx) throws Exception {
        // Mở đầu xoá viền cho sạch (command("thinking") đã xoá nhưng script tự xoá phòng bị gọi rơi)
        StringBuilder sb = new StringBuilder(RING_OFF).append("\nn=0\ng=0\nwhile [ $n -lt 200 ]; do\n");
        for (int k = 0; k < 24; k++) {
            // Đèn trong nhịp xanh lá (~0,5 s sáng / 0,5 s tắt): không có hiệu ứng thở xanh lá
            if (k % 6 == 0) {
                sb.append("if [ $g = 0 ]; then ").append(setColor(INNER_COLOR, INNER_IDS, "00ff00"))
                  .append("; g=1; else ").append(setColor(INNER_COLOR, INNER_IDS, "000000"))
                  .append("; g=0; fi\n");
            }
            sb.append(setLed(RING_COLOR, 16 + k, "ffffff")).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 23) % 24, "606060")).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 22) % 24, "181818")).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 21) % 24, "000000")).append('\n');
            sb.append(PACE).append('\n');
            sb.append("[ -f ").append(STOPF).append(" ] && break\n"); // app báo dừng: thoát sau khung này
        }
        sb.append("n=$((n+1))\ndone\n")
          .append(setColor(INNER_COLOR, INNER_IDS, "000000")).append('\n')
          .append(RING_OFF).append('\n')
          .append("rm -f ").append(STOPF).append(' ').append(PID).append('\n');
        return writeScript(ctx, "led_comet.sh", sb.toString());
    }

    /**
     * Script quét sáng đánh thức: đầu sáng chạy đúng 1 vòng viền với đuôi mờ ngắn (~1 s/vòng,
     * thử trên loa), tự kết thúc nên không cần PID/STOPF. Mở đầu bằng xoá viền cho sạch.
     */
    private static File writeSweep(Context ctx) throws Exception {
        StringBuilder sb = new StringBuilder(RING_OFF).append('\n');
        for (int k = 0; k < 24; k++) {
            sb.append(setLed(RING_COLOR, 16 + k, "ffffff")).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 23) % 24, "505050")).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 21) % 24, "000000")).append('\n');
            sb.append(PACE).append('\n');
        }
        return writeScript(ctx, "led_wake_sweep.sh", sb.toString());
    }

    /**
     * Script đèn theo nhạc: đọc số đèn (0..24, MusicLight ghi) mỗi 40 ms, đổi thì bật đúng chừng ấy đèn
     * viền tính đối xứng từ đèn 16 sang hai bên, tắt phần còn lại. Dừng bằng STOPF như sao chổi.
     */
    private static File writeMusic(Context ctx) throws Exception {
        File level = MusicLight.levelFile();
        if (level == null) throw new Exception("không có bộ nhớ ngoài");
        int[] order = new int[MusicLight.RING];
        for (int i = 0, a = 0, b = 23; i < order.length; i++) order[i] = 16 + (i % 2 == 0 ? a++ : b--);
        // Driver đèn xếp lệnh vào hàng rồi chạy dần (~100 ms mỗi lệnh, ghi thì trả về ngay - đo trên loa):
        // ghi nhanh hơn thế thì hàng dồn lại, đèn chạy lệch / như không sáng (lỗi bản đầu, 2 lệnh mỗi 40 ms).
        // Nên mỗi lần đổi chỉ MỘT lệnh: tăng thì bật đèn 1..l (đèn đã sáng ghi lại không sao), giảm thì tắt
        // đèn l+1..24; nhiều nhất ~5 lần mỗi giây.
        StringBuilder sb = new StringBuilder(RING_OFF).append("\np=0\nwhile [ ! -f ").append(STOPF).append(" ]; do\n")
                .append("  read l < ").append(level.getAbsolutePath()).append("\n")
                .append("  if [ -n \"$l\" ] && [ \"$l\" -gt \"$p\" ]; then\n    case \"$l\" in\n");
        for (int n = 1; n <= MusicLight.RING; n++) {
            StringBuilder on = new StringBuilder();
            for (int i = 0; i < n; i++) on.append(' ').append(order[i]);
            sb.append("      ").append(n).append(") ").append(setColor(RING_COLOR, n + on.toString(), "ffffff")).append(" ;;\n");
        }
        sb.append("    esac\n    p=$l\n  elif [ -n \"$l\" ] && [ \"$l\" -lt \"$p\" ]; then\n    case \"$l\" in\n");
        for (int n = 0; n < MusicLight.RING; n++) {
            StringBuilder off = new StringBuilder();
            for (int i = n; i < order.length; i++) off.append(' ').append(order[i]);
            sb.append("      ").append(n).append(") ").append(blackAll(RING_COLOR, (order.length - n) + off.toString())).append(" ;;\n");
        }
        sb.append("    esac\n    p=$l\n  fi\n  sleep 0.2\ndone\n")
          .append(RING_OFF).append('\n')
          .append("rm -f ").append(STOPF).append(' ').append(PID).append('\n');
        return writeScript(ctx, "led_music.sh", sb.toString());
    }

    private static File writeScript(Context ctx, String name, String body) throws Exception {
        File dir = ctx.getExternalCacheDir();
        if (dir == null) throw new Exception("không có bộ nhớ ngoài");
        File f = new File(dir, name);
        FileOutputStream os = new FileOutputStream(f);
        os.write(body.getBytes("UTF-8"));
        os.close();
        return f;
    }
}
