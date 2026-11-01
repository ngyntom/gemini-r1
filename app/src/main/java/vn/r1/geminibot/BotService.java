package vn.r1.geminibot;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Vòng lặp chính: chờ từ đánh thức -> ghi câu hỏi -> gửi Gemini -> đọc câu trả lời.
 *
 * Điều khiển qua Web UI: http://<IP của R1>:8088, hoặc qua adb, ví dụ:
 *   adb shell am startservice -n vn.r1.geminibot/.BotService --es api_key AIza...
 *   adb shell am startservice -n vn.r1.geminibot/.BotService --es say "Xin chào"
 *   adb shell am startservice -n vn.r1.geminibot/.BotService --ez reset true
 * Xem log: adb logcat -s GeminiR1
 */
public class BotService extends Service {
    static final String TAG = "GeminiR1";
    private static final int TTS_RATE = 24000;
    private static final long WAKE_LISTEN_MS = 5000; // chờ câu hỏi sau tiếng bíp

    private final GeminiClient gemini = new GeminiClient();
    private final VoiceRecorder recorder = new VoiceRecorder();
    private final Object speakLock = new Object();
    private volatile boolean running;
    private volatile boolean paused;
    private Thread worker;
    private WebServer web;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        @SuppressWarnings("deprecation")
        Notification n = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Gemini R1")
                .setContentText("Đang lắng nghe")
                .build();
        startForeground(1, n);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        wakeLock.acquire();
        web = new WebServer(this);
        web.start();
        Led.setEnabled(Config.load(this).led);
        Led.start();
        new Thread(() -> Updater.reportLastInstall(this), "update-log").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Config.saveFromIntent(this, intent);
        if (intent != null && intent.getBooleanExtra("reset", false)) resetHistory();
        if (intent != null && intent.hasExtra("say")) sayAsync(intent.getStringExtra("say"));
        if (worker == null) {
            running = true;
            worker = new Thread(this::loop, "gemini-loop");
            worker.start();
        }
        return START_STICKY;
    }

    // ---- Điều khiển từ Web UI ----

    void sayAsync(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        new Thread(() -> speak(Config.load(this), text)).start();
    }

    void askTextAsync(final String question) {
        if (question == null || question.trim().isEmpty()) return;
        new Thread(() -> {
            try {
                Config cfg = Config.load(this);
                BotLog.state = "thinking";
                BotLog.lastHeard = question;
                BotLog.i("Hỏi (Web): " + question);
                GeminiClient.Answer a = gemini.askText(cfg, question);
                BotLog.lastReply = a.reply;
                BotLog.i("Trả lời: " + a.reply);
                if (!a.reply.isEmpty()) speak(cfg, a.reply);
            } catch (Throwable e) {
                BotLog.e("Lỗi hỏi Gemini", e);
            } finally {
                if ("thinking".equals(BotLog.state)) BotLog.state = "idle";
            }
        }).start();
    }

    void triggerListen() {
        recorder.trigger();
    }

    void setPaused(boolean p) {
        paused = p;
        if (p) recorder.stop(); // thoát khỏi vòng chờ từ đánh thức ngay
        BotLog.i(p ? "Tạm dừng nghe (nhả micro)" : "Tiếp tục nghe");
    }

    byte[] recentMicWav() {
        return recorder.recentWav();
    }

    boolean isPaused() {
        return paused;
    }

    void resetHistory() {
        gemini.clearHistory();
        BotLog.i("Đã xoá lịch sử hội thoại");
    }

    // ---- Vòng lặp chính ----

    private void loop() {
        WakeEngine wake = null;
        String wakeEngine = null;
        boolean followUp = false;
        try {
            while (running) {
                try {
                    Config cfg = Config.load(this);
                    if (cfg.apiKey.isEmpty()) {
                        BotLog.state = "no_key";
                        Thread.sleep(2000);
                        continue;
                    }
                    if (paused) {
                        BotLog.state = "paused";
                        recorder.close(); // nhả micro cho app khác
                        Thread.sleep(500);
                        continue;
                    }
                    if (wake == null || !cfg.wakeEngine.equals(wakeEngine)) {
                        if (wake != null) wake.close();
                        wake = "vosk".equals(cfg.wakeEngine) ? new VoskWakeWord(this)
                                : new MicroWakeWord(this);
                        wakeEngine = cfg.wakeEngine;
                        BotLog.i("Bộ nhận từ đánh thức: " + wakeEngine);
                    }
                    recorder.setGain(cfg.micGain);
                    Led.setEnabled(cfg.led);
                    recorder.ensureOpen();

                    byte[] wav;
                    if (followUp && cfg.followUpSec > 0) {
                        followUp = false;
                        BotLog.state = "listening";
                        wav = recorder.recordUtterance(cfg.followUpSec * 1000L);
                        if (wav == null) continue; // không ai nói tiếp -> quay lại chờ từ đánh thức
                    } else {
                        followUp = false;
                        wake.configure(cfg);
                        BotLog.state = "idle";
                        String hit = wake.waitFor(recorder);
                        if (hit == null) {
                            Thread.sleep(50);
                            continue;
                        }
                        BotLog.i("Đánh thức: " + hit);
                        Tone.beepAsync();
                        BotLog.state = "listening";
                        wav = recorder.recordUtterance(WAKE_LISTEN_MS);
                        if (wav == null) {
                            BotLog.i("Không nghe thấy câu hỏi");
                            continue;
                        }
                    }
                    BotLog.state = "thinking";
                    BotLog.i("Thu được " + (wav.length - 44) / 32 + " ms audio, gửi Gemini...");

                    GeminiClient.Answer a = gemini.ask(cfg, wav);
                    BotLog.lastHeard = a.heard;
                    BotLog.i("Nghe: " + a.heard);
                    if (a.reply.isEmpty()) continue;
                    BotLog.lastReply = a.reply;
                    BotLog.i("Trả lời: " + a.reply);
                    recorder.pause();
                    try {
                        speak(cfg, a.reply);
                    } finally {
                        recorder.resume();
                    }
                    followUp = true;
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    // Bắt cả Error (vd. UnsatisfiedLinkError) để luồng không chết âm thầm
                    BotLog.state = "error";
                    BotLog.e("Lỗi", e);
                    recorder.close(); // mở lại mic ở vòng sau
                    if (e instanceof Error) {
                        if (wake != null) wake.close();
                        wake = null;
                        Thread.sleep(30000);
                    } else {
                        Thread.sleep(3000);
                    }
                }
            }
        } catch (InterruptedException ignored) {
        } finally {
            recorder.close();
            if (wake != null) wake.close();
        }
    }

    private void speak(Config cfg, String say) {
        synchronized (speakLock) {
            String prev = BotLog.state;
            BotLog.state = "speaking";
            try {
                PcmPlayer.play(synth(cfg, say), TTS_RATE);
            } catch (Throwable e) {
                BotLog.e("TTS lỗi", e);
            } finally {
                BotLog.state = prev;
            }
        }
    }

    /** Google Cloud TTS (nhanh); lỗi (vd. chưa bật API) thì dùng Gemini TTS. */
    private byte[] synth(Config cfg, String say) throws Exception {
        if ("google".equals(cfg.ttsEngine)) {
            try {
                long t0 = System.currentTimeMillis();
                byte[] pcm = GoogleTts.synth(cfg, say, TTS_RATE);
                BotLog.i("Google TTS: " + (System.currentTimeMillis() - t0) + " ms");
                return pcm;
            } catch (Exception e) {
                BotLog.e("Google TTS lỗi, dùng Gemini TTS", e);
            }
        }
        long t0 = System.currentTimeMillis();
        byte[] pcm = gemini.tts(cfg, say);
        BotLog.i("Gemini TTS: " + (System.currentTimeMillis() - t0) + " ms");
        return pcm;
    }

    @Override
    public void onDestroy() {
        running = false;
        recorder.stop();
        if (web != null) web.stop();
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
