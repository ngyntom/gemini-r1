package vn.r1.geminibot;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.AudioProcessor;
import com.google.android.exoplayer2.audio.DefaultAudioSink;
import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.audio.TeeAudioProcessor;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phát nhạc YouTube trên loa (ExoPlayer), hàng chờ / lặp / trộn / yêu thích / danh sách phát / lịch sử.
 * Cùng dạng dữ liệu và cùng tên API với Voicebot trên Pi (music.py / webui.py) để trang Nhạc của Pi
 * chạy gần như nguyên vẹn: hàng chờ {query, index, entries, goi_y, tron, goc, chu}, lặp "tat" |
 * "tat_ca" | "mot", trạng thái {state, title, elapsed, duration, buffered, id, lap, tron, chat_luong}.
 *
 * ExoPlayer chỉ được gọi từ luồng "music" của nó; trạng thái cho Web UI đọc từ bản chụp cập nhật mỗi 0,5 s.
 */
final class Music {
    private static final int MAX_LIST = 100, MAX_HISTORY = 50, MAX_PLAYLISTS = 50;
    private static Music instance;

    private final Context ctx;
    private final File dir;
    private final Handler h;
    private ExoPlayer player;

    private JSONObject queue;            // hàng chờ hiện tại (null = chưa phát gì)
    private volatile String state = "stopped";
    private volatile String title;
    private volatile String curId;
    private volatile double elapsed, duration, buffered;
    private volatile JSONObject quality;
    private int generation;              // tăng mỗi lần đổi bài: bỏ kết quả lấy link của bài cũ
    private int errorsInRow;
    private volatile int teeChannels;
    /** Bot đang nghe / nói: tạm dừng nhạc, xong thì phát tiếp (xem holdForBot). */
    private boolean heldForBot;

    static synchronized Music get(Context ctx) {
        if (instance == null) instance = new Music(ctx.getApplicationContext());
        return instance;
    }

