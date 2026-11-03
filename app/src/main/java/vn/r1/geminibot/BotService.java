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
    /** Bot kết thúc câu trả lời bằng câu hỏi thì nghe câu đáp ít nhất chừng này giây. */
    private static final int ASK_BACK_SEC = 8;
    /** Im lặng đầu câu đáp "Dạ?" cho ampli thức dậy (loa ngắt ampli khi rảnh, giống LEAD_MS của Tone). */
    private static final int CUE_LEAD_MS = 500;

    private final GeminiClient gemini = new GeminiClient();
    private final GeminiLive live = new GeminiLive();
    private final VoiceRecorder recorder = new VoiceRecorder();
    private LocalAnswer local;
    private final Object speakLock = new Object();
    private volatile boolean running;
    private volatile boolean paused;
    private volatile boolean cutIn;
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
        Led.setMusicEnabled(Config.load(this).musicLed, Config.load(this).musicLedInner);
        Led.start(this);
        Button.start(this);
        Music.get(this); // dựng trình phát nhạc sẵn (hàng chờ cũ đọc lại từ file)
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
                String mus = MusicCommands.handle(this, question);
                if (mus != null) {
                    BotLog.lastReply = mus;
                    speak(cfg, mus);
                    Music.get(this).holdForBot(false);
                    return;
                }
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

    /** Web UI vừa lưu cấu hình: tắt Gemini Live thì kết thúc ngay phiên Live đang chạy. */
    /** Model từ đánh thức đang nạp (vòng chính ghi lúc configure) - khác cấu hình mới thì nạp lại ngay. */
    private volatile String lastWakeKey;

    private static String wakeKey(Config cfg) {
        return cfg.wakeEngine + "|" + cfg.mwwModel + "|" + cfg.mwwCutoff + "|" + cfg.wakeWord;
    }

    void configChanged() {
        Config cfg = Config.load(this);
        // Vòng chính chỉ nạp model từ đánh thức TRƯỚC mỗi lần chờ, mà lần chờ kéo dài tới khi nghe được từ CŨ:
        // đổi model trên web xong vẫn nghe từ cũ (đã gặp: chọn hey_google mà loa vẫn chờ "Alexa"). Báo bộ
        // nhận thoát ra để nạp ngay.
        if (lastWakeKey != null && !wakeKey(cfg).equals(lastWakeKey)) recorder.requestReload();
        Led.setEnabled(cfg.led); // vòng chính có thể đang chờ từ đánh thức rất lâu -> áp dụng ngay ở đây
        Led.setMusicEnabled(cfg.musicLed, cfg.musicLedInner);
        if (!cfg.live) live.cancel();
    }

    /** Bấm nút lúc bot đang nói (cờ cutIn để vòng chính nghe câu hỏi mới ngay sau đó). */
    void interruptSpeech() {
        cutIn = true;
        live.cutIn();
        PcmPlayer.cancel();
        BotLog.i("Ngắt lời bot (nút bấm)");
    }

    void setPaused(boolean p) {
        boolean changed = paused != p;
        paused = p;
        if (p) recorder.stop(); // thoát khỏi vòng chờ từ đánh thức ngay
        BotLog.i(p ? "Tạm dừng nghe (nhả micro)" : "Tiếp tục nghe");
        if (changed) announce(p ? "Đã tắt mic" : "Đã bật mic");
    }

    /** Câu báo ngắn đã tạo giọng (theo nội dung + bộ đọc), để bấm nút là nói ngay, không chờ mạng. */
    private final java.util.Map<String, PcmPlayer.Pcm> announceCache = new java.util.HashMap<>();

    /** Đọc câu báo ngắn (tắt / bật mic…) ở luồng riêng; lỗi tạo giọng thì chỉ ghi log. */
    void announce(final String text) {
        new Thread(() -> {
            Config cfg = Config.load(this);
            String key = cfg.ttsEngine + "|" + cfg.voice + "|" + cfg.googleVoice + "|" + text;
            try {
                PcmPlayer.Pcm p;
                synchronized (announceCache) {
                    p = announceCache.get(key);
                }
                if (p == null) {
                    p = synth(cfg, text);
                    synchronized (announceCache) {
                        announceCache.put(key, p);
                    }
                }
                synchronized (speakLock) {
                    PcmPlayer.play(withLead(p), p.rate);
                }
            } catch (Exception e) {
                BotLog.w("Không đọc được \"" + text + "\": " + e.getMessage());
            }
        }, "announce").start();
    }

    /** Đệm im lặng đầu cho ampli thức dậy (loa ngắt ampli khi rảnh, mất tiếng đầu câu). */
    private static byte[] withLead(PcmPlayer.Pcm p) {
        byte[] silence = new byte[p.rate * 2 * CUE_LEAD_MS / 1000];
        byte[] out = new byte[silence.length + p.data.length];
        System.arraycopy(p.data, 0, out, silence.length, p.data.length);
        return out;
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
        boolean barged = false; // vừa ngắt lời bot -> nghe câu hỏi mới ngay, không chờ từ đánh thức
        boolean askedBack = false; // câu trả lời kết thúc bằng câu hỏi
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
                        PcmPlayer.keepAwake(false);
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
                    // Bot vừa hỏi lại ("…bạn muốn nghe tin gì không?") thì luôn nghe câu đáp, kể cả khi tắt nghe tiếp
                    int followSec = askedBack ? Math.max(cfg.followUpSec, ASK_BACK_SEC) : cfg.followUpSec;
                    askedBack = false;
                    if (followUp && followSec > 0) {
                        followUp = false;
                        BotLog.state = "listening";
                        wav = recorder.recordUtterance(followSec * 1000L);
                        if (wav == null) continue; // không ai nói tiếp -> quay lại chờ từ đánh thức
                    } else {
                        followUp = false;
                        wake.configure(cfg);
                        lastWakeKey = wakeKey(cfg);
                        BotLog.state = "idle";
                        if (!barged) {
                            PcmPlayer.keepAwake(false); // rảnh: cho ampli nghỉ
                            Music.get(this).holdForBot(false); // trả lời xong: nhạc phát tiếp
                        }
                        String hit = barged ? "ngắt lời" : wake.waitFor(recorder);
                        barged = false;
                        if (hit != null) {
                            Music.get(this).holdForBot(true); // tạm dừng nhạc để nghe rõ câu hỏi
                            PcmPlayer.keepAwake(true); // giữ ampli thức tới lúc trả lời xong
                        }
                        if (hit == null) {
                            Thread.sleep(50);
                            continue;
                        }
                        BotLog.i("Đánh thức: " + hit);
                        // Vòng chờ từ đánh thức có thể kéo dài nhiều phút: đọc lại cấu hình để đổi Live / thường,
                        // âm báo… trên Web UI trong lúc đó có tác dụng ngay lần gọi này
                        cfg = Config.load(this);
                        // wake() TRƯỚC khi đổi trạng thái để luồng đèn thấy "listening" là thấy luôn cờ quét sáng.
                        // "web" = nút "Nghe ngay" trên Web UI, không phải nghe thấy từ khóa nên không quét.
                        if (!"web".equals(hit)) Led.wake();
                        BotLog.state = "listening";
                        // Gemini Live luôn dùng câu đáp thay cho ding-dong (trừ khi đặt im lặng): mic tắt
                        // hẳn lúc bot nói "Dạ?" nên không phải bỏ đoạn ding-dong, nghe "Dạ?" xong nói ngay
                        // là được đầy đủ chữ — nói đè lên ding-dong thì âm đầu rơi vào đoạn bị bỏ
                        // (xem cueGuardFrames trong GeminiLive).
                        final String cue = cfg.live && !"none".equals(cfg.wakeSound) ? "voice" : cfg.wakeSound;
                        if ("voice".equals(cue)) {
                            // Câu đáp dài -> phát xong rồi mới thu, bỏ phần mic đã thu lúc phát
                            recorder.pause();
                            try {
                                playWakeCue(cfg, cue);
                            } finally {
                                recorder.resume();
                            }
                        } else if (!"none".equals(cue)) {
                            // Ding-dong (0.37 s) phát song song, mic vẫn thu: người hay nói liền
                            // "Okay Nabu, mấy giờ rồi" nên tắt mic lúc này sẽ mất đầu câu hỏi.
                            // Tiếng ding-dong ngắn hơn MIN_SPEECH_FRAMES nên không bị tính là câu nói.
                            final Config c = cfg;
                            new Thread(() -> {
                                try {
                                    playWakeCue(c, cue);
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
                            barged = speakBargeIn(cfg, quick, wake);
                            followUp = !barged;
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
                            String mus = MusicCommands.handle(this, text);
                            if (mus != null) {
                                // Lệnh nhạc: trả lời ngắn rồi về chờ từ đánh thức (nhạc phát tiếp ở đó)
                                BotLog.lastHeard = text;
                                BotLog.lastReply = mus;
                                barged = speakBargeIn(cfg, mus, wake);
                                followUp = false;
                                continue;
                            }
                            String quick = cfg.localTime ? LocalAnswer.answer(text) : null;
                            if (quick != null) {
                                BotLog.lastHeard = text;
                                BotLog.lastReply = quick;
                                BotLog.i("Trả lời tại loa: " + quick);
                                barged = speakBargeIn(cfg, quick, wake);
                                followUp = !barged;
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
                    barged = speakBargeIn(cfg, a.reply, wake);
                    followUp = !barged;
                    askedBack = a.reply.trim().endsWith("?");
                    if (askedBack && !barged) BotLog.i("Bot hỏi lại -> nghe câu đáp " + Math.max(cfg.followUpSec, ASK_BACK_SEC) + " s");
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
            PcmPlayer.keepAwake(false);
            recorder.close();
            if (wake != null) wake.close();
        }
    }

    /**
     * Đọc câu trả lời của vòng chính. Bật "ngắt lời" thì mic vẫn mở và bộ nhận từ đánh thức vẫn chạy
     * trong lúc đọc để người dùng gọi từ đánh thức cắt ngang; tắt thì tạm dừng mic như cũ (loa không
     * khử tiếng vọng, mic thu lại giọng bot). Nút bấm ngắt được trong cả hai trường hợp (interruptSpeech).
     * Trả về true khi bị ngắt -> vòng sau nghe câu hỏi mới ngay.
     */
    private boolean speakBargeIn(final Config cfg, final String say, WakeEngine wake) throws InterruptedException {
        cutIn = false;
        if (!cfg.bargeIn || wake == null) {
            recorder.pause();
            try {
                speak(cfg, say);
            } finally {
                recorder.resume();
            }
            return cutIn;
        }
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread t = new Thread(() -> {
            try {
                speak(cfg, say);
            } finally {
                done.set(true);
                recorder.trigger(); // cho waitFor() thoát khi đọc xong
            }
        }, "speak");
        t.start();
        String hit = null;
        boolean finished = false;
        try {
            hit = wake.waitFor(recorder);
            finished = done.get();
        } finally {
            if (!done.get()) PcmPlayer.cancel();
            t.join();
            recorder.takeTrigger(); // bỏ trigger luồng đọc để lại khi bị ngắt
        }
        if (hit == null) return false; // mic bị dừng (tạm dừng nghe / tắt app)
        if (!finished && !cutIn) BotLog.i("Ngắt lời bot: " + ("web".equals(hit) ? "nút Nghe ngay" : hit));
        return cutIn || !finished;
    }

    private void speak(Config cfg, String say) {
        synchronized (speakLock) {
            String prev = BotLog.state;
            BotLog.state = "speaking";
            try {
                int gen = PcmPlayer.generation(); // bị ngắt lúc đang tạo giọng thì không phát nữa
                PcmPlayer.Pcm p = synth(cfg, say);
                // Ampli chưa được giữ thức (vd. đọc từ Web UI) -> đệm im lặng đầu cho ampli kịp bật
                PcmPlayer.play(PcmPlayer.awake() ? p.data : withLead(p), p.rate, gen);
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
    private void playWakeCue(Config cfg, String cue) throws InterruptedException {
        if ("none".equals(cue)) return;
        if ("voice".equals(cue)) {
            try {
                if (cueVoice == null || !cfg.wakeReply.equals(cueText)) {
                    PcmPlayer.Pcm p = synth(cfg, cfg.wakeReply);
                    // Đệm im lặng đầu cho ampli thức dậy: Tone.java tự có sẵn cho ding-dong,
                    // câu TTS thì không -> không đệm là mất tiếng "D..." đầu lúc loa ngủ lâu
                    byte[] silence = new byte[p.rate * 2 * CUE_LEAD_MS / 1000];
                    byte[] withLead = new byte[silence.length + p.data.length];
                    System.arraycopy(silence, 0, withLead, 0, silence.length);
                    System.arraycopy(p.data, 0, withLead, silence.length, p.data.length);
                    cueVoice = new PcmPlayer.Pcm(withLead, p.rate);
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
