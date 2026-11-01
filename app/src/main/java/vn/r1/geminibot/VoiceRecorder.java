package vn.r1.geminibot;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;

/**
 * Giữ micro mở liên tục (16 kHz mono), cung cấp từng frame 30 ms cho bộ nhận
 * từ đánh thức, và thu một câu nói bằng VAD năng lượng đơn giản.
 */
public class VoiceRecorder {
    static final int RATE = 16000;
    static final int FRAME = 480;                      // 30 ms
    private static final int PREROLL_FRAMES = 10;      // giữ 300 ms trước khi bắt đầu nói
    private static final int START_FRAMES = 3;         // 90 ms to liên tục mới tính là nói
    private static final int END_SILENCE_FRAMES = 40;  // im 1.2 s thì kết thúc
    private static final int MAX_FRAMES = 333;         // tối đa ~10 s
    private static final int MIN_SPEECH_FRAMES = 15;   // bỏ tiếng động ngắn < 450 ms
    // Micro của R1 thu nhỏ (nền ~25 RMS) -> ngưỡng tối thiểu thấp, phần còn lại theo mức ồn nền
    private static final double MIN_THRESHOLD = 120;

    /** Nhận các frame của câu đang thu (vd. Vosk nghe song song). */
    interface FrameSink {
        void begin();

        void frame(short[] f);
    }

    private volatile FrameSink sink;
    private AudioRecord rec;
    private int channels = 1;
    private double noise = 200;
    private volatile boolean stopped;
    private volatile boolean triggered;
    private volatile float gain = 1f;

    /** Yêu cầu nghe ngay (từ Web UI), bỏ qua từ đánh thức. */
    public void trigger() {
        triggered = true;
    }

    /** Bộ nhận từ đánh thức gọi mỗi frame; true một lần sau mỗi trigger(). */
    public boolean takeTrigger() {
        if (!triggered) return false;
        triggered = false;
        return true;
    }

