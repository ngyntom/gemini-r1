package vn.r1.geminibot;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Đăng nhập Web UI (như tài khoản của Voicebot trên Pi) để mở loa ra Internet qua tên miền / proxy.
 *
 * Một tài khoản: tên + mật khẩu băm PBKDF2 trong files/auth.json. Chưa đặt mật khẩu thì Web UI mở như cũ
 * (chỉ nên dùng trong mạng nhà). Đặt rồi thì mọi /api/* (trừ vài API công khai, xem isPublic) cần cookie phiên.
 * Phiên lưu ở files/sessions.json nên khởi động lại app không bị đăng xuất.
 * Quên mật khẩu: mã 6 số gửi qua thông báo Home Assistant (requestCode / confirmCode), hoặc xoá files/auth.json
 * qua adb (README) - Web UI trở về không cần đăng nhập.
 */
final class Auth {
    static final String COOKIE = "r1_session";
    static final int SESSION_DAYS = 30;
    private static final int ITER = 10000; // CPU loa yếu: ~0,3 s mỗi lần thử, đủ chậm để dò mật khẩu
    private static final SecureRandom RNG = new SecureRandom();

    private static JSONObject account;                  // {user, salt, hash} hoặc null = chưa đặt
    private static JSONObject sessions;                 // {token: hết hạn (ms)}
    private static int failures;                        // sai liên tiếp (mọi nguồn: sau proxy IP đều giống nhau)
    private static long lockedUntil;

    private Auth() {
    }