    private Music(Context ctx) {
        this.ctx = ctx;
        dir = new File(ctx.getFilesDir(), "music");
        dir.mkdirs();
        HandlerThread t = new HandlerThread("music");
        t.start();
        h = new Handler(t.getLooper());
        h.post(() -> {
            // PCM sau giải mã chép ra cho nhóm loa (Group) trước khi vào AudioTrack của ExoPlayer
            final Group group = Group.get(ctx);
            TeeAudioProcessor tee = new TeeAudioProcessor(new TeeAudioProcessor.AudioBufferSink() {
                @Override
                public void flush(int sampleRateHz, int channelCount, int encoding) {
                    teeChannels = encoding == C.ENCODING_PCM_16BIT ? channelCount : 0;
                    group.onFlush(encoding == C.ENCODING_PCM_16BIT ? sampleRateHz : 0, channelCount, encoding);
                }

                @Override
                public void handleBuffer(ByteBuffer buffer) {
                    // Đèn theo nhạc: PCM ở đây ra loa sau ~0,3 s (đệm AudioTrack của ExoPlayer); phát theo
                    // nhóm thì Group tự báo đúng lúc ra loa
                    if (!group.active() && teeChannels > 0) MusicLight.feed(buffer, teeChannels, 300_000_000L);
                    group.onPcm(buffer);
                }
            });
            @SuppressWarnings("deprecation")
            final DefaultAudioSink sink = new DefaultAudioSink.Builder()
                    .setAudioProcessors(new AudioProcessor[]{tee}).build();
            RenderersFactory rf = (handler, video, audio, text, meta) -> new Renderer[]{
                    new MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audio, sink)};
            player = new ExoPlayer.Builder(ctx, rf).setLooper(h.getLooper()).build();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int s) {
                    if (s == Player.STATE_READY) errorsInRow = 0;
                    if (s == Player.STATE_ENDED) onEnded();
                }

                @Override
                public void onPlayerError(PlaybackException e) {
                    onError(e);
                }
            });
            tick();
        });
        queue = readJson("queue.json");
    }

    // ---------- Trạng thái ----------

    private void tick() {
        if (player != null && curId != null) {
            elapsed = player.getCurrentPosition() / 1000.0;
            buffered = player.getBufferedPosition() / 1000.0;
            long d = player.getDuration();
            if (d > 0) duration = d / 1000.0;
        }
        h.postDelayed(this::tick, 500);
    }

    synchronized JSONObject status() throws Exception {
        JSONObject q = queue != null ? queue : new JSONObject();
        JSONObject o = new JSONObject().put("lap", repeat()).put("volume", volume());
        if (curId == null || "stopped".equals(state)) {
            return o.put("state", "stopped").put("title", JSONObject.NULL)
                    .put("elapsed", JSONObject.NULL).put("duration", JSONObject.NULL);
        }
        o.put("state", state).put("title", title).put("elapsed", elapsed).put("id", curId)
                .put("tron", q.optBoolean("tron")).put("chu", q.optString("chu"))
                .put("buffered", buffered);
        if (duration > 0) o.put("duration", duration);
        else o.put("duration", JSONObject.NULL);
        if (quality != null) o.put("chat_luong", quality);
        String out = Group.get(ctx).targetName();
        if (out != null) o.put("noi_phat", out).put("cast", true);
        return o;
    }

    synchronized JSONObject queueJson() {
        return queue != null ? queue : new JSONObject();
    }

    boolean playing() {
        return "playing".equals(state);
    }

    // ---------- Phát ----------

    /** Phát bài index trong entries (web UI chọn bài / danh sách); ghi cả danh sách làm hàng chờ. */
    synchronized String playList(String query, JSONArray entries, int index, double startSec, boolean suggest,
                                 String owner) throws Exception {
        JSONArray clean = new JSONArray();
        for (int i = 0; i < entries.length() && clean.length() < MAX_LIST; i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null && YouTube.validId(e.optString("id"))) clean.put(slim(e));
        }
        if (index < 0 || index >= clean.length()) return "Bài hát không hợp lệ.";
        queue = new JSONObject().put("query", query).put("index", index).put("entries", clean)
                .put("goi_y", suggest).put("chu", owner == null ? "" : owner);
        saveQueue();
        start(startSec);
        return "Đang mở " + clean.getJSONObject(index).optString("title") + ".";
    }

    /** Lệnh giọng nói / ô "phát" nhanh: tìm rồi phát kết quả đầu, các kết quả sau làm hàng chờ. */
    String playSearch(String q) throws Exception {
        JSONArray r = YouTube.search(q, 20);
        if (r.length() == 0) return "Không tìm thấy bài " + q + ".";
        playList(q, r, 0, 0, true, "");
        return "Đang mở " + r.getJSONObject(0).optString("title") + ".";
    }

    private void start(final double startSec) throws Exception {
        final JSONObject e = current();
        if (e == null) return;
        final int gen = ++generation;
        final String id = e.optString("id");
        curId = id;
        title = e.optString("title");
        duration = e.optDouble("duration", 0);
        elapsed = startSec;
        buffered = 0;
        state = "playing";
        heldForBot = false;
        history(e);
        new Thread(() -> {
            try {
                final YouTube.Audio a = YouTube.audio(id, false);
                synchronized (Music.this) {
                    if (gen != generation) return;
                    if (a.duration > 0) duration = a.duration;
                    quality = new JSONObject().put("codec", a.codec).put("kbps", a.kbps > 0 ? a.kbps : JSONObject.NULL)
                            .put("khz", JSONObject.NULL);
                }
                h.post(() -> {
                    if (gen != generation) return;
                    DefaultHttpDataSource.Factory ds = new DefaultHttpDataSource.Factory()
                            .setUserAgent(a.userAgent).setAllowCrossProtocolRedirects(true)
                            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000);
                    player.setMediaSource(new ProgressiveMediaSource.Factory(ds)
                            .createMediaSource(MediaItem.fromUri(Uri.parse(a.url))));
                    player.prepare();
                    applyOutput();
                    if (startSec > 0) player.seekTo((long) (startSec * 1000));
                    player.setPlayWhenReady(!heldForBot && "playing".equals(state));
                });
                BotLog.i("Nhạc: " + title + " (" + a.codec + " " + a.kbps + " kbps)");
            } catch (Exception ex) {
                BotLog.w("Nhạc: không lấy được " + id + ": " + ex.getMessage());
                h.post(() -> onError(null));
            }
        }, "music-resolve").start();
    }

    private synchronized JSONObject current() {
        if (queue == null) return null;
        JSONArray en = queue.optJSONArray("entries");
        int i = queue.optInt("index", -1);
        return en != null && i >= 0 && i < en.length() ? en.optJSONObject(i) : null;
    }

    private void onEnded() {
        errorsInRow = 0;
        advance(1, true);
    }

    private void onError(PlaybackException e) {
        if (e != null) BotLog.w("Nhạc lỗi phát: " + e.getErrorCodeName() + " " + e.getMessage());
        if (++errorsInRow > 3) {
            BotLog.w("Nhạc: lỗi liên tiếp, dừng");
            stop();
            return;
        }
        h.postDelayed(() -> advance(1, true), 1500);
    }

    /** Sang bài kế (delta = 1) / trước (-1); auto = hết bài tự chuyển (áp dụng chế độ lặp, tự gợi ý). */
    private void advance(final int delta, final boolean auto) {
        new Thread(() -> {
            try {
                synchronized (Music.this) {
                    if (queue == null) return;
                    JSONArray en = queue.getJSONArray("entries");
                    int i = queue.optInt("index", 0);
                    String lap = repeat();
                    if (auto && "mot".equals(lap)) {
                        start(0);
                        return;
                    }
                    int n = i + delta;
                    if (n < 0) n = 0;
                    if (n >= en.length()) {
                        if ("tat_ca".equals(lap)) {
                            n = 0;
                        } else if (queue.optBoolean("goi_y", true)) {
                            appendRelated();
                            en = queue.getJSONArray("entries");
                            if (n >= en.length()) {
                                stop();
                                return;
                            }
                        } else {
                            stop();
                            return;
                        }
                    }
                    queue.put("index", n);
                    saveQueue();
                    start(0);
                }
            } catch (Exception e) {
                BotLog.w("Nhạc: chuyển bài lỗi: " + e.getMessage());
            }
        }, "music-next").start();
    }

    /** Hết hàng chờ: nối bài liên quan của bài đang nghe (như "Tự phát" trên Pi). */
    private void appendRelated() throws Exception {
        JSONArray en = queue.getJSONArray("entries");
        JSONObject last = en.optJSONObject(en.length() - 1);
        if (last == null) return;
        Set<String> skip = new HashSet<>();
        for (int k = 0; k < en.length(); k++) skip.add(en.getJSONObject(k).optString("id"));
        JSONArray more = YouTube.related(last.optString("id"), skip, 10);
        for (int k = 0; k < more.length(); k++) en.put(slim(more.getJSONObject(k)));
        trim();
    }

    void next() {
        advance(1, false);
    }

    void back() {
        synchronized (this) {
            // Đã nghe quá 5 s thì "lùi" = phát lại từ đầu bài (như mọi trình phát nhạc)
            if (elapsed > 5 && curId != null) {
                h.post(() -> player.seekTo(0));
                return;
            }
        }
        advance(-1, false);
    }

    synchronized boolean pause() {
        if (!"playing".equals(state)) return false;
        state = "paused";
        h.post(() -> player.setPlayWhenReady(false));
        return true;
    }

    synchronized void resume() {
        if (curId == null) return;
        state = "playing";
        heldForBot = false;
        h.post(() -> player.setPlayWhenReady(true));
    }

    synchronized boolean seek(double sec) {
        if (curId == null) return false;
        elapsed = sec;
        h.post(() -> player.seekTo((long) (sec * 1000)));
        return true;
    }

    synchronized String stop() {
        boolean was = curId != null;
        generation++;
        curId = null;
        title = null;
        state = "stopped";
        heldForBot = false;
        h.post(() -> player.stop());
        Group.get(ctx).onStop();
        return was ? "Đã dừng nhạc." : "Không có nhạc đang phát.";
    }

    /** Nơi phát (bảng chọn của trang Nhạc): null = loa này, "nhom" / "loa:<ip>" = qua nhóm loa. */
    void setOutput(String target) {
        Group.get(ctx).setTarget(target);
        h.post(this::applyOutput);
    }

    /** Phát qua nhóm thì ExoPlayer tắt tiếng: loa này cũng phát qua đường đồng bộ như các loa khác. */
    private void applyOutput() {
        if (player != null) player.setVolume(Group.get(ctx).active() ? 0f : 1f);
    }

    /**
     * Bot nghe thấy từ đánh thức (true) / trả lời xong (false): tạm dừng nhạc cho bot nghe rõ và nói,
     * xong thì phát tiếp. Nhạc người dùng tự tạm dừng thì không tự phát lại.
     */
    synchronized void holdForBot(boolean hold) {
        Group.duck(hold); // loa này đang phát nhạc nhóm của loa khác: im tạm
        if (hold && "playing".equals(state) && !heldForBot) {
            heldForBot = true;
            h.post(() -> player.setPlayWhenReady(false));
        } else if (!hold && heldForBot) {
            heldForBot = false;
            if ("playing".equals(state)) h.post(() -> player.setPlayWhenReady(true));
        }
    }

    // ---------- Hàng chờ: chèn / trộn / tự phát ----------

    synchronized boolean insert(JSONObject bai, boolean next) throws Exception {
        if (queue == null || bai == null || !YouTube.validId(bai.optString("id"))) return false;
        JSONArray en = queue.getJSONArray("entries");
        int i = queue.optInt("index", 0);
        List<JSONObject> l = list(en);
        l.add(next ? Math.min(i + 1, l.size()) : l.size(), slim(bai));
        queue.put("entries", new JSONArray(l));
        trim();
        saveQueue();
        return true;
    }

    synchronized boolean setAutoSuggest(boolean on) throws Exception {
        if (queue == null) return false;
        queue.put("goi_y", on);
        saveQueue();
        return true;
    }

    synchronized boolean shuffle(boolean on) throws Exception {
        if (queue == null) return false;
        List<JSONObject> l = list(queue.getJSONArray("entries"));
        int i = queue.optInt("index", 0);
        if (on && !queue.optBoolean("tron")) {
            List<JSONObject> after = new ArrayList<>(l.subList(Math.min(i + 1, l.size()), l.size()));
            Collections.shuffle(after);
            List<JSONObject> out = new ArrayList<>(l.subList(0, Math.min(i + 1, l.size())));
            out.addAll(after);
            queue.put("goc", new JSONArray(l)).put("entries", new JSONArray(out)).put("tron", true);
        } else if (!on && queue.optBoolean("tron")) {
            List<JSONObject> goc = list(queue.optJSONArray("goc") != null ? queue.getJSONArray("goc") : new JSONArray());
            Set<String> ids = new HashSet<>();
            for (JSONObject b : goc) ids.add(b.optString("id"));
            for (JSONObject b : l) if (!ids.contains(b.optString("id"))) goc.add(b);
            String cur = i < l.size() ? l.get(i).optString("id") : "";
            int ni = i;
            for (int k = 0; k < goc.size(); k++) if (goc.get(k).optString("id").equals(cur)) ni = k;
            queue.put("entries", new JSONArray(goc)).put("index", ni).put("tron", false);
            queue.remove("goc");
        }
        saveQueue();
        return true;
    }

    /** Giới hạn hàng chờ MAX_LIST bài: bỏ bớt bài đã nghe ở đầu, giữ đúng vị trí bài đang nghe. */
    private void trim() throws Exception {
        List<JSONObject> l = list(queue.getJSONArray("entries"));
        int i = queue.optInt("index", 0);
        int over = l.size() - MAX_LIST;
        if (over <= 0) return;
        int drop = Math.min(over, i);
        queue.put("entries", new JSONArray(l.subList(drop, l.size()))).put("index", i - drop);
    }

    // ---------- Lặp ----------

    String repeat() {
        JSONObject o = readJson("repeat.json");
        String m = o != null ? o.optString("lap") : "";
        return "tat_ca".equals(m) || "mot".equals(m) ? m : "tat";
    }

    String setRepeat(String m) throws Exception {
        if (!"tat_ca".equals(m) && !"mot".equals(m)) m = "tat";
        writeJson("repeat.json", new JSONObject().put("lap", m));
        return m;
    }

    // ---------- Âm lượng (phần trăm như Pi) ----------

    int volume() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    int setVolume(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct * max / 100f), 0);
        return volume();
    }

    // ---------- Yêu thích / danh sách phát / lịch sử / danh sách tìm gần nhất ----------

    synchronized JSONArray favorites() {
        return readArray("favorites.json");
    }

    synchronized JSONArray setFavorite(JSONObject bai, boolean add) throws Exception {
        String id = bai != null ? bai.optString("id") : "";
        JSONArray cur = favorites();
        if (!YouTube.validId(id)) return cur;
        List<JSONObject> l = new ArrayList<>();
        if (add) l.add(slim(bai));
        for (JSONObject b : list(cur)) if (!b.optString("id").equals(id)) l.add(b);
        JSONArray out = new JSONArray(l.subList(0, Math.min(MAX_LIST, l.size())));
        writeJson("favorites.json", out);
        return out;
    }

    synchronized JSONArray playlists() {
        return readArray("playlists.json");
    }

    synchronized JSONObject createPlaylist(String name, JSONArray songs) throws Exception {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Cần tên danh sách phát");
        if (name.length() > 60) name = name.substring(0, 60);
        List<JSONObject> l = list(playlists());
        if (l.size() >= MAX_PLAYLISTS) throw new IllegalArgumentException("Tối đa " + MAX_PLAYLISTS + " danh sách phát");
        JSONArray bai = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int i = 0; songs != null && i < songs.length() && bai.length() < MAX_LIST; i++) {
            JSONObject b = songs.optJSONObject(i);
            if (b != null && YouTube.validId(b.optString("id")) && seen.add(b.optString("id"))) bai.put(slim(b));
        }
        JSONObject pl = new JSONObject().put("id", Long.toHexString(System.nanoTime() & 0xffffffffL))
                .put("ten", name).put("bai", bai).put("tao_luc", System.currentTimeMillis() / 1000.0);
        l.add(0, pl);
        writeJson("playlists.json", new JSONArray(l));
        return pl;
    }

    /** Đổi tên / xoá cả danh sách / thêm - bỏ 1 bài (thêm vào cuối, trùng thì bỏ qua). */
    synchronized JSONArray editPlaylist(String plId, String name, boolean delete, JSONObject bai, boolean add)
            throws Exception {
        List<JSONObject> l = list(playlists());
        JSONObject pl = null;
        for (JSONObject p : l) if (p.optString("id").equals(plId)) pl = p;
        if (pl == null) throw new IllegalArgumentException("Không có danh sách phát này");
        if (delete) {
            l.remove(pl);
        } else {
            if (name != null && !name.trim().isEmpty()) pl.put("ten", name.trim());
            if (bai != null && YouTube.validId(bai.optString("id"))) {
                List<JSONObject> songs = list(pl.optJSONArray("bai") != null ? pl.getJSONArray("bai") : new JSONArray());
                boolean has = false;
                for (JSONObject b : new ArrayList<>(songs)) {
                    if (b.optString("id").equals(bai.optString("id"))) {
                        has = true;
                        if (!add) songs.remove(b);
                    }
                }
                if (add && !has && songs.size() < MAX_LIST) songs.add(slim(bai));
                pl.put("bai", new JSONArray(songs));
            }
        }
        JSONArray out = new JSONArray(l);
        writeJson("playlists.json", out);
        return out;
    }

    synchronized JSONArray history() {
        return readArray("history.json");
    }

    synchronized void history(JSONObject bai) {
        try {
            String id = bai.optString("id");
            if (!YouTube.validId(id)) return;
            List<JSONObject> l = new ArrayList<>();
            l.add(slim(bai));
            for (JSONObject b : list(history())) if (!b.optString("id").equals(id)) l.add(b);
            writeJson("history.json", new JSONArray(l.subList(0, Math.min(MAX_HISTORY, l.size()))));
        } catch (Exception e) {
            BotLog.w("Nhạc: lỗi ghi lịch sử: " + e.getMessage());
        }
    }

    synchronized JSONObject lastList() {
        JSONObject o = readJson("list.json");
        try {
            return o != null ? o : new JSONObject().put("query", "").put("results", new JSONArray());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    synchronized void saveList(String query, JSONArray results) {
        try {
            JSONArray l = new JSONArray();
            for (int i = 0; i < results.length() && i < 50; i++) l.put(results.getJSONObject(i));
            writeJson("list.json", new JSONObject().put("query", query).put("results", l)
                    .put("ts", System.currentTimeMillis() / 1000.0));
        } catch (Exception ignored) {
        }
    }

    // ---------- Khám phá ----------

    /** Các hàng chủ đề như trang Khám phá của Pi: id, tên, dòng phụ, từ khoá ({nam} = năm nay). */
    static final String[][] TOPICS = {
            {"hot", "Thịnh hành", "Nhạc Việt đang hot", "MV official nhạc trẻ hot {nam}"},
            {"tiktok", "Hot TikTok", "Đang viral trên TikTok", "nhạc hot tiktok {nam} official MV"},
            {"bolero", "Bolero trữ tình", "Nhạc vàng hay nhất", "bolero MV official"},
            {"chill", "Nhạc chill", "Nhẹ nhàng, thư giãn", "lofi chill vpop MV official"},
            {"acoustic", "Acoustic cover", "Mộc mạc, dễ nghe", "acoustic cover MV official {nam}"},
    };

    JSONObject explore() throws Exception {
        JSONArray rows = new JSONArray().put(new JSONObject().put("id", "cho_ban").put("ten", "Dành cho bạn")
                .put("phu", "Dựa trên những bài bạn đã nghe"));
        for (String[] t : TOPICS) rows.put(new JSONObject().put("id", t[0]).put("ten", t[1]).put("phu", t[2]));
        JSONArray recent = new JSONArray();
        JSONArray hist = history();
        for (int i = 0; i < hist.length() && i < 20; i++) recent.put(hist.get(i));
        return new JSONObject().put("gan_day", recent).put("hang", rows);
    }

    /** Bài của 1 hàng Khám phá, đệm 6 giờ (Dành cho bạn: 3 giờ, đổi khi nghe bài mới). */
    JSONArray exploreRow(String row, boolean fresh) throws Exception {
        JSONObject cache = readJson("explore.json");
        if (cache == null) cache = new JSONObject();
        String key;
        long ttl;
        JSONArray hist = history();
        if ("cho_ban".equals(row)) {
            StringBuilder k = new StringBuilder();
            for (int i = 0; i < hist.length() && i < 3; i++) k.append(hist.getJSONObject(i).optString("id")).append(',');
            key = k.toString();
            ttl = 3 * 3600000L;
        } else {
            String[] t = null;
            for (String[] x : TOPICS) if (x[0].equals(row)) t = x;
            if (t == null) return new JSONArray();
            key = t[3];
            ttl = 6 * 3600000L;
        }
        JSONObject old = cache.optJSONObject(row);
        if (!fresh && old != null && key.equals(old.optString("khoa"))
                && System.currentTimeMillis() - old.optLong("ts") < ttl) {
            return old.optJSONArray("bai");
        }
        JSONArray songs;
        try {
            songs = "cho_ban".equals(row) ? forYou(hist) : topic(key);
        } catch (Exception e) {
            BotLog.w("Khám phá " + row + ": " + e.getMessage());
            return old != null ? old.optJSONArray("bai") : new JSONArray();
        }
        if (songs.length() == 0) return old != null ? old.optJSONArray("bai") : songs;
        synchronized (this) {
            cache = readJson("explore.json");
            if (cache == null) cache = new JSONObject();
            cache.put(row, new JSONObject().put("ts", System.currentTimeMillis()).put("khoa", key).put("bai", songs));
            writeJson("explore.json", cache);
        }
        return songs;
    }

    private JSONArray topic(String q) throws Exception {
        String year = String.valueOf(java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
        JSONArray r = YouTube.search(q.replace("{nam}", year), 24);
        JSONArray single = new JSONArray(), longer = new JSONArray();
        for (int i = 0; i < r.length(); i++) {
            JSONObject b = r.getJSONObject(i);
            double d = b.optDouble("duration", 0);
            if (d < 90) continue; // bỏ short
            (d <= 600 ? single : longer).put(b);
        }
        if (single.length() < 6) for (int i = 0; i < longer.length(); i++) single.put(longer.get(i));
        JSONArray out = new JSONArray();
        for (int i = 0; i < single.length() && i < 16; i++) out.put(single.get(i));
        return out;
    }

    private JSONArray forYou(JSONArray hist) throws Exception {
        JSONArray seeds = hist.length() > 0 ? hist : favorites();
        Set<String> heard = new HashSet<>();
        for (int i = 0; i < hist.length(); i++) heard.add(hist.getJSONObject(i).optString("id"));
        List<JSONArray> lists = new ArrayList<>();
        for (int i = 0; i < seeds.length() && i < 3; i++) {
            try {
                lists.add(YouTube.related(seeds.getJSONObject(i).optString("id"), heard, 8));
            } catch (Exception e) {
                BotLog.w("Gợi ý: " + e.getMessage());
            }
        }
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (int k = 0; k < 8; k++) {
            for (JSONArray l : lists) {
                if (k < l.length() && seen.add(l.getJSONObject(k).optString("id"))) out.put(l.get(k));
            }
        }
        return out;
    }

    // ---------- Tiện ích ----------

    static JSONObject slim(JSONObject b) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", b.optString("id")).put("title", b.optString("title"))
                    .put("channel", b.optString("channel")).put("channel_id", b.optString("channel_id"));
            if (b.has("duration") && !b.isNull("duration")) o.put("duration", b.opt("duration"));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static List<JSONObject> list(JSONArray a) {
        List<JSONObject> l = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) l.add(o);
        }
        return l;
    }

    private void saveQueue() {
        try {
            writeJson("queue.json", queue);
        } catch (Exception e) {
            BotLog.w("Nhạc: lỗi ghi hàng chờ: " + e.getMessage());
        }
    }

    private JSONObject readJson(String name) {
        try {
            return new JSONObject(readFile(name));
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray readArray(String name) {
        try {
            return new JSONArray(readFile(name));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private String readFile(String name) throws Exception {
        InputStream in = new FileInputStream(new File(dir, name));
        try {
            return LocalAdb.readAll(in);
        } finally {
            in.close();
        }
    }

    private void writeJson(String name, Object json) throws Exception {
        File tmp = new File(dir, name + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(json.toString().getBytes("UTF-8"));
        out.close();
        if (!tmp.renameTo(new File(dir, name))) throw new Exception("không ghi được " + name);
    }
}
