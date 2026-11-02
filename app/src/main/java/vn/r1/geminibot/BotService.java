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
    private static final long WAKE_LISTEN_MS = 7000; // chờ câu hỏi sau tiếng bíp

    private final GeminiClient gemini = new GeminiClient();
    private final GeminiLive live = new GeminiLive();
    private final VoiceRecorder recorder = new VoiceRecorder();
    private LocalAnswer local;
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
        local = new LocalAnswer(this);
        Led.setEnabled(Config.load(this).led);
        Led.start(this);
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
                String quick = cfg.localTime ? LocalAnswer.answer(question) : null;
                if (quick != null) {
                    BotLog.lastReply = quick;
                    BotLog.i("Trả lời tại loa: " + quick);
                    speak(cfg, quick);
                    return;
                }
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
                    // Vosk nghe câu hỏi giờ chỉ là dự phòng khi không có Google STT: nó nghe sai nhiều
                    // ("mình giờ", "hôm bạn là"…), còn Google STT cho chữ đúng để trả lời tại loa
                    boolean voskAsk = cfg.localTime && !GoogleStt.usable(cfg);
                    if (voskAsk) local.loadAsync();
                    recorder.setSink(voskAsk && local.ready() ? local : null);
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
                        BotLog.state = "listening";
                        if ("voice".equals(cfg.wakeSound)) {
                            // Câu đáp dài -> phát xong rồi mới thu, bỏ phần mic đã thu lúc phát
                            recorder.pause();
                            try {
                                playWakeCue(cfg);
                            } finally {
                                recorder.resume();
                            }
                        } else {
                            // Ding-dong (0.37 s) phát song song, mic vẫn thu: người hay nói liền
                            // "Okay Nabu, mấy giờ rồi" nên tắt mic lúc này sẽ mất đầu câu hỏi.
                            // Tiếng ding-dong ngắn hơn MIN_SPEECH_FRAMES nên không bị tính là câu nói.
                            final Config c = cfg;
                            new Thread(() -> {
                                try {
                                    playWakeCue(c);
                                } catch (InterruptedException ignored) {
                                }
                            }, "wake-cue").start();
                        }
                        if (cfg.live) {
                            live.run(cfg, recorder); // trò chuyện liên tục tới khi im lặng / tạm biệt
                            continue;
                        }
                        wav = recorder.recordUtterance(WAKE_LISTEN_MS);
                        if (wav == null) {
                            BotLog.i("Không nghe thấy câu hỏi");
                            continue;
                        }
                    }
                    if (voskAsk && local.ready()) {
                        String heard = local.heard();
                        String quick = LocalAnswer.answer(heard);
                        if (quick != null) {
                            BotLog.lastHeard = heard;
                            BotLog.lastReply = quick;
                            BotLog.i("Nghe (tại loa): " + heard + " -> " + quick);
                            recorder.pause();
                            try {
                                speak(cfg, quick);
                            } finally {
                                recorder.resume();
                            }
                            followUp = true;
                            continue;
                        }
                        if (!heard.isEmpty()) BotLog.i("Vosk nghe: " + heard + " (không phải hỏi giờ)");
                    }
                    BotLog.state = "thinking";
                    // Đọc lại cấu hình: vòng chờ từ đánh thức có thể đã kéo dài nhiều phút, cấu hình lưu từ
                    // Web UI trong lúc đó (vd. service account) phải có tác dụng ngay với câu hỏi này
                    cfg = Config.load(this);
                    GeminiClient.Answer a = null;
                    if (GoogleStt.usable(cfg)) {
                        // Đổi giọng nói thành chữ trước: câu đơn giản trả lời tại loa, còn lại gửi chữ (nhanh hơn audio)
                        long t0 = System.currentTimeMillis();
                        try {
                            String text = GoogleStt.recognize(cfg, wav);
                            BotLog.i("Google STT (" + (System.currentTimeMillis() - t0) + " ms): " + text);
                            if (text.isEmpty()) {
                                BotLog.i("Không nghe rõ lời");
                                continue;
                            }
                            String quick = cfg.localTime ? LocalAnswer.answer(text) : null;
                            if (quick != null) {
                                BotLog.lastHeard = text;
                                BotLog.lastReply = quick;
                                BotLog.i("Trả lời tại loa: " + quick);
                                recorder.pause();
                                try {
                                    speak(cfg, quick);
                                } finally {
                                    recorder.resume();
                                }
                                followUp = true;
                                continue;
                            }
                            a = gemini.askText(cfg, text);
                            if (a.heard.isEmpty()) a.heard = text;
                        } catch (InterruptedException e) {
                            throw e;
                        } catch (Exception e) {
                            String m = String.valueOf(e.getMessage());
                            BotLog.w((m.length() > 200 ? m.substring(0, 200) + "…" : m));
                        }
                    }
                    if (a == null) {
                        BotLog.i("Thu được " + (wav.length - 44) / 32 + " ms audio, gửi Gemini...");
                        a = gemini.ask(cfg, wav);
                    }
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
                PcmPlayer.Pcm p = synth(cfg, say);
                PcmPlayer.play(p.data, p.rate);
            } catch (Throwable e) {
                BotLog.e("TTS lỗi", e);
            } finally {
                BotLog.state = prev;
            }
        }
    }

    private String cueText;
    private PcmPlayer.Pcm cueVoice;

    /** chime = "ding-dong", voice = đọc câu đáp (vd. "Dạ?") một lần rồi giữ lại, none = im lặng. */
    private void playWakeCue(Config cfg) throws InterruptedException {
        if ("none".equals(cfg.wakeSound)) return;
        if ("voice".equals(cfg.wakeSound)) {
            try {
                if (cueVoice == null || !cfg.wakeReply.equals(cueText)) {
                    cueVoice = synth(cfg, cfg.wakeReply);
                    cueText = cfg.wakeReply;
                }
                PcmPlayer.play(cueVoice.data, cueVoice.rate);
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                BotLog.w("Không tạo được câu đáp, dùng ding-dong: " + e.getMessage());
            }
        }
        PcmPlayer.play(Tone.CHIME, Tone.RATE);
    }

    /**
     * gfree = giọng Google Dịch (miễn phí), google = Cloud TTS (cần key Cloud), gemini = Gemini TTS.
     * Lỗi thì lùi về Gemini TTS. Cloud TTS tự chỉnh tốc độ, các bộ còn lại dùng TimeStretch.
     */
    private PcmPlayer.Pcm synth(Config cfg, String say) throws Exception {
        long t0 = System.currentTimeMillis();
        if ("google".equals(cfg.ttsEngine) || "gfree".equals(cfg.ttsEngine)) {
            try {
                PcmPlayer.Pcm p;
                if ("google".equals(cfg.ttsEngine)) {
                    p = new PcmPlayer.Pcm(GoogleTts.synth(cfg, say, TTS_RATE), TTS_RATE);
                } else {
                    p = FreeGoogleTts.synth(getCacheDir(), say);
                    p = new PcmPlayer.Pcm(TimeStretch.apply(p.data, p.rate, cfg.ttsRate), p.rate);
                }
                BotLog.i(cfg.ttsEngine + " TTS: " + (System.currentTimeMillis() - t0) + " ms");
                return p;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                BotLog.w("TTS " + cfg.ttsEngine + " lỗi, dùng Gemini TTS: "
                        + (m.length() > 160 ? m.substring(0, 160) + "…" : m));
                t0 = System.currentTimeMillis();
            }
        }
        byte[] pcm = TimeStretch.apply(gemini.tts(cfg, say), TTS_RATE, cfg.ttsRate);
        BotLog.i("Gemini TTS: " + (System.currentTimeMillis() - t0) + " ms");
        return new PcmPlayer.Pcm(pcm, TTS_RATE);
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