    private static File file(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name);
    }

    private static synchronized void load(Context ctx) {
        if (sessions != null) return;
        try {
            account = new JSONObject(LocalAdb.readAll(new java.io.FileInputStream(file(ctx, "auth.json"))));
        } catch (Exception e) {
            account = null;
        }
        try {
            sessions = new JSONObject(LocalAdb.readAll(new java.io.FileInputStream(file(ctx, "sessions.json"))));
        } catch (Exception e) {
            sessions = new JSONObject();
        }
    }

    private static void write(Context ctx, String name, JSONObject o) throws Exception {
        File tmp = file(ctx, name + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        fos.write(o.toString().getBytes("UTF-8"));
        fos.close();
        if (!tmp.renameTo(file(ctx, name))) throw new java.io.IOException("Không ghi được " + name);
    }

    static synchronized boolean enabled(Context ctx) {
        load(ctx);
        return account != null;
    }

    static synchronized String user(Context ctx) {
        load(ctx);
        return account == null ? "" : account.optString("user");
    }

    /** API không cần đăng nhập: app iOS dò địa chỉ, loa khác dò nhóm, chính các API đăng nhập. */
    static boolean isPublic(String method, String path) {
        if (!path.startsWith("/api/")) return true; // trang + file tĩnh: phải tải được mới hiện màn đăng nhập
        if (method.equals("GET") && (path.equals("/api/ping") || path.equals("/api/group/info")
                || path.equals("/api/auth"))) return true;
        return method.equals("POST") && (path.equals("/api/login") || path.equals("/api/logout")
                || path.equals("/api/auth/forgot/request") || path.equals("/api/auth/forgot/confirm"));
    }

    static String cookieToken(Map<String, String> headers) {
        String c = headers.get("cookie");
        if (c == null) return null;
        for (String part : c.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=")) return p.substring(COOKIE.length() + 1);
        }
        return null;
    }

    static synchronized boolean valid(Context ctx, String token) {
        load(ctx);
        if (token == null || token.isEmpty()) return false;
        long exp = sessions.optLong(token, 0);
        if (exp == 0) return false;
        if (exp < System.currentTimeMillis()) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    /** Đúng tên + mật khẩu thì trả token phiên mới; sai thì ném lỗi (thông báo cho người dùng). */
    static synchronized String login(Context ctx, String user, String password) throws Exception {
        load(ctx);
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            throw new Exception("Sai quá nhiều lần, thử lại sau " + ((lockedUntil - now) / 1000 + 1) + " giây");
        }
        if (account == null) throw new Exception("Loa chưa đặt mật khẩu");
        boolean ok = account.optString("user").equalsIgnoreCase(user.trim())
                & MessageDigest.isEqual(hash(password, account.optString("salt")),
                Base64.decode(account.optString("hash"), Base64.NO_WRAP));
        if (!ok) {
            failures++;
            // 5 lần sai: khoá 1 phút, mỗi lần sai tiếp gấp đôi, tối đa 15 phút
            if (failures >= 5) lockedUntil = now + Math.min(15 * 60_000L, 60_000L << Math.min(failures - 5, 4));
            throw new Exception("Sai tên đăng nhập hoặc mật khẩu");
        }
        failures = 0;
        lockedUntil = 0;
        String token = randomB64(32);
        // Dọn phiên hết hạn rồi mới thêm phiên mới
        for (Iterator<String> it = sessions.keys(); it.hasNext(); ) {
            if (sessions.optLong(it.next(), 0) < now) it.remove();
        }
        sessions.put(token, now + SESSION_DAYS * 86_400_000L);
        write(ctx, "sessions.json", sessions);
        return token;
    }

    static synchronized void logout(Context ctx, String token) throws Exception {
        load(ctx);
        if (token != null && sessions.has(token)) {
            sessions.remove(token);
            write(ctx, "sessions.json", sessions);
        }
    }

    /**
     * Đặt / đổi tài khoản. Đã có mật khẩu thì phải nhập đúng mật khẩu cũ. password rỗng = tắt đăng nhập.
     * Mọi phiên cũ bị huỷ; trả token phiên mới cho người đang đổi (khỏi phải đăng nhập lại), hoặc null khi tắt.
     */
    static synchronized String set(Context ctx, String user, String password, String oldPassword) throws Exception {
        load(ctx);
        if (account != null && !MessageDigest.isEqual(hash(oldPassword, account.optString("salt")),
                Base64.decode(account.optString("hash"), Base64.NO_WRAP))) {
            throw new Exception("Mật khẩu hiện tại không đúng");
        }
        sessions = new JSONObject();
        write(ctx, "sessions.json", sessions);
        if (password.isEmpty()) {
            file(ctx, "auth.json").delete();
            account = null;
            BotLog.i("Đã tắt đăng nhập Web UI");
            return null;
        }
        user = user.trim();
        if (user.isEmpty()) throw new Exception("Thiếu tên đăng nhập");
        if (password.length() < 6) throw new Exception("Mật khẩu tối thiểu 6 ký tự");
        String salt = randomB64(16);
        JSONObject a = new JSONObject().put("user", user).put("salt", salt)
                .put("hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP));
        write(ctx, "auth.json", a);
        account = a;
        BotLog.i("Đã đặt mật khẩu Web UI cho " + user);
        String token = randomB64(32);
        sessions.put(token, System.currentTimeMillis() + SESSION_DAYS * 86_400_000L);
        write(ctx, "sessions.json", sessions);
        return token;
    }

    // ---------- Quên mật khẩu (như /api/change_password/* của Pi): mã 6 số gửi qua thông báo Home Assistant ----------
    // Bảo mật dựa vào việc có mã trên điện thoại (thông báo HA), nên 2 API này không cần đăng nhập.
    private static String code;
    private static long codeExpires, lastCodeSent;
    private static int codeFailures;

    /** Tạo mã mới và gửi qua HA. Chặn xin mã dồn dập (API công khai: không để người lạ spam thông báo). */
    static void requestCode(Context ctx) throws Exception {
        Config c = Config.load(ctx);
        String ma;
        synchronized (Auth.class) {
            load(ctx);
            if (account == null) throw new Exception("Loa chưa đặt mật khẩu");
            if (!c.haEnabled()) throw new Exception("Loa chưa kết nối Home Assistant nên không gửi được mã");
            long now = System.currentTimeMillis();
            if (now - lastCodeSent < 60_000L) {
                throw new Exception("Vừa gửi mã, chờ " + ((60_000L - (now - lastCodeSent)) / 1000 + 1) + " giây rồi thử lại");
            }
            ma = String.format(java.util.Locale.US, "%06d", RNG.nextInt(1_000_000));
            code = ma;
            codeExpires = now + 5 * 60_000L;
            codeFailures = 0;
            lastCodeSent = now;
        }
        try {
            HomeAssistant.notify(c, Group.displayName(ctx), "Mã đặt lại mật khẩu Web UI của loa: " + ma + " (hết hạn sau 5 phút)");
        } catch (Exception e) {
            synchronized (Auth.class) {
                code = null;
                lastCodeSent = 0;
            }
            throw new Exception("Gửi thông báo qua Home Assistant thất bại: " + e.getMessage());
        }
        BotLog.i("Đã gửi mã quên mật khẩu Web UI qua Home Assistant (notify." + c.haNotify + ")");
    }

    /** Đúng mã thì đặt mật khẩu mới (giữ tên đăng nhập), huỷ mọi phiên cũ, trả token phiên mới. */
    static synchronized String confirmCode(Context ctx, String nhap, String password) throws Exception {
        load(ctx);
        if (code == null) throw new Exception("Chưa xin mã - bấm \"Gửi mã xác thực\" trước");
        if (System.currentTimeMillis() > codeExpires) {
            code = null;
            throw new Exception("Mã đã hết hạn - xin mã mới");
        }
        if (password.length() < 6) throw new Exception("Mật khẩu mới tối thiểu 6 ký tự");
        if (!MessageDigest.isEqual(code.getBytes("UTF-8"), nhap.trim().getBytes("UTF-8"))) {
            if (++codeFailures >= 5) {
                code = null;
                throw new Exception("Sai mã quá nhiều lần - xin mã mới");
            }
            throw new Exception("Mã xác thực không đúng");
        }
        code = null;
        String user = account.optString("user");
        String salt = randomB64(16);
        JSONObject a = new JSONObject().put("user", user).put("salt", salt)
                .put("hash", Base64.encodeToString(hash(password, salt), Base64.NO_WRAP));
        write(ctx, "auth.json", a);
        account = a;
        failures = 0;
        lockedUntil = 0;
        String token = randomB64(32);
        sessions = new JSONObject().put(token, System.currentTimeMillis() + SESSION_DAYS * 86_400_000L);
        write(ctx, "sessions.json", sessions);
        BotLog.i("Đã đặt lại mật khẩu Web UI bằng mã Home Assistant");
        return token;
    }

    private static byte[] hash(String password, String salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), ITER, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    }

    private static String randomB64(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Header Set-Cookie; qua proxy HTTPS (X-Forwarded-Proto: https) thì thêm Secure. maxAge 0 = xoá. */
    static String setCookie(String token, int maxAgeSec, Map<String, String> headers) {
        return COOKIE + "=" + (token == null ? "" : token) + "; Path=/; Max-Age=" + maxAgeSec
                + "; HttpOnly; SameSite=Lax"
                + ("https".equalsIgnoreCase(headers.get("x-forwarded-proto")) ? "; Secure" : "");
    }
}
