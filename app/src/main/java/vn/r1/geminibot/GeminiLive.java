package vn.r1.geminibot;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Chế độ trò chuyện liên tục qua Gemini Live API (WebSocket BidiGenerateContent):
 * mic 16 kHz gửi liên tục, Gemini tự nhận biết lúc người dùng nói xong và trả lời bằng giọng
 * (PCM 24 kHz) phát ngay khi tới. Kết thúc khi im lặng liveIdleSec giây hoặc người dùng chào tạm biệt
 * (Gemini gọi hàm end_conversation).
 *
 * R1 không có khử tiếng vọng: lúc loa đang nói thì gửi khoảng lặng thay cho mic, nếu không Gemini
 * tự nghe lại chính mình. Vì vậy không nói chen ngang được khi loa đang nói.
 */
final class GeminiLive {
    private static final String URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final int OUT_RATE = 24000;
    private static final int SEND_FRAMES = 3;          // gửi mỗi 90 ms
    private static final long ECHO_GUARD_MS = 700;     // mic còn tắt thêm chừng này sau khi loa nói xong (đệm AudioTrack ~0.5 s)
    private static final long CUE_GUARD_MS = 900;      // bỏ tiếng ding-dong lúc mới vào

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private volatile boolean setupDone, closed, endRequested, userSpoke;
    private volatile String error;
    private volatile long lastActivity;
    private final StringBuilder heard = new StringBuilder();
    private final StringBuilder said = new StringBuilder();
    private Player player;
    private Set<String> deviceIds;
    private Config cfg;
    private WebSocket ws;

    /** Chạy một phiên trò chuyện, trả về khi phiên kết thúc. */
    void run(Config cfg, VoiceRecorder rec) throws InterruptedException {
        this.cfg = cfg;
        setupDone = closed = endRequested = userSpoke = false;
        error = null;
        heard.setLength(0);
        said.setLength(0);
        deviceIds = new HashSet<>();
        String devices = null;
        if (cfg.haEnabled()) {
            try {
                devices = HomeAssistant.entities(cfg, deviceIds);
            } catch (Exception e) {
                BotLog.w("Live: không lấy được thiết bị Home Assistant: " + e.getMessage());
            }
        }

        final CountDownLatch ready = new CountDownLatch(1);
        player = new Player();
        long start = System.currentTimeMillis();
        try {
            final String setup = setupJson(devices).toString();
            Request req = new Request.Builder().url(URL).header("x-goog-api-key", cfg.apiKey).build();
            ws = http.newWebSocket(req, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket w, Response r) {
                    w.send(setup);
                }

                @Override
                public void onMessage(WebSocket w, String text) {
                    handle(text, ready);
                }

                @Override
                public void onMessage(WebSocket w, ByteString bytes) {
                    handle(bytes.utf8(), ready); // Live API gửi JSON trong frame nhị phân
                }

                @Override
                public void onClosing(WebSocket w, int code, String reason) {
                    if (code != 1000) error = "Gemini Live đóng kết nối: " + code + " " + reason;
                    w.close(1000, null);
                    closed = true;
                    ready.countDown();
                }

                @Override
                public void onClosed(WebSocket w, int code, String reason) {
                    closed = true;
                    ready.countDown();
                }

                @Override
                public void onFailure(WebSocket w, Throwable t, Response r) {
                    error = "Gemini Live lỗi: " + t.getMessage() + (r != null ? " (HTTP " + r.code() + ")" : "");
                    closed = true;
                    ready.countDown();
                }
            });