    public synchronized void ensureOpen() {
        if (rec != null) return;
        stopped = false;
        // HAL của R1 báo "channel is not support" với mono -> thử mono trước, không được thì stereo rồi trộn
        AudioRecord r = open(AudioFormat.CHANNEL_IN_MONO);
        channels = 1;
        if (r == null) {
            r = open(AudioFormat.CHANNEL_IN_STEREO);
            channels = 2;
        }
        if (r == null) throw new IllegalStateException("Không mở được micro (app khác còn giữ mic?)");
        r.startRecording();
        if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            r.release();
            throw new IllegalStateException("Micro không bắt đầu thu được");
        }
        rec = r;
        BotLog.i("Đã mở micro (" + (channels == 1 ? "mono" : "stereo") + ")");
    }

    private static AudioRecord open(int channelMask) {
        int minBuf = AudioRecord.getMinBufferSize(RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return null;
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, FRAME * 4 * 20));
        if (r.getState() != AudioRecord.STATE_INITIALIZED) {
            r.release();
            return null;
        }
        return r;
    }

    /** Tạm dừng khi loa đang nói để không tự nghe lại chính mình; bỏ luôn audio cũ. */
    public synchronized void pause() {
        if (rec != null) rec.stop();
    }

    public synchronized void resume() {
        if (rec != null) rec.startRecording();
    }

    public void stop() {
        stopped = true;
    }

    public synchronized void close() {
        stopped = true;
        if (rec != null) {
            rec.stop();
            rec.release();
            rec = null;
        }
    }

    /** Đọc đủ một frame 30 ms. Trả về null nếu mic bị dừng / lỗi. */
    public short[] readFrame() {
        AudioRecord r = rec;
        if (r == null || stopped) return null;
        int want = FRAME * channels;
        short[] buf = new short[want];
        int n = 0;
        while (n < want) {
            int got = r.read(buf, n, want - n);
            if (got <= 0 || stopped) return null;
            n += got;
        }
        short[] mono = buf;
        if (channels != 1) {
            mono = new short[FRAME];
            for (int i = 0; i < FRAME; i++) mono[i] = (short) ((buf[2 * i] + buf[2 * i + 1]) / 2);
        }
        if (gain != 1f) {
            for (int i = 0; i < FRAME; i++) {
                mono[i] = (short) Math.max(-32768, Math.min(32767, Math.round(mono[i] * gain)));
            }
        }
        recent(mono);
        return mono;
    }

    void setSink(FrameSink s) {
        sink = s;
    }

    void setGain(float g) {
        gain = g;
    }

    // ---- Chẩn đoán: mức micro và 5 s âm thanh gần nhất ----

    private static final int RECENT_FRAMES = 166;
    private final short[][] ring = new short[RECENT_FRAMES][];
    private int ringPos, levelFrames, levelPeak;

    private synchronized void recent(short[] f) {
        ring[ringPos] = f;
        ringPos = (ringPos + 1) % RECENT_FRAMES;
        levelPeak = Math.max(levelPeak, (int) rms(f));
        if (++levelFrames >= 33) {
            BotLog.micLevel = levelPeak;
            levelPeak = 0;
            levelFrames = 0;
        }
    }

    /** 5 giây micro vừa thu (sau khuếch đại), dạng WAV. */
    synchronized byte[] recentWav() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < RECENT_FRAMES; i++) {
            short[] f = ring[(ringPos + i) % RECENT_FRAMES];
            if (f != null) writeFrame(out, f);
        }
        return Wav.wrap(out.toByteArray(), RATE);
    }

    /** Cập nhật mức ồn nền khi đang im lặng (gọi trong lúc chờ từ đánh thức). */
    public void trackNoise(short[] frame) {
        double v = rms(frame);
        if (v < threshold()) noise = noise * 0.95 + v * 0.05;
    }

    /**
     * Thu một câu nói, trả về WAV. Trả về null nếu không ai bắt đầu nói
     * trong startTimeoutMs, hoặc mic bị dừng.
     */
    public byte[] recordUtterance(long startTimeoutMs) {
        int waitLimit = (int) (startTimeoutMs / 30);
        int waited = 0, loud = 0, silent = 0, frames = 0;
        ArrayDeque<short[]> preroll = new ArrayDeque<>();
        ByteArrayOutputStream out = null;

        while (!stopped) {
            short[] buf = readFrame();
            if (buf == null) return null;
            double v = rms(buf);
            double th = threshold();

            if (out == null) {
                if (++waited > waitLimit) return null;
                preroll.addLast(buf);
                if (preroll.size() > PREROLL_FRAMES) preroll.removeFirst();
                if (v > th) {
                    loud++;
                } else {
                    loud = 0;
                    noise = noise * 0.95 + v * 0.05;
                }
                if (loud >= START_FRAMES) {
                    out = new ByteArrayOutputStream();
                    FrameSink sk = sink;
                    if (sk != null) sk.begin();
                    for (short[] f : preroll) {
                        writeFrame(out, f);
                        if (sk != null) sk.frame(f);
                    }
                    frames = preroll.size();
                    silent = 0;
                }
            } else {
                writeFrame(out, buf);
                FrameSink sk = sink;
                if (sk != null) sk.frame(buf);
                frames++;
                silent = v < th * 0.7 ? silent + 1 : 0;
                if (silent >= END_SILENCE_FRAMES || frames >= MAX_FRAMES) {
                    if (frames - silent >= MIN_SPEECH_FRAMES) return Wav.wrap(out.toByteArray(), RATE);
                    // Chỉ là tiếng động ngắn, nghe tiếp
                    out = null;
                    loud = 0;
                    preroll.clear();
                }
            }
        }
        return null;
    }

    private double threshold() {
        return Math.max(noise * 3, MIN_THRESHOLD);
    }

    static double rms(short[] s) {
        double sum = 0;
        for (short v : s) sum += (double) v * v;
        return Math.sqrt(sum / s.length);
    }

    private static void writeFrame(ByteArrayOutputStream out, short[] f) {
        for (short v : f) {
            out.write(v & 0xff);
            out.write((v >> 8) & 0xff);
        }
    }
}
