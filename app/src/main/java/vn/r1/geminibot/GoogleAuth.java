package vn.r1.geminibot;

import android.util.Base64;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Đăng nhập Google Cloud bằng service account (file JSON tải từ Cloud Console): ký JWT RS256 bằng
 * private key, đổi lấy access token (~1 giờ) ở token_uri rồi giữ lại tới gần hết hạn.
 * Dùng cho Cloud Speech-to-Text và Text-to-Speech thay cho API key.
 */
final class GoogleAuth {
    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    private static String cachedFor;
    private static String token;
    private static long expiresAt;

    private GoogleAuth() {}

    /** Email của service account (để hiện trên Web UI), "" nếu JSON không hợp lệ. */
    static String email(String saJson) {
        try {
            return new JSONObject(saJson).optString("client_email", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** Access token còn hạn cho service account này (lấy mới khi cần). */
    static synchronized String token(String saJson) throws Exception {
        long now = System.currentTimeMillis();
        if (token != null && saJson.equals(cachedFor) && now < expiresAt - 60000) return token;

        JSONObject sa = new JSONObject(saJson);
        String tokenUri = sa.optString("token_uri", "https://oauth2.googleapis.com/token");
        long iat = now / 1000;
        String header = b64(new JSONObject().put("alg", "RS256").put("typ", "JWT").toString().getBytes("UTF-8"));
        String claims = b64(new JSONObject()
                .put("iss", sa.getString("client_email"))
                .put("scope", SCOPE)
                .put("aud", tokenUri)
                .put("iat", iat)
                .put("exp", iat + 3600)
                .toString().getBytes("UTF-8"));
        String unsigned = header + "." + claims;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey(sa.getString("private_key")));
        sig.update(unsigned.getBytes("UTF-8"));
        String jwt = unsigned + "." + b64(sig.sign());

        HttpURLConnection c = (HttpURLConnection) new URL(tokenUri).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try {
            OutputStream os = c.getOutputStream();
            os.write(("grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8")
                    + "&assertion=" + URLEncoder.encode(jwt, "UTF-8")).getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = LocalAdb.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400) throw new Exception("Google đăng nhập service account lỗi HTTP " + code + ": " + resp);
            JSONObject j = new JSONObject(resp);
            token = j.getString("access_token");
            expiresAt = now + j.optLong("expires_in", 3600) * 1000;
            cachedFor = saJson;
            return token;
        } finally {
            c.disconnect();
        }
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String b = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(b, Base64.DEFAULT)));
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }
}