            if (!ready.await(10, TimeUnit.SECONDS) || !setupDone) {
                throw new Exception(error != null ? error : "Gemini Live không phản hồi");
            }
            BotLog.i("Gemini Live: bắt đầu trò chuyện (" + cfg.liveModel + ")");
            lastActivity = System.currentTimeMillis();

            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            int frames = 0;
            while (!closed && !endRequested) {
                short[] f = rec.readFrame();
                if (f == null) break;
                long now = System.currentTimeMillis();
                boolean muted = now - start < CUE_GUARD_MS || player.busyUntil() + ECHO_GUARD_MS > now;
                for (short v : f) {
                    int s = muted ? 0 : v;
                    chunk.write(s & 0xff);
                    chunk.write((s >> 8) & 0xff);
                }
                if (++frames >= SEND_FRAMES) {
                    ws.send(new JSONObject().put("realtimeInput", new JSONObject().put("audio", new JSONObject()
                            .put("data", Base64.encodeToString(chunk.toByteArray(), Base64.NO_WRAP))
                            .put("mimeType", "audio/pcm;rate=" + VoiceRecorder.RATE))).toString());
                    chunk.reset();
                    frames = 0;
                }
                // Đèn: loa đang nói -> speaking; người dùng vừa nói, chờ trả lời -> thinking; còn lại -> listening
                if (player.busy()) BotLog.state = "speaking";
                else BotLog.state = userSpoke ? "thinking" : "listening";

                long idleFrom = Math.max(lastActivity, player.busyUntil());
                if (!userSpoke && now - idleFrom > cfg.liveIdleSec * 1000L) {
                    BotLog.i("Gemini Live: im lặng " + cfg.liveIdleSec + " s, kết thúc");
                    break;
                }
            }
            // Chào tạm biệt: audio câu chào có thể tới sau lệnh end_conversation -> chờ thêm rồi chờ nói hết
            long endAt = System.currentTimeMillis();
            while (endRequested && !closed && System.currentTimeMillis() < endAt + 8000
                    && (player.busy() || System.currentTimeMillis() < endAt + 1500)) {
                BotLog.state = player.busy() ? "speaking" : "thinking";
                Thread.sleep(100);
            }
            if (error != null) BotLog.w(error);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            BotLog.state = "error";
            BotLog.w(String.valueOf(e.getMessage()));
            Thread.sleep(1500);
        } finally {
            if (ws != null) ws.close(1000, "bye");
            player.close();
            logTurn();
            BotLog.state = "idle";
            BotLog.i("Gemini Live: kết thúc");
        }
    }

    private JSONObject setupJson(String devices) throws Exception {
        JSONArray functions = new JSONArray()
                .put(new JSONObject().put("name", "end_conversation")
                        .put("description", "Kết thúc cuộc trò chuyện khi người dùng chào tạm biệt hoặc muốn dừng"));
        if (devices != null) {
            functions.put(new JSONObject().put("name", "ha_call")
                    .put("description", "Điều khiển một thiết bị trong nhà qua Home Assistant")
                    .put("parameters", new JSONObject()
                            .put("type", "OBJECT")
                            .put("properties", new JSONObject()
                                    .put("domain", new JSONObject().put("type", "STRING"))
                                    .put("service", new JSONObject().put("type", "STRING"))
                                    .put("entity_id", new JSONObject().put("type", "STRING"))
                                    .put("data_json", new JSONObject().put("type", "STRING")
                                            .put("description", "tham số thêm dạng JSON, vd. {\"brightness_pct\":50}"))
                                    .put("user_words", new JSONObject().put("type", "STRING")
                                            .put("description", GeminiClient.USER_WORDS_DESC)))
                            .put("required", new JSONArray().put("domain").put("service").put("entity_id")
                                    .put("user_words"))));
        }
        return new JSONObject().put("setup", new JSONObject()
                .put("model", "models/" + cfg.liveModel)
                .put("generationConfig", new JSONObject()
                        .put("responseModalities", new JSONArray().put("AUDIO"))
                        .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", cfg.voice)))))
                .put("systemInstruction", new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt(devices)))))
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", functions)))
                .put("inputAudioTranscription", new JSONObject())
                .put("outputAudioTranscription", new JSONObject())
                .put("realtimeInputConfig", new JSONObject().put("automaticActivityDetection",
                        new JSONObject().put("silenceDurationMs", 800))));
    }

    private String prompt(String devices) {
        StringBuilder sb = new StringBuilder(cfg.persona)
                .append("\n\n").append(GeminiClient.CAPABILITIES)
                .append("\n\nBạn đang trò chuyện bằng giọng nói qua một chiếc loa. Luôn nói tiếng Việt (trừ khi ")
                .append("người dùng nói ngôn ngữ khác), câu ngắn, tự nhiên. Thời gian hiện tại (giờ Việt Nam): ")
                .append(LocalAnswer.nowForPrompt())
                .append(". Khi người dùng chào tạm biệt hoặc muốn dừng (\"tạm biệt\", \"thôi nhé\", \"xong rồi\"), ")
                .append("chào lại thật ngắn rồi gọi hàm end_conversation.");
        if (devices != null) {
            sb.append("\n\nBạn điều khiển được thiết bị trong nhà qua Home Assistant (entity_id | tên | trạng thái):\n")
              .append(devices)
              .append("\nMuốn bật / tắt / chỉnh thiết bị thì gọi hàm ha_call với entity_id đúng trong danh sách, ")
              .append("chờ kết quả rồi mới nói đã làm xong; lỗi hoặc không tìm thấy thì nói rõ. ")
              .append(GeminiClient.DEVICE_RULES).append(' ')
              .append("Hỏi trạng thái thì trả lời theo danh sách.");
        }
        return sb.toString();
    }

    private void handle(String text, CountDownLatch ready) {
        try {
            JSONObject m = new JSONObject(text);
            if (m.has("setupComplete")) {
                setupDone = true;
                ready.countDown();
                return;
            }
            JSONObject sc = m.optJSONObject("serverContent");
            if (sc != null) {
                JSONObject it = sc.optJSONObject("inputTranscription");
                if (it != null && !it.optString("text").trim().isEmpty()) {
                    synchronized (heard) {
                        heard.append(it.optString("text"));
                    }
                    userSpoke = true;
                    lastActivity = System.currentTimeMillis();
                }
                JSONObject ot = sc.optJSONObject("outputTranscription");
                if (ot != null) {
                    synchronized (said) {
                        said.append(ot.optString("text"));
                    }
                }
                if (sc.optBoolean("interrupted")) player.flush();
                JSONObject turn = sc.optJSONObject("modelTurn");
                JSONArray parts = turn != null ? turn.optJSONArray("parts") : null;
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject data = parts.getJSONObject(i).optJSONObject("inlineData");
                    if (data == null) continue;
                    player.write(Base64.decode(data.getString("data"), Base64.DEFAULT));
                    userSpoke = false;
                    lastActivity = System.currentTimeMillis();
                }
                if (sc.optBoolean("turnComplete")) logTurn();
            }
            JSONObject tc = m.optJSONObject("toolCall");
            if (tc != null) {
                final JSONArray calls = tc.optJSONArray("functionCalls");
                // Gọi Home Assistant ở luồng riêng để không chặn luồng nhận audio
                new Thread(() -> answerTools(calls), "live-tool").start();
            }
            if (m.has("goAway")) BotLog.w("Gemini Live: máy chủ sắp đóng phiên");
        } catch (Exception e) {
            BotLog.w("Gemini Live: không đọc được tin nhắn: " + e.getMessage());
        }
    }

    private void answerTools(JSONArray calls) {
        try {
            JSONArray out = new JSONArray();
            for (int i = 0; calls != null && i < calls.length(); i++) {
                JSONObject fc = calls.getJSONObject(i);
                String name = fc.optString("name");
                JSONObject args = fc.optJSONObject("args");
                if (args == null) args = new JSONObject();
                JSONObject result;
                if ("end_conversation".equals(name)) {
                    endRequested = true;
                    result = new JSONObject().put("ok", true);
                } else if ("ha_call".equals(name)) {
                    try {
                        String dataJson = args.optString("data_json", "").trim();
                        result = HomeAssistant.call(cfg, deviceIds, args.optString("domain"), args.optString("service"),
                                args.optString("entity_id"), dataJson.isEmpty() ? null : new JSONObject(dataJson),
                                args.optString("user_words"));
                    } catch (Exception e) {
                        result = new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage()));
                    }
                    if (!result.optBoolean("ok")) BotLog.w("Home Assistant lỗi: " + result.optString("error"));
                } else {
                    result = new JSONObject().put("ok", false).put("error", "Không có hàm " + name);
                }
                out.put(new JSONObject().put("id", fc.optString("id")).put("name", name).put("response", result));
            }
            ws.send(new JSONObject().put("toolResponse", new JSONObject().put("functionResponses", out)).toString());
        } catch (Exception e) {
            BotLog.w("Gemini Live: lỗi trả kết quả hàm: " + e.getMessage());
        }
    }

    private void logTurn() {
        String h, s;
        synchronized (heard) {
            h = heard.toString().trim();
            heard.setLength(0);
        }
        synchronized (said) {
            s = said.toString().trim();
            said.setLength(0);
        }
        if (!h.isEmpty()) {
            BotLog.lastHeard = h;
            BotLog.i("Nghe (Live): " + h);
        }
        if (!s.isEmpty()) {
            BotLog.lastReply = s;
            BotLog.i("Trả lời (Live): " + s);
        }
    }

    /** Phát PCM 24 kHz theo từng đoạn, ước lượng lúc nào phát xong để tắt mic chống vọng. */
    private static final class Player {
        private final AudioTrack track;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final Thread thread;
        private volatile long busyUntil;
        private volatile boolean stop;

        Player() {
            int min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, OUT_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, OUT_RATE), AudioTrack.MODE_STREAM);
            track.play();
            thread = new Thread(() -> {
                while (!stop) {
                    try {
                        byte[] b = queue.poll(200, TimeUnit.MILLISECONDS);
                        if (b != null) track.write(b, 0, b.length & ~1);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "live-player");
            thread.start();
        }

        synchronized void write(byte[] pcm) {
            long now = System.currentTimeMillis();
            if (busyUntil < now) {
                // Ampli của R1 tắt sau một lúc im lặng và mất ~0.5 s để bật: đệm khoảng lặng trước câu mới
                if (now - busyUntil > 2000) {
                    queue.offer(new byte[OUT_RATE / 2 * 2]);
                    busyUntil = now + 500;
                } else {
                    busyUntil = now;
                }
            }
            busyUntil += pcm.length / 2 * 1000L / OUT_RATE;
            queue.offer(pcm);
        }

        long busyUntil() {
            return busyUntil;
        }

        boolean busy() {
            return busyUntil > System.currentTimeMillis();
        }

        /** Gemini báo bị ngắt lời: bỏ phần chưa phát. */
        synchronized void flush() {
            queue.clear();
            track.pause();
            track.flush();
            track.play();
            busyUntil = System.currentTimeMillis();
        }

        void close() {
            stop = true;
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            track.stop();
            track.release();
        }
    }
}
