package vn.r1.geminibot;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Tải model Vosk tiếng Việt (32 MB) về loa khi lần đầu dùng Vosk, thay vì nhét vào APK.
 *
 * Android 5.1 không tin chứng chỉ Let's Encrypt mới của alphacephei.com, nên bỏ qua kiểm tra
 * chứng chỉ cho riêng lần tải này và kiểm tra SHA-256 của file thay thế.
 */
final class ModelInstaller {
    private static final String URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip";
    private static final String SHA256 = "efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5";
    private static final String DIR = "model-vn";

    private static org.vosk.Model shared;

    private ModelInstaller() {}

    /** Một model Vosk dùng chung (từ đánh thức Vosk + trả lời giờ tại loa), không bao giờ đóng. */
    static synchronized org.vosk.Model shared(Context ctx) throws Exception {
        if (shared == null) {
            BotLog.i("Đang nạp model Vosk...");
            shared = new org.vosk.Model(ensure(ctx).getAbsolutePath());
            BotLog.i("Model Vosk sẵn sàng");
        }
        return shared;
    }

    static synchronized File ensure(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), DIR);
        File marker = new File(dir, ".ok-" + SHA256.substring(0, 8));
        if (marker.exists()) return dir;

        File zip = new File(ctx.getCacheDir(), "vosk-model.zip");
        BotLog.i("Đang tải model Vosk (32 MB)...");
        download(zip);
        if (!sha256(zip).equals(SHA256)) {
            zip.delete();
            throw new IOException("Model Vosk tải về bị sai (SHA-256 không khớp)");
        }
        deleteRecursive(dir);
        unzipStripTop(zip, dir);
        zip.delete();
        if (!marker.createNewFile()) throw new IOException("Không ghi được " + marker);
        BotLog.i("Đã cài model Vosk");
        return dir;
    }

    private static void download(File dest) throws Exception {
        HttpsURLConnection c = (HttpsURLConnection) new URL(URL_ZIP).openConnection();
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] x, String a) {}
            public void checkServerTrusted(X509Certificate[] x, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        c.setSSLSocketFactory(ssl.getSocketFactory());
        c.setHostnameVerifier((HostnameVerifier) (h, s) -> h.equals("alphacephei.com"));
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Tải model Vosk: HTTP " + c.getResponseCode());
            copy(c.getInputStream(), new FileOutputStream(dest));
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Giải nén, bỏ thư mục gốc vosk-model-small-vn-0.4/ trong file zip. */
    private static void unzipStripTop(File zip, File dest) throws IOException {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            String name = e.getName();
            int slash = name.indexOf('/');
            if (slash < 0 || slash == name.length() - 1 || name.contains("..")) continue;
            File out = new File(dest, name.substring(slash + 1));
            if (e.isDirectory()) {
                out.mkdirs();
                continue;
            }
            out.getParentFile().mkdirs();
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
            os.close();
        }
        zin.close();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }
}
