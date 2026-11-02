package vn.r1.geminibot;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quét và đổi mạng Wi-Fi từ Web UI. Đổi sang mạng mới mà không vào được (sai mật khẩu, sóng yếu…)
 * trong CONNECT_MS thì tự quay về mạng cũ, để loa không bị "mất tích" khỏi mạng nhà.
 */
final class Wifi {
    private static final long CONNECT_MS = 30000;
    /** Trạng thái lần đổi gần nhất cho Web UI: "" | "connecting" | "ok" | "fail", kèm thông báo. */
    static volatile String switchState = "";
    static volatile String switchMessage = "";

    private Wifi() {}

    /** Các mạng quét được, mỗi SSID một dòng (sóng mạnh nhất), mạnh trước. */
    static JSONArray scan(Context ctx) throws Exception {
        WifiManager wm = wm(ctx);
        wm.startScan();
        Thread.sleep(2500);
        String cur = unquote(wm.getConnectionInfo().getSSID());
        Map<String, ScanResult> best = new HashMap<>();
        for (ScanResult r : wm.getScanResults()) {
            if (r.SSID == null || r.SSID.isEmpty()) continue; // mạng ẩn
            ScanResult b = best.get(r.SSID);
            if (b == null || r.level > b.level) best.put(r.SSID, r);
        }
        List<ScanResult> list = new ArrayList<>(best.values());
        Collections.sort(list, (a, b) -> b.level - a.level);
        JSONArray out = new JSONArray();
        for (ScanResult r : list) {
            out.put(new JSONObject()
                    .put("ssid", r.SSID)
                    .put("level", r.level)
                    .put("bars", WifiManager.calculateSignalLevel(r.level, 5))
                    .put("secure", security(r.capabilities))
                    .put("ghz", r.frequency > 4000 ? 5 : 2.4)
                    .put("current", r.SSID.equals(cur))
                    .put("saved", findConfig(wm, r.SSID) != null));
        }
        return out;
    }

    /** Đổi sang mạng ssid (chạy nền). password rỗng = dùng mật khẩu đã lưu / mạng mở. */
    static void connectAsync(final Context ctx, final String ssid, final String password, final String secure) {
        switchState = "connecting";
        switchMessage = "Đang kết nối " + ssid + "…";
        new Thread(() -> {
            try {
                connect(ctx, ssid, password, secure);
            } catch (Exception e) {
                switchState = "fail";
                switchMessage = "Lỗi đổi Wi-Fi: " + e.getMessage();
                BotLog.w(switchMessage);
            }
        }, "wifi-switch").start();
    }

    private static synchronized void connect(Context ctx, String ssid, String password, String secure) throws Exception {
        WifiManager wm = wm(ctx);
        WifiInfo info = wm.getConnectionInfo();
        int prevId = info.getNetworkId();
        String prevSsid = unquote(info.getSSID());
        if (ssid.equals(prevSsid) && info.getSupplicantState() == SupplicantState.COMPLETED) {
            switchState = "ok";
            switchMessage = "Loa đang dùng " + ssid;
            return;
        }

        WifiConfiguration saved = findConfig(wm, ssid);
        int id;
        boolean added = false;
        if (saved != null && password.isEmpty()) {
            id = saved.networkId;
        } else {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = quote(ssid);
            if ("WEP".equals(secure)) {
                c.wepKeys[0] = quote(password);
                c.wepTxKeyIndex = 0;
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            } else if ("open".equals(secure)) {
                c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                if (password.length() < 8) throw new Exception("Mật khẩu WPA phải từ 8 ký tự");
                c.preSharedKey = quote(password);
            }
            // Mạng đã lưu mà nhập mật khẩu mới: thêm cấu hình mới, chỉ xoá cấu hình cũ khi vào được
            id = wm.addNetwork(c);
            added = true;
            if (id < 0) throw new Exception("Android không nhận cấu hình mạng " + ssid);
        }

        BotLog.i("Wi-Fi: đổi từ " + prevSsid + " sang " + ssid);
        wm.enableNetwork(id, true); // true = tạm tắt các mạng khác để chắc chắn vào mạng này
        wm.reconnect();

        long deadline = System.currentTimeMillis() + CONNECT_MS;
        boolean ok = false;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            WifiInfo now = wm.getConnectionInfo();
            if (now.getNetworkId() == id && now.getSupplicantState() == SupplicantState.COMPLETED
                    && now.getIpAddress() != 0) {
                ok = true;
                break;
            }
        }

        if (ok) {
            if (added && saved != null) wm.removeNetwork(saved.networkId); // bỏ mật khẩu cũ của mạng này
            enableAll(wm);
            wm.saveConfiguration();
            int ip = wm.getConnectionInfo().getIpAddress();
            String ipText = (ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 24 & 0xff);
            switchState = "ok";
            switchMessage = "Đã vào " + ssid + ", IP " + ipText;
            BotLog.i("Wi-Fi: " + switchMessage);
            return;
        }

        // Không vào được: bỏ cấu hình vừa thêm, quay lại mạng cũ
        if (added) wm.removeNetwork(id);
        if (prevId >= 0) wm.enableNetwork(prevId, true);
        wm.reconnect();
        long back = System.currentTimeMillis() + CONNECT_MS;
        while (System.currentTimeMillis() < back && wm.getConnectionInfo().getIpAddress() == 0) Thread.sleep(500);
        enableAll(wm);
        wm.saveConfiguration();
        switchState = "fail";
        switchMessage = "Không vào được " + ssid + " (sai mật khẩu hoặc sóng yếu), đã quay lại " + prevSsid;
        BotLog.w("Wi-Fi: " + switchMessage);
    }

    /** enableNetwork(id, true) tắt các mạng đã lưu khác: bật lại để loa tự chuyển khi mạng chính mất. */
    private static void enableAll(WifiManager wm) {
        List<WifiConfiguration> all = wm.getConfiguredNetworks();
        if (all == null) return;
        for (WifiConfiguration c : all) wm.enableNetwork(c.networkId, false);
    }

    private static WifiConfiguration findConfig(WifiManager wm, String ssid) {
        List<WifiConfiguration> all = wm.getConfiguredNetworks();
        if (all == null) return null;
        for (WifiConfiguration c : all) {
            if (quote(ssid).equals(c.SSID)) return c;
        }
        return null;
    }

    private static String security(String caps) {
        if (caps == null) return "open";
        if (caps.contains("WPA") || caps.contains("RSN")) return "WPA";
        if (caps.contains("WEP")) return "WEP";
        return "open";
    }

    private static WifiManager wm(Context ctx) {
        return (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String unquote(String s) {
        if (s == null) return "";
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
