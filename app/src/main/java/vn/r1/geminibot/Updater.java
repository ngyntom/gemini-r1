package vn.r1.geminibot;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Tự cập nhật app từ file APK tải lên qua Web UI, bằng "pm install" chạy qua adbd của loa.
 *
 * Script cài chạy nền trong shell (sống sót khi app bị tắt lúc cài), cài xong thì khởi động
 * lại BotService. Nếu APK ký bằng khoá khác (mỗi lần GitHub Actions build một khoá mới),
 * script gỡ bản cũ rồi cài lại, và chép lại cài đặt (kể cả API key) bằng run-as.
 */
final class Updater {
    private static final String PREFS = "gemini_r1.xml";

    private Updater() {}

    static File apkFile(Context ctx) {
        File dir = ctx.getExternalCacheDir();
        if (dir == null) throw new IllegalStateException("Không có bộ nhớ ngoài");
        return new File(dir, "update.apk");
    }

    /** Kiểm tra APK rồi bắt đầu cài; trả về thông báo cho Web UI. App sẽ bị tắt vài giây sau. */
    static String install(Context ctx, File apk) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        PackageInfo neu = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (neu == null) throw new IOException("File không phải APK hợp lệ");
        if (!ctx.getPackageName().equals(neu.packageName)) {
            throw new IOException("APK là của app khác (" + neu.packageName + ")");
        }
        PackageInfo cur = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
        boolean sameKey = neu.signatures != null && Arrays.equals(neu.signatures, cur.signatures);

        File dir = apk.getParentFile();
        // R1 không có FUSE (/sdcard -> /mnt/internal_sd), shell thấy đúng đường dẫn như app
        String SD = dir.getAbsolutePath();
        File prefsCopy = new File(dir, "prefs.xml");
        prefsCopy.delete();
        if (!sameKey) {
            copy(new File(ctx.getApplicationInfo().dataDir, "shared_prefs/" + PREFS), prefsCopy);
        }

        String pkg = ctx.getPackageName();
        String t = "/data/local/tmp";
        String script = "sleep 2\n"
                + "cp " + SD + "/update.apk " + t + "/gr1.apk\n"
                + "rm -f " + t + "/gr1.xml\n"
                + "[ -f " + SD + "/prefs.xml ] && cp " + SD + "/prefs.xml " + t + "/gr1.xml\n"
                + "{\n"
                + "  [ -f " + t + "/gr1.xml ] && echo 'Khoá ký khác -> gỡ bản cũ' && pm uninstall " + pkg + "\n"
                + "  pm install -r " + t + "/gr1.apk\n"
                + "} > " + t + "/gr1.log 2>&1\n"
                + "if [ -f " + t + "/gr1.xml ]; then\n"
                + "  cat " + t + "/gr1.xml | run-as " + pkg
                + " sh -c 'mkdir -p shared_prefs && cat > shared_prefs/" + PREFS + "'\n"
                + "fi\n"
                + "am startservice -n " + pkg + "/.BotService\n"
                + "sleep 2\n"
                + "mkdir -p " + SD + "\n"
                + "cp " + t + "/gr1.log " + SD + "/install.log\n"
                + "rm -f " + t + "/gr1.apk " + t + "/gr1.xml " + t + "/gr1.log " + SD + "/update.apk "
                + SD + "/prefs.xml\n";
        FileOutputStream fos = new FileOutputStream(new File(dir, "install.sh"));
        fos.write(script.getBytes("UTF-8"));
        fos.close();

        LocalAdb.shell("cp " + SD + "/install.sh " + t + "/gr1.sh; trap '' HUP; "
                + "sh " + t + "/gr1.sh </dev/null >/dev/null 2>&1 &", 10000);
        String v = neu.versionName + " (" + neu.versionCode + ")";
        BotLog.i("Đang cài bản " + v + (sameKey ? "" : " – khoá ký khác, gỡ bản cũ rồi cài lại, giữ cài đặt"));
        return "Đang cài bản " + v + ", bot sẽ khởi động lại sau khoảng 30 giây";
    }

    /** Gọi khi app khởi động: ghi kết quả lần cập nhật trước (nếu có) vào log. */
    static void reportLastInstall(Context ctx) {
        File dir = ctx.getExternalCacheDir();
        if (dir == null) return;
        File log = new File(dir, "install.log");
        if (!log.exists()) return;
        try {
            String s = LocalAdb.readAll(new FileInputStream(log)).trim().replace('\n', ' ');
            BotLog.i("Kết quả cập nhật: " + s);
        } catch (IOException e) {
            BotLog.e("Đọc kết quả cập nhật", e);
        }
        log.delete();
    }

    private static void copy(File from, File to) throws IOException {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }
}
