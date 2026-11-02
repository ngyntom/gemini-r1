package vn.r1.geminibot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Nút bấm trên R1 (rk29-keypad, /dev/input/event0, mã 0xc3 = PHICOMM_OK): bấm một lần để tắt / mở mic;
 * bot đang nói thì bấm để ngắt lời.
 *
 * SELinux chặn app đọc /dev/input (untrusted_app), nên chạy một script nền bằng shell của adbd
 * (LocalAdb): "getevent -c 1" đợi từng sự kiện, thấy nút được nhấn xuống thì "am broadcast" về app.
 * Không giữ một luồng getevent dài trong LocalAdb vì adbd của R1 chỉ phục vụ một kết nối mỗi lúc
 * (đèn LED cũng cần adbd); dùng "-c 1" để getevent thoát và đẩy hết output sau mỗi sự kiện,
 * không bị kẹt trong bộ đệm của pipe.
 */
final class Button {
    static final String ACTION = "vn.r1.geminibot.BUTTON";
    private static final String PID = "/data/local/tmp/gr1_btn.pid";
    private static final String DEVICE = "/dev/input/event0";

    private Button() {}

    /** Đăng ký nhận nút bấm và chạy script nền (thử lại tới khi adbd nhận lệnh). */
    static void start(final BotService bot) {
        bot.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if ("speaking".equals(BotLog.state)) { // đang nói -> ngắt lời, không tắt mic
                    bot.interruptSpeech();
                    return;
                }
                boolean mute = !bot.isPaused();
                bot.setPaused(mute);
                BotLog.i(mute ? "Nút bấm: tắt mic" : "Nút bấm: mở mic");
            }
        }, new IntentFilter(ACTION));

        Thread t = new Thread(() -> {
            String err = "";
            while (true) {
                try {
                    File sh = writeScript(bot);
                    // Dừng script cũ (app vừa cập nhật / khởi động lại) rồi chạy bản mới
                    String out = LocalAdb.shell("kill $(cat " + PID + " 2>/dev/null) 2>/dev/null; trap '' HUP; sh "
                            + sh.getAbsolutePath() + " </dev/null >/dev/null 2>&1 & echo $! > " + PID
                            + "\necho OK", 8000);
                    if (!out.contains("OK")) throw new Exception(out.trim());
                    BotLog.i("Nút bấm: bấm một lần để tắt / mở mic, đang nói thì ngắt lời");
                    return;
                } catch (Exception e) {
                    // adbd bận (máy tính đang nối adb) -> thử lại sau
                    if (!String.valueOf(e).equals(err)) {
                        err = String.valueOf(e);
                        BotLog.w("Nút bấm: " + err);
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "button");
        t.setDaemon(true);
        t.start();
    }

    /** Script nền: dòng sự kiện dạng "0001 00c3 00000001" (EV_KEY, mã nút, 1 = nhấn xuống). */
    private static File writeScript(Context ctx) throws Exception {
        String body = "while true; do\n"
                + "  e=$(getevent -c 1 -q " + DEVICE + ")\n"
                + "  case \"$e\" in\n"
                + "    *00c3*00000001*) am broadcast -a " + ACTION + " >/dev/null 2>&1 ;;\n"
                + "    '') sleep 1 ;;\n" // getevent lỗi (thiết bị bận) -> đừng quay vòng liên tục
                + "  esac\n"
                + "done\n";
        File dir = ctx.getExternalCacheDir();
        if (dir == null) throw new Exception("không có bộ nhớ ngoài");
        File f = new File(dir, "button.sh");
        FileOutputStream os = new FileOutputStream(f);
        os.write(body.getBytes("UTF-8"));
        os.close();
        return f;
    }
}
