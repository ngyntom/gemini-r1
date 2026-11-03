package vn.r1.geminibot;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Home Assistant REST API: lấy danh sách thiết bị (/api/states) để đưa cho Gemini,
 * và gọi lệnh (/api/services/<domain>/<service>) khi Gemini muốn điều khiển.
 *
 * Dùng địa chỉ LAN dạng http://IP:8123: Android 5.1 không tin chứng chỉ Let's Encrypt,
 * và không phân giải được homeassistant.local.
 */
final class HomeAssistant {
    /** Nhóm thiết bị Gemini được điều khiển. Các lệnh hệ thống (homeassistant.restart, shell_command, hassio…) bị chặn. */
    static final Set<String> CONTROL = new HashSet<>(Arrays.asList(
            "light", "switch", "fan", "cover", "climate", "media_player", "scene", "script", "lock",
            "input_boolean", "input_number", "input_select", "vacuum", "humidifier", "water_heater",
            "button", "input_button", "automation", "siren", "valve", "select", "number"));

    private HomeAssistant() {}

    /**
     * Danh sách thiết bị đã lưu trên Web UI, dạng "entity_id | tên | trạng thái", mỗi dòng một thiết bị;
     * null nếu chưa lưu thiết bị nào (bot không đụng tới Home Assistant). Chỉ các thiết bị này nằm trong
     * ids nên ha_call không điều khiển được thiết bị chưa lưu.
     */
    static String entities(Config cfg, Set<String> ids) throws Exception {
        if (cfg.haDevices.length() == 0) return null;
        JSONArray states = new JSONArray(request(cfg, "GET", "/api/states", null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.getJSONObject(i);
            String id = s.optString("entity_id");
            if (!cfg.haDevices.has(id)) continue;
            appendEntity(sb, s, id, domainOf(id), cfg.haDevices.optString(id, ""));
            ids.add(id);
        }
        return sb.toString();
    }

    static String domainOf(String id) {
        return id.contains(".") ? id.substring(0, id.indexOf('.')) : "";
    }

    private static void appendEntity(StringBuilder sb, JSONObject s, String id, String domain, String alias) {
        {
            JSONObject a = s.optJSONObject("attributes");
            if (a == null) a = new JSONObject();
            String name = a.optString("friendly_name", id);
            sb.append(id).append(" | ").append(alias.isEmpty() ? name : alias + " (tên gốc: " + name + ")")
              .append(" | ").append(s.optString("state"));
            String unit = a.optString("unit_of_measurement", "");
            if (!unit.isEmpty()) sb.append(' ').append(unit);
            if (a.has("brightness") && !a.isNull("brightness")) {
                sb.append(", độ sáng ").append(Math.round(a.optDouble("brightness", 0) * 100 / 255)).append('%');
            }
            if (a.has("current_temperature")) sb.append(", nhiệt độ phòng ").append(a.optString("current_temperature"));
            if (a.has("temperature") && !a.isNull("temperature") && "climate".equals(domain)) {
                sb.append(", đặt ").append(a.optString("temperature"));
            }
            sb.append('\n');
        }
    }

    /**
     * Loại thiết bị theo lời nói -> các nhóm entity hợp lệ. Gemini từng nghe "tắt đèn phòng ba" mà tắt
     * climate.may_lanh_phong_ba (không có đèn tên đó) -> chặn khi loại thiết bị không khớp.
     */
    private static final String[][] KIND = {
            {"đèn", "light switch"},
            {"quạt", "fan switch"},
            {"máy lạnh", "climate"}, {"điều hoà", "climate"}, {"điều hòa", "climate"},
            {"rèm", "cover"}, {"cửa cuốn", "cover"},
            {"ổ cắm", "switch"},
            {"tivi", "media_player switch"}, {"ti vi", "media_player switch"},
            {"máy hút bụi", "vacuum"}, {"robot", "vacuum"},
    };

    /** Lỗi nếu lời nói chỉ rõ một loại thiết bị mà entity thuộc nhóm khác, ngược lại null. */
    static String kindMismatch(String userWords, String entityId) {
        if (userWords == null) return null;
        String w = userWords.toLowerCase(java.util.Locale.ROOT);
        for (String[] k : KIND) {
            if (!w.contains(k[0])) continue;
            Set<String> ok = new HashSet<>(Arrays.asList(k[1].split(" ")));
            for (String id : entityId.split(",")) {
                String d = id.trim().contains(".") ? id.trim().substring(0, id.trim().indexOf('.')) : "";
                if (!ok.contains(d)) {
                    return "Người dùng nói \"" + k[0] + "\" nhưng " + id.trim() + " không phải loại đó. "
                            + "KHÔNG điều khiển thiết bị khác loại: nói với người dùng là không tìm thấy " + k[0]
                            + " khớp và kể tên các " + k[0] + " có trong danh sách.";
                }
            }
            return null;
        }
        return null;
    }

    /** Gọi một lệnh, trả về mô tả kết quả để gửi lại cho Gemini. */
    static JSONObject call(Config cfg, Set<String> knownIds, String domain, String service,
                           String entityId, JSONObject data, String userWords) throws Exception {
        if (!CONTROL.contains(domain)) {
            return new JSONObject().put("ok", false).put("error", "Không được phép gọi nhóm " + domain);
        }
        String mismatch = entityId == null ? null : kindMismatch(userWords, entityId);
        if (mismatch != null) {
            BotLog.w("Chặn lệnh Home Assistant sai loại: \"" + userWords + "\" -> " + entityId);
            return new JSONObject().put("ok", false).put("error", mismatch);
        }
        if (!service.matches("[a-z_]+")) {
            return new JSONObject().put("ok", false).put("error", "Tên lệnh không hợp lệ: " + service);
        }
        JSONObject body = data != null ? new JSONObject(data.toString()) : new JSONObject();
        if (entityId != null && !entityId.isEmpty()) {
            for (String id : entityId.split(",")) {
                if (!knownIds.contains(id.trim())) {
                    return new JSONObject().put("ok", false).put("error", "Không có thiết bị " + id.trim());
                }
            }
            body.put("entity_id", entityId);
        }
        BotLog.i("Home Assistant: " + domain + "." + service + " " + (entityId == null ? "" : entityId)
                + (data != null && data.length() > 0 ? " " + data : ""));
        JSONArray changed = new JSONArray(request(cfg, "POST", "/api/services/" + domain + "/" + service, body));
        JSONArray states = new JSONArray();
        for (int i = 0; i < changed.length(); i++) {
            JSONObject s = changed.getJSONObject(i);
            states.put(s.optString("entity_id") + " = " + s.optString("state"));
        }
        return new JSONObject().put("ok", true).put("new_states", states);
    }

    /**
     * Thiết bị cho tab Thiết bị trên Web UI: [{id, name, original, alias, enabled, domain, state, unit, temp, current}].
     * all = false: thiết bị đã lưu; all = true: mọi entity của HA, để chọn trong cửa sổ "Thêm/bớt thiết bị".
     */
    static JSONArray devices(Config cfg, boolean all) throws Exception {
        JSONArray states = new JSONArray(request(cfg, "GET", "/api/states", null));
        JSONArray out = new JSONArray();
        for (int i = 0; i < states.length(); i++) {
            JSONObject s = states.getJSONObject(i);
            String id = s.optString("entity_id");
            String domain = domainOf(id);
            boolean enabled = cfg.haDevices.has(id);
            if (!all && !enabled) continue;
            JSONObject a = s.optJSONObject("attributes");
            if (a == null) a = new JSONObject();
            String original = a.optString("friendly_name", id);
            String alias = cfg.haDevices.optString(id, "");
            JSONObject d = new JSONObject()
                    .put("id", id)
                    .put("name", alias.isEmpty() ? original : alias)
                    .put("original", original)
                    .put("alias", alias)
                    .put("enabled", enabled)
                    .put("domain", domain)
                    .put("state", s.optString("state"))
                    .put("unit", a.optString("unit_of_measurement", ""));
            if (a.has("temperature") && !a.isNull("temperature")) d.put("temp", a.optString("temperature"));
            if (a.has("current_temperature") && !a.isNull("current_temperature")) d.put("current", a.optString("current_temperature"));
            // Huy hiệu % trên ô Thiết bị (như Pi): độ sáng đèn 0-255, tốc độ quạt 0-100
            if (a.has("brightness") && !a.isNull("brightness")) d.put("brightness", a.optDouble("brightness"));
            if (a.has("percentage") && !a.isNull("percentage")) d.put("percentage", a.optDouble("percentage"));
            out.put(d);
        }
        return out;
    }

    /** Bật / tắt một thiết bị từ Web UI (người dùng tự bấm nên không cần kiểm tra loại như Gemini). */
    static void uiService(Config cfg, String entityId, String service) throws Exception {
        String domain = entityId.contains(".") ? entityId.substring(0, entityId.indexOf('.')) : "";
        if (!CONTROL.contains(domain)) throw new Exception("Không được phép điều khiển nhóm " + domain);
        if (!service.equals("turn_on") && !service.equals("turn_off") && !service.equals("toggle")
                && !service.equals("press")) {
            throw new Exception("Lệnh không hợp lệ: " + service);
        }
        BotLog.i("Web UI: " + domain + "." + service + " " + entityId);
        request(cfg, "POST", "/api/services/" + domain + "/" + service, new JSONObject().put("entity_id", entityId));
    }

    /** Gửi thông báo qua dịch vụ notify đã chọn (mã quên mật khẩu Web UI). */
    static void notify(Config cfg, String message) throws Exception {
        request(cfg, "POST", "/api/services/notify/" + cfg.haNotify,
                new JSONObject().put("title", Group.deviceName().replace('_', ' ')).put("message", message));
    }

    /** Kiểm tra kết nối cho Web UI: trả về số thiết bị đọc được. */
    static String test(Config cfg) throws Exception {
        int total = devices(cfg, true).length();
        JSONObject info = new JSONObject(request(cfg, "GET", "/api/config", null));
        return "Đã kết nối " + info.optString("location_name", "Home Assistant") + " (HA "
                + info.optString("version") + "), " + total + " entity, bot dùng " + cfg.haDevices.length()
                + " thiết bị đã lưu";
    }

    private static String request(Config cfg, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(cfg.haUrl + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(10000);
        c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + cfg.haToken);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            String resp = LocalAdb.readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code == 401) throw new Exception("Home Assistant từ chối token (HTTP 401)");
            if (code >= 400) throw new Exception("Home Assistant HTTP " + code + ": " + resp);
            return resp;
        } finally {
            c.disconnect();
        }
    }
}
