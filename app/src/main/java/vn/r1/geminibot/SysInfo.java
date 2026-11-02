package vn.r1.geminibot;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Thông tin hệ thống của loa cho tab Loa trên Web UI: RAM, CPU (%, MHz, nhiệt độ), Wi-Fi, bộ nhớ,
 * thời gian bật. Mỗi mục đọc lỗi (vd. SELinux chặn sysfs) thì bỏ qua, không làm hỏng /api/status.
 */
final class SysInfo {
    /** Nhiệt độ CPU RK3229 (cảm biến tsadc0, đơn vị °C). */
    private static final String TEMP = "/sys/class/hwmon/hwmon0/device/temp0_input";
    private static final String FREQ = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
    private static long lastTotal, lastIdle;
    private static int lastPct = -1;

    private SysInfo() {}

    static JSONObject read(Context ctx) {
        JSONObject j = new JSONObject();
        try {
            long total = 0, free = 0, cached = 0, buffers = 0;
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) total = kb(l);
                else if (l.startsWith("MemFree:")) free = kb(l);
                else if (l.startsWith("Buffers:")) buffers = kb(l);
                else if (l.startsWith("Cached:")) cached = kb(l);
            }
            r.close();
            // Buffers + Cached là bộ nhớ đệm, hệ thống lấy lại được khi cần -> không tính là đã dùng
            j.put("ram_total_mb", total / 1024).put("ram_used_mb", (total - free - buffers - cached) / 1024);
        } catch (Exception ignored) {
        }
        try {
            j.put("cpu_pct", cpuPercent());
        } catch (Exception ignored) {
        }
        try {
            j.put("cpu_mhz", Long.parseLong(line(FREQ)) / 1000);
        } catch (Exception ignored) {
        }
        try {
            j.put("cpu_temp", Integer.parseInt(line(TEMP)));
        } catch (Exception ignored) {
        }
        try {
            j.put("cores", Runtime.getRuntime().availableProcessors())
             .put("uptime_s", SystemClock.elapsedRealtime() / 1000)
             .put("android", Build.VERSION.RELEASE)
             .put("model", Build.MODEL);
            Runtime rt = Runtime.getRuntime();
            j.put("app_mem_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
            StatFs fs = new StatFs(ctx.getFilesDir().getAbsolutePath());
            j.put("storage_free_mb", fs.getAvailableBytes() / 1048576).put("storage_total_mb", fs.getTotalBytes() / 1048576);
        } catch (Exception ignored) {
        }
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo w = wm.getConnectionInfo();
            int ip = w.getIpAddress();
            j.put("wifi_ssid", w.getSSID() == null ? "" : w.getSSID().replace("\"", ""))
             .put("wifi_rssi", w.getRssi())
             .put("wifi_mbps", w.getLinkSpeed())
             .put("ip", (ip & 0xff) + "." + (ip >> 8 & 0xff) + "." + (ip >> 16 & 0xff) + "." + (ip >> 24 & 0xff));
        } catch (Exception ignored) {
        }
        return j;
    }

    /** % CPU từ lần gọi trước (Web UI hỏi mỗi 1,5 s) theo dòng "cpu" của /proc/stat. */
    private static synchronized int cpuPercent() throws Exception {
        String[] p = line("/proc/stat").trim().split("\\s+");
        long total = 0;
        for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
        long idle = Long.parseLong(p[4]) + (p.length > 5 ? Long.parseLong(p[5]) : 0);
        if (lastTotal > 0 && total > lastTotal) {
            lastPct = (int) Math.round(100.0 * (1 - (double) (idle - lastIdle) / (total - lastTotal)));
        }
        lastTotal = total;
        lastIdle = idle;
        return lastPct;
    }

    private static long kb(String meminfoLine) {
        return Long.parseLong(meminfoLine.replaceAll("[^0-9]", ""));
    }

    private static String line(String path) throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(path));
        try {
            return r.readLine();
        } finally {
            r.close();
        }
    }
}
