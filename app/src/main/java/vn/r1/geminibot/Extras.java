package vn.r1.geminibot;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;

import java.io.File;

/**
 * AirPlay / DLNA và Bluetooth của loa.
 *
 * AirPlay + DLNA nằm trong app phát nhạc Phicomm (com.phicomm.speaker.player), nhưng bản gốc chỉ bật
 * khi app trợ lý Phicomm (com.phicomm.speaker.device, đã ẩn để nhả micro) ra lệnh. Bản sửa
 * autodlna.apk (cùng gói, cùng khoá ký với bản gốc - lấy từ bộ cài cũ của người dùng) tự bật AirPlay
 * + DLNA khi khởi động. Các service này không export nên không bật / tắt riêng được: bật = cài bản sửa
 * (từ /sdcard/autodlna.apk) rồi khởi động lại loa, tắt = gỡ bản cập nhật, quay về bản gốc.
 */
final class Extras {
    private static final String PLAYER = "com.phicomm.speaker.player";
    static volatile String busy = "";

    private Extras() {}

    static File airplayApk() {
        return new File(Environment.getExternalStorageDirectory(), "autodlna.apk");
    }

    /** true khi đang chạy bản sửa (bản cập nhật của app hệ thống). */
    static boolean airplayOn(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(PLAYER, 0);
            return (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void setAirplay(final Context ctx, final boolean on) {
        if (!busy.isEmpty()) return;
        busy = on ? "Đang bật AirPlay…" : "Đang tắt AirPlay…";
        new Thread(() -> {
            try {
                if (on) {
                    if (!airplayApk().exists()) throw new Exception("thiếu " + airplayApk() + " (xem README)");
                    // "pm" gọi qua biến: app gốc Phicomm (nếu còn) chặn lệnh adb chứa chữ "pm"
                    // Shell của adbd thấy bộ nhớ ngoài ở /sdcard (đường dẫn của app là /storage/emulated/0)
                    String out = LocalAdb.shell("x=p; ${x}m install -r /sdcard/autodlna.apk", 180000);
                    if (!out.contains("Success")) throw new Exception(out.trim());
                    BotLog.i("Đã bật AirPlay + DLNA, khởi động lại loa…");
                    busy = "Đã bật AirPlay, loa đang khởi động lại (~1,5 phút)";
                    Thread.sleep(1500);
                    LocalAdb.reboot(); // AirPlay chỉ khởi động cùng loa
                } else {
                    String out = LocalAdb.shell("x=p; ${x}m uninstall " + PLAYER, 60000);
                    if (!out.contains("Success")) throw new Exception(out.trim());
                    BotLog.i("Đã tắt AirPlay + DLNA (app phát nhạc về bản gốc)");
                }
            } catch (Exception e) {
                BotLog.w("AirPlay: " + e.getMessage());
            } finally {
                busy = "";
            }
        }, "airplay").start();
    }

    static boolean bluetoothOn() {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        return bt != null && bt.isEnabled();
    }

    static void setBluetooth(boolean on) {
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null) return;
        if (on) bt.enable();
        else bt.disable();
        BotLog.i(on ? "Bật Bluetooth (" + bt.getName() + ")" : "Tắt Bluetooth");
    }
}
