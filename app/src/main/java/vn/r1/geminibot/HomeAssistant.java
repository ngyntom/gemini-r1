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
    /** Chỉ đọc trạng thái (hỏi nhiệt độ, cửa đang mở không…). */
    private static final Set<String> READ_ONLY = new HashSet<>(Arrays.asList(
            "sensor", "binary_sensor", "weather", "person", "device_tracker", "sun"));
    private static final int MAX_ENTITIES = 300;

    private HomeAssistant() {}

    /** Danh sách thiết bị dạng "entity_id | tên | trạng thái", mỗi dòng một thiết bị. */
    static String entities(Config cfg, Set<String> ids) throws Exception {
        JSONArray states = new JSONArray(request(cfg, "GET", "/api/states", null));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < states.length() && n < MAX_ENTITIES; i++) {
            JSONObject s = states.getJSONObject(i);
            String id = s.optString("entity_id");
            String domain = id.contains(".") ? id.substring(0, id.indexOf('.')) : "";
            if (!CONTROL.contains(domain) && !READ_ONLY.contains(domain)) continue;
            JSONObject a = s.optJSONObject("attributes");
            if (a == null) a = new JSONObject();
            sb.append(id).append(" | ").append(a.optString("friendly_name", id))
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
            ids.add(id);
            n++;
        }
        return sb.toString();
    }

    /** Gọi một lệnh, trả về mô tả kết quả để gửi lại cho Gemini. */
    static JSONObject call(Config cfg, Set<String> knownIds, String domain, String service,
                           String entityId, JSONObject data) throws Exception {
        if (!CONTROL.contains(domain)) {
            return new JSONObject().put("ok", false).put("error", "Không được phép gọi nhóm " + domain);
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

    /** Kiểm tra kết nối cho Web UI: trả về số thiết bị đọc được. */
    static String test(Config cfg) throws Exception {
        Set<String> ids = new HashSet<>();
        entities(cfg, ids);
        JSONObject info = new JSONObject(request(cfg, "GET", "/api/config", null));
        return "Đã kết nối " + info.optString("location_name", "Home Assistant") + " (HA "
                + info.optString("version") + "), " + ids.size() + " thiết bị";
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
