package vn.r1.geminibot;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Phát PCM 16-bit mono ra loa, chặn cho tới khi phát xong hoặc bị cancel(). */
public final class PcmPlayer {
    /** Tăng mỗi lần cancel(); play() thấy khác giá trị lúc bắt đầu thì dừng ngay. */
    private static volatile int generation;

    private PcmPlayer() {}

    /** Ngắt câu đang phát (người dùng gọi từ đánh thức / bấm nút lúc bot đang nói). */
    public static void cancel() {
        generation++;
    }

    /** PCM 16-bit mono kèm tần số lấy mẫu. */
    public static final class Pcm {
        final byte[] data;
        final int rate;

        Pcm(byte[] data, int rate) {
            this.data = data;
            this.rate = rate;
        }
    }

    private static volatile Thread keeper;

    /**
     * Giữ đường phát của loa luôn "thức" bằng một luồng im lặng. Không có luồng phát nào thì Android cho
     * HAL âm thanh nghỉ sau ~3 s và ampli R1 tắt; lúc bật lại, ampli tăng tiếng từ từ nên đầu câu trả lời
     * nghe nhỏ rồi mới to. Bật từ lúc nghe thấy từ đánh thức tới khi quay lại chờ từ đánh thức.
     */
    static synchronized void keepAwake(boolean on) {
        if (on == (keeper != null)) return;
        if (!on) {
            keeper.interrupt();
            keeper = null;
            return;
        }
        keeper = new Thread(() -> {
            int rate = 16000;
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5), AudioTrack.MODE_STREAM);
            byte[] silence = new byte[rate / 10 * 2]; // 100 ms
            try {
                t.play();
                // write() chặn theo tốc độ phát nên vòng này gần như không tốn CPU
                while (!Thread.currentThread().isInterrupted()) t.write(silence, 0, silence.length);
            } finally {
                t.stop();
                t.release();
            }
        }, "amp-keep");
        keeper.start();
    }

    static boolean awake() {
        return keeper != null;
    }

    static int generation() {
        return generation;
    }

    public static void play(byte[] pcm, int rate) throws InterruptedException {
        play(pcm, rate, generation);
    }

    /** gen = generation() lấy TRƯỚC khi tạo giọng: bị ngắt trong lúc đang tạo giọng thì không phát nữa. */
    static void play(byte[] pcm, int rate, int gen) throws InterruptedException {
        if (pcm == null || pcm.length < 2 || gen != generation) return;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, rate), AudioTrack.MODE_STREAM);
        try {
            t.play();
            int len = pcm.length & ~1;
            int off = 0;
            while (off < len && gen == generation) {
                int w = t.write(pcm, off, Math.min(8192, len - off));
                if (w <= 0) break;
                off += w;
            }
            int totalFrames = len / 2;
            long deadline = System.currentTimeMillis() + 2000L + totalFrames * 1000L / rate;
            while (gen == generation && t.getPlaybackHeadPosition() < totalFrames
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            if (gen != generation) {
                // Bị ngắt: bỏ phần còn trong bộ đệm thay vì phát nốt
                t.pause();
                t.flush();
            }
        } finally {
            t.stop();
            t.release();
        }
    }
}
