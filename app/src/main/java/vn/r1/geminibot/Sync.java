package vn.r1.geminibot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Đồng bộ cài đặt sang loa R1 khác trong mạng nhà (như "Đồng bộ sang Pi khác" của Voicebot): loa này đọc
 * các khoá của nhóm được chọn, đăng nhập loa đích (nếu loa đó có mật khẩu) rồi gửi /api/sync/import; loa đích
 * chỉ ghi đúng các khoá thuộc nhóm đã chọn. Không chép những gì riêng từng loa: tên loa, nhóm loa, mật khẩu.
 */
final class Sync {
    private static final String PREFS = "gemini_r1";
    static final Map<String, String[]> NHOM = new LinkedHashMap<>();

    static {
        NHOM.put("tro_ly", new String[]{"api_key", "model", "tts_model", "voice", "wake_engine", "mww_model", "mww_cutoff",
                "wake_word", "follow_up", "persona", "mic_gain", "tts_engine", "google_voice", "google_tts_key", "tts_rate",
                "wake_sound", "wake_reply", "local_time", "stt_engine", "live", "live_model", "live_idle", "gcloud_sa",
                "search", "city", "barge_in", "bot_self", "user_call"});
        NHOM.put("home_assistant", new String[]{"ha_url", "ha_token", "ha_notify", "ha_devices", "ha_dash_order",
                "ha_dash_dev_order", "ha_dash_icons"});
        NHOM.put("den", new String[]{"led", "music_led", "music_led_inner", "music_led_inner_kieu",
                "led_wake_kieu", "led_wake_vet", "led_wake_len",
                "led_wake_ms", "led_nghe", "led_nghe_vet", "led_noi", "led_noi_vet"});
    }

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private Sync() {
    }

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> chonNhom(JSONArray nhom) {
        List<String> out = new ArrayList<>();
        for (int i = 0; nhom != null && i < nhom.length(); i++) {
            String n = nhom.optString(i);
            if (NHOM.containsKey(n) && !out.contains(n)) out.add(n);
        }
        return out;
    }

    /** Loa R1 khác trong mạng + loa đó có bật đăng nhập không (để trang Đồng bộ hỏi mật khẩu). */
    static JSONArray thietBi() throws Exception {
        JSONArray loa = Group.scan();
        for (int i = 0; i < loa.length(); i++) {
            JSONObject l = loa.getJSONObject(i);
            try (Response r = HTTP.newCall(new Request.Builder().url("http://" + l.getString("ip") + ":" + WebServer.PORT
                    + "/api/auth").build()).execute()) {
                JSONObject a = new JSONObject(r.body() != null ? r.body().string() : "{}");
                l.put("can_dang_nhap", a.optBoolean("enabled"));
            } catch (Exception e) {
                l.put("can_dang_nhap", false); // bản cũ chưa có /api/auth: không có đăng nhập
            }
        }
        return loa;
    }

    /** Gửi các nhóm đã chọn sang loa ip. Trả thông báo cho người dùng; lỗi thì ném Exception có lời giải thích. */
    static String day(Context ctx, String ip, JSONArray nhomChon, String user, String pass) throws Exception {
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new Exception("Địa chỉ loa đích không hợp lệ");
        List<String> nhom = chonNhom(nhomChon);
        if (nhom.isEmpty()) throw new Exception("Chọn ít nhất 1 nhóm cài đặt");
        Map<String, ?> tatCa = p(ctx).getAll();
        JSONObject data = new JSONObject();
        for (String n : nhom) {
            for (String k : NHOM.get(n)) if (tatCa.containsKey(k)) data.put(k, String.valueOf(tatCa.get(k)));
        }
        String goc = "http://" + ip + ":" + WebServer.PORT;
        String cookie = null;
        if (!pass.isEmpty()) {
            JSONObject dn = new JSONObject().put("username", user.isEmpty() ? "admin" : user).put("password", pass);
            try (Response r = HTTP.newCall(new Request.Builder().url(goc + "/api/login")
                    .post(RequestBody.create(JSON, dn.toString())).build()).execute()) {
                if (r.code() == 401) throw new Exception("Sai tên đăng nhập hoặc mật khẩu của loa đích");
                if (!r.isSuccessful()) throw new Exception("Loa đích từ chối đăng nhập (" + r.code() + ")");
                String sc = r.header("Set-Cookie");
                if (sc != null) cookie = sc.split(";")[0];
            }
        }
        Request.Builder rq = new Request.Builder().url(goc + "/api/sync/import")
                .post(RequestBody.create(JSON, new JSONObject().put("nhom", new JSONArray(nhom)).put("data", data).toString()));
        if (cookie != null) rq.header("Cookie", cookie);
        try (Response r = HTTP.newCall(rq.build()).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (r.code() == 401) throw new Exception("Loa đích có mật khẩu - nhập tên đăng nhập và mật khẩu của loa đó");
            if (r.code() == 404) throw new Exception("Loa đích chưa có chức năng đồng bộ - cập nhật app cho loa đó trước");
            if (!r.isSuccessful()) {
                String loi = body;
                try {
                    loi = new JSONObject(body).optString("error", body);
                } catch (Exception ignored) {
                }
                throw new Exception("Loa đích báo lỗi: " + loi);
            }
        }
        BotLog.i("Đồng bộ: đã gửi " + data.length() + " cài đặt (" + nhom + ") sang " + ip);
        return "Đã đồng bộ " + data.length() + " cài đặt sang " + ip;
    }

    /** Loa đích: ghi các khoá thuộc nhóm đã chọn. Model từ đánh thức là file trên loa nguồn mà loa này không có thì bỏ qua. */
    static int nhan(Context ctx, JSONObject req) throws Exception {
        List<String> nhom = chonNhom(req.optJSONArray("nhom"));
        JSONObject data = req.optJSONObject("data");
        if (nhom.isEmpty() || data == null) throw new Exception("Thiếu nhóm hoặc dữ liệu đồng bộ");
        SharedPreferences.Editor e = p(ctx).edit();
        int n = 0;
        for (String ten : nhom) {
            for (String k : NHOM.get(ten)) {
                if (!data.has(k)) continue;
                String v = data.optString(k);
                if (k.equals("mww_model") && v.startsWith("/") && !new File(v).isFile()) {
                    BotLog.w("Đồng bộ: bỏ qua model từ đánh thức " + v + " (loa này chưa có file đó)");
                    continue;
                }
                e.putString(k, v);
                n++;
            }
        }
        e.commit();
        BotLog.i("Đồng bộ: đã nhận " + n + " cài đặt (" + nhom + ")");
        return n;
    }
}
