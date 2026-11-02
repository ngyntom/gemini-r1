package vn.r1.geminibot;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

/**
 * Đèn theo nhạc: viền sáng nhiều / ít đèn theo độ to của nhạc (kiểu thanh VU, sáng đối xứng từ phía trước).
 *
 * App không ghi được /sys nên làm 2 nửa: app đo độ to PCM (từ ExoPlayer hoặc nhạc nhóm), trễ đúng lúc
 * tiếng ra loa rồi ghi số đèn (0..24) vào 1 file; script nền chạy bằng shell của adbd (Led) đọc file đó
 * (lệnh "read" có sẵn trong sh, không tốn tiến trình) và chỉ ghi driver đèn khi số đèn đổi.
 */
final class MusicLight {
    static final int RING = 24;
    private static File levelFile;
    /** (thời điểm ra loa theo System.nanoTime, số đèn) chờ tới lúc. */
    private static final ArrayDeque<long[]> pending = new ArrayDeque<>();
    private static double peak = 3000;    // mức to nhất gần đây (tự co giãn theo bài)
    private static double smooth;
    private static volatile int shown = -1;
    private static volatile long lastFeed;

    private MusicLight() {}

    static void init(Context ctx) {
        File dir = ctx.getExternalCacheDir();
        if (dir == null) return;
        levelFile = new File(dir, "music_level");
        Thread t = new Thread(MusicLight::loop, "music-light");
        t.setDaemon(true);
        t.start();
    }

    static File levelFile() {
        return levelFile;
    }

    /** Có nhạc trong ~1 s gần đây (Led dùng để bật / tắt hiệu ứng). */
    static boolean active() {
        return System.nanoTime() - lastFeed < 1_000_000_000L;
    }

    /** PCM 16-bit xen kênh (ExoPlayer), ra loa sau khoảng delayNs. */
    static void feed(ByteBuffer pcm, int channels, long delayNs) {
        ByteBuffer b = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int n = b.remaining() / 2;
        if (n == 0) return;
        double sum = 0;
        for (int i = 0; i < n; i += Math.max(1, channels)) {
            double v = b.getShort(b.position() + i * 2);
            sum += v * v;
        }
        push(Math.sqrt(sum / Math.max(1, n / Math.max(1, channels))), System.nanoTime() + delayNs);
    }

    /** PCM mono đã gắn mốc lúc ra loa (nhạc nhóm). */
    static void feed(short[] mono, long playAtNs) {
        if (mono.length == 0) return;
        double sum = 0;
        for (int i = 0; i < mono.length; i += 2) sum += (double) mono[i] * mono[i];
        push(Math.sqrt(sum / ((mono.length + 1) / 2)), playAtNs);
    }

    private static synchronized void push(double rms, long at) {
        lastFeed = System.nanoTime();
        peak = Math.max(rms, peak * 0.9995);          // co dần về mức to của đoạn đang nghe
        if (peak < 1500) peak = 1500;
        smooth = rms > smooth ? rms : smooth * 0.7 + rms * 0.3; // lên nhanh, xuống từ từ
        int level = (int) Math.round(Math.min(1.0, smooth / peak) * RING);
        if (pending.size() > 200) pending.pollFirst();
        pending.addLast(new long[]{at, level});
    }

    private static void loop() {
        while (true) {
            try {
                Thread.sleep(40);
                int level = -1;
                synchronized (MusicLight.class) {
                    long now = System.nanoTime();
                    while (!pending.isEmpty() && pending.peekFirst()[0] <= now) level = (int) pending.pollFirst()[1];
                }
                if (!active()) level = 0;
                if (level < 0 || level == shown || levelFile == null) continue;
                shown = level;
                FileOutputStream out = new FileOutputStream(levelFile);
                out.write((level + "\n").getBytes("UTF-8"));
                out.close();
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) {
            }
        }
    }
}
