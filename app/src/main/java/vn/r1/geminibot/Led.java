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
 * Nghe thấy từ đánh thức thì lưỡi liềm sáng xoay 1 vòng quanh viền (script nền) rồi mới vào hiệu ứng
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
    private static String sweepLevels = "";
    private static Context appCtx;
    /** Trang Đèn LED bấm "Thử hiệu ứng đánh thức": chạy lưỡi liềm ngay (không cần gọi loa) rồi về kiểu đèn hiện tại. */
    private static volatile boolean testWake;

    static void testWake() {
        testWake = true;
    }

    /** Lưu cài đặt trên web: kiểu đèn có thể đã đổi -> luồng đèn kiểm lại và vẽ lại kiểu đang hiện. */
    private static volatile boolean doiCauHinh;

    static void daDoiCauHinh() {
        doiCauHinh = true;
    }

    /** Mức sáng trong cấu hình đổi thì dựng lại script (thử / đánh thức lần sau dùng ngay mức mới). */
    private static void capNhatSweep() {
        Config c = appCtx == null ? null : Config.load(appCtx);
        if (c == null || khoaHieuUng(c).equals(sweepLevels)) return;
        shown = ""; // Nghe / Nói đang hiện kiểu cũ -> vẽ lại theo kiểu mới
        try {
            sweep = writeSweep(appCtx);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng đánh thức: " + e.getMessage());
        }
        try {
            comet = writeComet(appCtx, c);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng sao chổi: " + e.getMessage());
        }
        try {
            music = writeMusic(appCtx, c);
        } catch (Exception e) {
            BotLog.w("Không tạo được hiệu ứng đèn theo nhạc: " + e.getMessage());
        }
    }
    private static File music;
    /** Đèn theo nhạc (Cài đặt → Đèn LED), xem MusicLight. */
    private static volatile boolean musicEnabled = true;

    /** Đèn trong đổi màu theo nhạc (cùng lúc với viền). */
    private static volatile boolean musicInner = true;
    /** Kiểu màu đèn trong theo nhạc: "4buc" | "min" | "cau_vong". */
    private static volatile String musicInnerKieu = "4buc";
    private static volatile String musicInnerMau = "0030ff";

    static void setMusicEnabled(boolean on, boolean inner, String kieu, String mau) {
        musicEnabled = on;
        String safeKieu = kieu == null || kieu.isEmpty() ? "4buc" : kieu;
        String safeMauM = mau == null || mau.isEmpty() ? "0030ff" : mau;
        if (inner != musicInner || !safeKieu.equals(musicInnerKieu) || !safeMauM.equals(musicInnerMau)) {
            musicInner = inner;
            musicInnerKieu = safeKieu;
            musicInnerMau = safeMauM;
            if ("music".equals(shown)) shown = ""; // đang chạy hiệu ứng nhạc: chạy lại với tuỳ chọn mới
        }
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
            appCtx = ctx.getApplicationContext();
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
                    if (doiCauHinh) {
                        doiCauHinh = false;
                        capNhatSweep();
                    }
                    if (testWake) {
                        testWake = false;
                        capNhatSweep();
                        if (sweep != null) {
                            String out = LocalAdb.shell(STOP + INNER_OFF + "; sh " + sweep.getAbsolutePath() + "; "
                                    + RING_WHITE + "; sleep 1\necho OK", 8000);
                            if (!out.contains("OK")) throw new Exception("lights_test lỗi: " + out.trim());
                        }
                        shown = ""; // vẽ lại kiểu đèn hiện tại sau khi thử
                    }
                    if (!mode.equals(shown)) {
                        // Quét sáng viền khi vào "listening" ngay sau từ đánh thức (cờ đặt trước khi BotService
                        // đổi trạng thái nên tới đây chắc chắn thấy). Cửa sổ 2,5 s: lần nghe tiếp theo (follow-up)
                        // hay bật đèn lại sau đó thì không quét; lệnh lỗi thì cờ còn nguyên -> quét lại khi thử lại.
                        boolean wake = wakeAt != 0 && test == null && "listening".equals(mode)
                                && System.currentTimeMillis() - wakeAt < 2500;
                        if (wake) capNhatSweep();
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
        Config c = appCtx != null && ("listening".equals(mode) || "speaking".equals(mode) || "thinking".equals(mode)) ? Config.load(appCtx) : null;
        // Quét đánh thức: script tự dừng sau đúng 1 vòng (~1 s) nên không cần PID/STOPF như sao chổi;
        // chỉ ghi từng đèn vào viền (multi_leds1) nên không đụng đèn trong, INNER_OFF đã tắt đèn trong trước.
        String sweepCmd = wake && sweep != null ? "; sh " + sweep.getAbsolutePath() : "";
        switch (mode) {
            // Viền trước, đèn trong sau (giữ thứ tự an toàn của bản cũ dù 2 nhóm là 2 file riêng).
            case "listening": {
                String inner = c == null ? "lights_test show 203" : innerCmd(c.ledNgheInnerKieu, c.ledNgheInnerMau);
                return stop + INNER_OFF + sweepCmd + "; " + (c == null ? RING_WHITE : vien(c, c.ledNghe, c.ledNgheVet))
                        + (inner.isEmpty() ? "" : "; " + inner);
            }
            case "speaking": {
                String inner = c == null ? INNER_GREEN : innerCmd(c.ledNoiInnerKieu, c.ledNoiInnerMau);
                return stop + INNER_OFF + "; " + (c == null ? RING_WHITE : vien(c, c.ledNoi, c.ledNoiVet))
                        + (inner.isEmpty() ? "" : "; " + inner);
            }
            case "error":
                return stop + INNER_OFF + "; " + RING_OFF + "; " + INNER_RED;
            case "muted":
                return stop + INNER_OFF + "; " + RING_OFF + "; " + INNER_MUTED;
            case "music":
                if (music != null) {
                    return stop + INNER_OFF + "; " + RING_OFF + "; rm -f " + STOPF + "; trap '' HUP; sh "
                            + music.getAbsolutePath() + " " + (musicInner ? 1 : 0)
                            + " " + musicInnerKieu + " " + musicInnerMau
                            + " </dev/null >/dev/null 2>&1 & echo $! > " + PID;
                }
                return stop + INNER_OFF + "; " + RING_OFF;
            case "thinking":
                if (comet != null) {
                    // Ghi PID ngay ở đây chứ không để script tự ghi khi chạy: bấm "về rảnh" liền sau "nghĩ" thì
                    // lệnh dừng tới trước khi script kịp ghi PID, bỏ qua, và sao chổi chạy mãi (thử trên loa).
                    String nghiKieu = c != null ? c.ledNghiInnerKieu : "nhay";
                    String nghiMau = c != null ? c.ledNghiInnerMau : "00ff00";
                    return stop + INNER_OFF + "; " + RING_OFF + "; rm -f " + STOPF + "; trap '' HUP; sh "
                            + comet.getAbsolutePath() + " " + nghiKieu + " " + nghiMau
                            + " </dev/null >/dev/null 2>&1 & echo $! > " + PID;
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
        return writeComet(ctx, appCtx != null ? Config.load(appCtx) : null);
    }

    private static File writeComet(Context ctx, Config c) throws Exception {
        // $1 = kieu đèn trong (nhay|tho|sang|tat), $2 = màu rrggbb; mặc định nhay/00ff00 (giữ nguyên nếu không có)
        // Dùng $mau (không dùng $2 trực tiếp) để shell không tạo lỗi khi $2 rỗng
        String setInner  = "echo '0 100 1 1 1 000000 '$mau' 100 0 " + INNER_IDS + "' > " + INNER_COLOR;
        String thoInner  = "echo '1 3000 99999999 1 1 000000 '$mau' 1400 1 " + INNER_IDS + "' > " + INNER_COLOR;
        // Màu đầu sao chổi và đuôi mờ dần (tính từ cấu hình)
        String cometMau = c != null && c.ledNghiCometMau != null && !c.ledNghiCometMau.isEmpty()
                ? c.ledNghiCometMau : "ffffff";
        int cr, cg, cb;
        try {
            cr = Integer.parseInt(cometMau.substring(0, 2), 16);
            cg = Integer.parseInt(cometMau.substring(2, 4), 16);
            cb = Integer.parseInt(cometMau.substring(4, 6), 16);
        } catch (Exception ignored) { cr = 255; cg = 255; cb = 255; }
        String tail1 = String.format("%02x%02x%02x", cr * 40 / 100, cg * 40 / 100, cb * 40 / 100);
        String tail2 = String.format("%02x%02x%02x", cr * 10 / 100, cg * 10 / 100, cb * 10 / 100);
        // Tốc độ sao chổi: thay đổi số vòng spin delay
        int paceCount = c != null && "nhanh".equals(c.ledNghiCometMs) ? 5
                : c != null && "cham".equals(c.ledNghiCometMs) ? 20 : 10;
        String pace = "p=0; while [ $p -lt " + paceCount + " ]; do p=$((p+1)); cat /proc/uptime >/dev/null; done";
        // Xoay cho comet script: 15 frame, mỗi frame sáng 1 đèn rồi tắt tự động
        StringBuilder xoayCmd = new StringBuilder("echo \"1 150 99999999 15");
        for (int i = 1; i <= 15; i++) xoayCmd.append(" 1 000000 $mau 400 1 1 ").append(i);
        xoayCmd.append("\" > ").append(INNER_COLOR);
        StringBuilder sb = new StringBuilder(RING_OFF)
                .append("\nmau=\"${2:-00ff00}\"\n")
                .append("case \"$1\" in\n")
                .append("  sang) ").append(setInner).append(" ;;\n")
                .append("  tho) ").append(thoInner).append(" ;;\n")
                .append("  xoay) ").append(xoayCmd).append(" ;;\n")
                .append("esac\n") // nhay: bật tắt trong vòng lặp; tat: INNER_OFF đã tắt rồi
                .append("n=0\ng=0\nwhile [ $n -lt 200 ]; do\n");
        for (int k = 0; k < 24; k++) {
            // Đèn trong nhịp ($1 = nhay hoặc rỗng để tương thích ngược): ~0,5 s sáng / 0,5 s tắt
            if (k % 6 == 0) {
                sb.append("if [ \"$1\" = \"nhay\" ] || [ -z \"$1\" ]; then\n")
                  .append("  if [ $g = 0 ]; then ").append(setInner)
                  .append("; g=1; else ").append(setColor(INNER_COLOR, INNER_IDS, "000000"))
                  .append("; g=0; fi\nfi\n");
            }
            sb.append(setLed(RING_COLOR, 16 + k, cometMau)).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 23) % 24, tail1)).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 22) % 24, tail2)).append('\n');
            sb.append(setLed(RING_COLOR, 16 + (k + 21) % 24, "000000")).append('\n');
            sb.append(pace).append('\n');
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
    /**
     * Đánh thức: lưỡi liềm sáng xoay đúng 1 vòng rồi dừng; lệnh "listening" ngay sau đó bật cả viền trắng.
     *
     * Ghi từng đèn KHÔNG dùng được: driver xếp mỗi lệnh vào hàng ~100 ms (xem writeMusic), 10 lệnh / bước
     * dồn thành ~24 s rồi bị lệnh viền trắng đè - nhìn chỉ thấy viền trắng (thử trên loa). Làm như hiệu ứng
     * đánh thức gốc của Phicomm (mã 1-24 trong /system/etc/lights_effects.conf): CẢ hiệu ứng trong MỘT lệnh
     * "1 <ms lệch giữa các khung> 1 <số khung>", mỗi khung "1 <màu từ> <màu tới> <ms chuyển> <quay ngược>
     * <số đèn> <các đèn>". "quay ngược" = 1: đèn sáng tới màu đích rồi TỰ tối về màu đầu (như hiệu ứng thở
     * 202/203) - lần đầu tưởng là "còn khung sau" nên thêm khung tối dần, khung đó lại tự sáng lên: viền
     * chạy 2 đường (lấp đầy rồi xoá) thay vì lưỡi liềm. Mỗi đèn 1 khung sáng-rồi-tắt, các đèn bắt đầu lệch
     * nhau 1 bước -> cung sáng giữa, mờ 2 đầu = lưỡi liềm (chọn trên loa trong 3 kiểu thử).
     * Lệnh ghi trả về ngay nên script chờ hiệu ứng chạy xong rồi mới để lệnh viền trắng chạy.
     */
    private static File writeSweep(Context ctx) throws Exception {
        Config c = Config.load(ctx);
        sweepLevels = khoaHieuUng(c);
        String lenh;
        double cho;
        if ("khong".equals(c.ledWakeKieu)) return null;                 // vào thẳng hiệu ứng Nghe
        if ("chop".equals(c.ledWakeKieu)) {                               // cả viền sáng lên rồi tự tắt 1 lần
            lenh = "1 0 1 1 1 000000 ffffff 250 1 " + RING_IDS;
            cho = 0.6;
        } else {
            lenh = xoayLenh(c, c.ledWakeVet, 1);
            cho = (24 * c.ledWakeMs + 2 * c.ledWakeLen * c.ledWakeMs) / 1000.0;
        }
        String body = RING_OFF + "\necho '" + lenh + "' > " + RING_COLOR + "\n"
                + String.format(java.util.Locale.US, "sleep %.2f\n", cho);
        return writeScript(ctx, "led_wake_sweep.sh", body);
    }

    /**
     * Lệnh xoay (1 lệnh nhiều khung, driver tự diễn): 24 khung, mỗi khung các đèn "sáng rồi tự tắt", khung sau
     * bắt đầu sau 1 bước; vet vệt cách đều 24/vet đèn. lap = số lần lặp (1 = 1 vòng; 99999999 = mãi tới khi một
     * lệnh "0 ..." (ép, như RING_WHITE / RING_OFF) của trạng thái sau đè lên - cột FORCE / SYNC của
     * lights_effects.conf: dòng bắt đầu bằng 0 ép ngay, bằng 1 xếp hàng theo khung).
     */
    private static String xoayLenh(Config c, int vet, int lap) {
        int buoc = c.ledWakeMs, chuyen = c.ledWakeLen * buoc;
        StringBuilder lenh = new StringBuilder("1 ").append(buoc).append(' ').append(lap).append(" 24");
        for (int b = 0; b < 24; b++) {
            lenh.append(" 1 000000 ffffff ").append(chuyen).append(" 1 ").append(vet);
            for (int j = 0; j < vet; j++) lenh.append(' ').append(16 + (b + j * 24 / vet) % 24);
        }
        return lenh.toString();
    }

    /** Đèn trong theo kiểu và màu: "tho" = thở, "sang" = sáng, "xoay" = quét vòng, "tat" = tắt. */
    private static String innerCmd(String kieu, String mau) {
        String safeMau = (mau == null || !mau.matches("[0-9a-fA-F]{6}")) ? "ffffff" : mau;
        switch (kieu == null ? "" : kieu) {
            case "sang":
                return setColor(INNER_COLOR, INNER_IDS, safeMau);
            case "tat":
                return "";
            case "xoay": {
                StringBuilder cmd = new StringBuilder("echo \"1 150 99999999 15");
                for (int i = 1; i <= 15; i++) cmd.append(" 1 000000 ").append(safeMau).append(" 400 1 1 ").append(i);
                return cmd.append("\" > ").append(INNER_COLOR).toString();
            }
            default: // tho
                return "echo '1 3000 99999999 1 1 000000 " + safeMau + " 1400 1 " + INNER_IDS + "' > " + INNER_COLOR;
        }
    }

    /** Viền ngoài lúc Nghe / Nói theo cấu hình: trắng đứng yên, thở trắng, xoay liên tục, tắt. */
    private static String vien(Config c, String kieu, int vet) {
        if (c == null || kieu == null || "trang".equals(kieu)) return RING_WHITE;
        switch (kieu) {
            case "tat":
                return RING_OFF;
            case "tho": // như hiệu ứng thở 202 của Phicomm nhưng cho 24 đèn viền
                return RING_OFF + "; echo '1 3000 99999999 1 1 000000 ffffff 1400 1 " + RING_IDS + "' > " + RING_COLOR;
            case "xoay":
                return RING_OFF + "; echo '" + xoayLenh(c, vet, 99999999) + "' > " + RING_COLOR;
            default:
                return RING_WHITE;
        }
    }

    /** Khoá các thông số hiệu ứng: đổi thì dựng lại script đánh thức và vẽ lại kiểu đèn đang hiện. */
    private static String khoaHieuUng(Config c) {
        return c.ledWakeKieu + "," + c.ledWakeLen + "," + c.ledWakeMs + "," + c.ledWakeVet + ","
                + c.ledNghe + c.ledNgheVet + "," + c.ledNoi + c.ledNoiVet + ","
                + c.ledNgheInnerKieu + c.ledNgheInnerMau + ","
                + c.ledNghiInnerKieu + c.ledNghiInnerMau + ","
                + c.ledNoiInnerKieu + c.ledNoiInnerMau + ","
                + c.ledNghiCometMau + c.ledNghiCometMs + ","
                + c.musicLedInnerMau + c.musicLedVienMau;
    }

    /**
     * Script đèn theo nhạc: đọc số đèn (0..24, MusicLight ghi) mỗi 40 ms, đổi thì bật đúng chừng ấy đèn
     * viền tính đối xứng từ đèn 16 sang hai bên, tắt phần còn lại. Dừng bằng STOPF như sao chổi.
     */
    private static File writeMusic(Context ctx) throws Exception {
        return writeMusic(ctx, appCtx != null ? Config.load(appCtx) : null);
    }

    private static File writeMusic(Context ctx, Config c) throws Exception {
        File level = MusicLight.levelFile();
        if (level == null) throw new Exception("không có bộ nhớ ngoài");
        int[] order = new int[MusicLight.RING];
        for (int i = 0, a = 0, b = 23; i < order.length; i++) order[i] = 16 + (i % 2 == 0 ? a++ : b--);
        // Driver đèn xếp lệnh vào hàng rồi chạy dần (~100 ms mỗi lệnh, ghi thì trả về ngay - đo trên loa):
        // ghi nhanh hơn thế thì hàng dồn lại, đèn chạy lệch / như không sáng (lỗi bản đầu, 2 lệnh mỗi 40 ms).
        // Nên mỗi lần đổi chỉ MỘT lệnh: tăng thì bật đèn 1..l (đèn đã sáng ghi lại không sao), giảm thì tắt
        // đèn l+1..24; nhiều nhất ~5 lần mỗi giây.
        // Cầu vồng thở: 6 màu chuyển lần lượt qua driver animation (1 lệnh, chạy mãi cho đến khi bị force-off).
        // Mỗi màu thở: interval 2000ms giữa các frame; transition 1800ms + reverse=1 → sáng lên rồi mờ dần.
        String rainbowCmd = "1 2000 99999999 6"
                + " 1 000000 ff0000 1800 1 " + INNER_IDS  // đỏ
                + " 1 000000 ff8800 1800 1 " + INNER_IDS  // cam
                + " 1 000000 aaff00 1800 1 " + INNER_IDS  // vàng-xanh
                + " 1 000000 00ffaa 1800 1 " + INNER_IDS  // xanh ngọc
                + " 1 000000 0088ff 1800 1 " + INNER_IDS  // xanh dương
                + " 1 000000 8800ff 1800 1 " + INNER_IDS; // tím
        // Smooth: bảng màu 25 bước (level 0..24) nội suy giữa 5 điểm dừng.
        int[][] stops = {{0,0x30,0xff},{0,0xb0,0xff},{0,0xff,0x30},{0xff,0xa0,0},{0xff,0,0x80}};
        String[] smoothColors = new String[25];
        for (int i = 0; i <= 24; i++) {
            int seg = Math.min(i / 6, 3);
            int t6 = (i == 24) ? 6 : i % 6;
            int r = stops[seg][0] + (stops[seg+1][0] - stops[seg][0]) * t6 / 6;
            int g = stops[seg][1] + (stops[seg+1][1] - stops[seg][1]) * t6 / 6;
            int b = stops[seg][2] + (stops[seg+1][2] - stops[seg][2]) * t6 / 6;
            smoothColors[i] = String.format("%02x%02x%02x", r, g, b);
        }

        // Màu viền khi phát nhạc (mặc định trắng)
        String vienMau = c != null && c.musicLedVienMau != null && !c.musicLedVienMau.isEmpty()
                ? c.musicLedVienMau : "ffffff";
        // Màu đèn trong 4buc: 4 độ sáng (20/40/70/100%) của màu được chọn
        String innerBaseMau = c != null && c.musicLedInnerMau != null && !c.musicLedInnerMau.isEmpty()
                ? c.musicLedInnerMau : "0030ff";
        int mr, mg, mb;
        try {
            mr = Integer.parseInt(innerBaseMau.substring(0, 2), 16);
            mg = Integer.parseInt(innerBaseMau.substring(2, 4), 16);
            mb = Integer.parseInt(innerBaseMau.substring(4, 6), 16);
        } catch (Exception ignored) { mr = 0; mg = 0x30; mb = 0xff; }
        String[] buc4 = {
            String.format("%02x%02x%02x", Math.max(1, mr*20/100), Math.max(1, mg*20/100), Math.max(1, mb*20/100)),
            String.format("%02x%02x%02x", Math.max(1, mr*40/100), Math.max(1, mg*40/100), Math.max(1, mb*40/100)),
            String.format("%02x%02x%02x", Math.max(1, mr*70/100), Math.max(1, mg*70/100), Math.max(1, mb*70/100)),
            innerBaseMau
        };

        StringBuilder sb = new StringBuilder(RING_OFF)
                // Khởi động cầu vồng TRƯỚC loop: 1 lệnh, driver tự chạy vòng mãi
                .append("\nif [ \"$1\" = 1 ] && [ \"$2\" = \"cau_vong\" ]; then\n  echo '")
                .append(rainbowCmd).append("' > ").append(INNER_COLOR).append("\nfi\n")
                .append("p=0\nwhile [ ! -f ").append(STOPF).append(" ]; do\n")
                .append("  read l < ").append(level.getAbsolutePath()).append("\n")
                .append("  if [ -n \"$l\" ] && [ \"$l\" -gt \"$p\" ]; then\n    case \"$l\" in\n");
        for (int n = 1; n <= MusicLight.RING; n++) {
            StringBuilder on = new StringBuilder();
            for (int i = 0; i < n; i++) on.append(' ').append(order[i]);
            sb.append("      ").append(n).append(") ").append(setColor(RING_COLOR, n + on.toString(), vienMau)).append(" ;;\n");
        }
        sb.append("    esac\n    p=$l\n  elif [ -n \"$l\" ] && [ \"$l\" -lt \"$p\" ]; then\n    case \"$l\" in\n");
        for (int n = 0; n < MusicLight.RING; n++) {
            StringBuilder off = new StringBuilder();
            for (int i = n; i < order.length; i++) off.append(' ').append(order[i]);
            sb.append("      ").append(n).append(") ").append(blackAll(RING_COLOR, (order.length - n) + off.toString())).append(" ;;\n");
        }
        sb.append("    esac\n    p=$l\n  fi\n");
        // Đèn trong (tham số $1 = 1): đổi màu theo nấc to, chỉ ghi khi sang nấc khác
        // Đèn trong theo kiểu ($2): bỏ qua khi cau_vong (driver tự chạy animation)
        sb.append("  if [ \"$1\" = 1 ] && [ \"$2\" != \"cau_vong\" ] && [ -n \"$l\" ]; then\n");
        sb.append("    if [ \"$2\" = \"min\" ]; then\n");
        // Smooth: 25 bước, theo dõi từng mức chính xác
        sb.append("      if [ \"$l\" != \"$c\" ]; then\n        case \"$l\" in\n");
        for (int i = 0; i <= 24; i++) {
            sb.append("          ").append(i).append(") ")
              .append(setColor(INNER_COLOR, INNER_IDS, smoothColors[i])).append(" ;;\n");
        }
        sb.append("        esac\n        c=$l\n      fi\n    else\n");
        // 4buc (mặc định): 4 dải độ sáng của màu được chọn
        sb.append("      if [ \"$l\" -le 6 ]; then b=1; elif [ \"$l\" -le 12 ]; then b=2; elif [ \"$l\" -le 18 ]; then b=3; else b=4; fi\n")
          .append("      if [ \"$b\" != \"$c\" ]; then\n        case $b in\n")
          .append("          1) ").append(setColor(INNER_COLOR, INNER_IDS, buc4[0])).append(" ;;\n")
          .append("          2) ").append(setColor(INNER_COLOR, INNER_IDS, buc4[1])).append(" ;;\n")
          .append("          3) ").append(setColor(INNER_COLOR, INNER_IDS, buc4[2])).append(" ;;\n")
          .append("          4) ").append(setColor(INNER_COLOR, INNER_IDS, buc4[3])).append(" ;;\n")
          .append("        esac\n        c=$b\n      fi\n    fi\n  fi\n");
        sb.append("  sleep 0.2\ndone\n")
          .append(RING_OFF).append('\n')
          .append(blackAll(INNER_COLOR, INNER_IDS)).append('\n')
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
