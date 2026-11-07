package vn.r1.geminibot;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Nhóm loa phát đồng bộ (kiểu nhóm loa Google Home / AirPlay 2) giữa các loa R1 chạy app này.
 *
 * Loa đang phát nhạc là "loa chủ": ExoPlayer giải mã như thường nhưng tắt tiếng; PCM sau giải mã được
 * chép ra (TeeAudioProcessor, xem Music) và gắn mốc "phát lúc T" theo đồng hồ loa chủ (T = lúc nhận
 * + LEAD). Mỗi loa trong nhóm, kể cả chính loa chủ, nhận PCM + mốc T rồi tự canh AudioTrack của mình để
 * mẫu đó ra loa đúng lúc T (đổi sang đồng hồ của mình nhờ đo lệch giờ qua UDP, như NTP thu gọn), sửa
 * dần lệch bằng cách bỏ / chèn vài mẫu. Âm thanh gửi qua TCP cổng 8089, đo giờ qua UDP cổng 8090.
 * Chế độ stereo: loa chủ phát kênh trái, loa đầu tiên trong nhóm phát kênh phải.
 */
final class Group {
    static final int AUDIO_PORT = 8089, CLOCK_PORT = 8090;
    /** Phát sau lúc nhận PCM chừng này: đủ cho mạng Wi-Fi + đệm AudioTrack. */
    private static final long LEAD_NS = 700_000_000L;
    private static final byte HELLO = 1, AUDIO = 2, FLUSH = 3, END = 4;
    /** Âm lượng loa chủ (1 byte 0..100 %): loa thành viên đặt theo - cả nhóm cùng mức với loa chủ. */
    private static final byte VOLUME = 5;
    private static final int ROLE_MIX = 0, ROLE_LEFT = 1, ROLE_RIGHT = 2;

    private static Group instance;
    private final Context ctx;

    // ---- Loa chủ ----
    /** Nơi phát: null = chỉ loa này; "nhom" = loa này + các loa trong nhóm; "loa:<ip>" = chỉ loa đó. */
    private volatile String target;
    private final List<Peer> peers = new ArrayList<>();
    private Player local;                 // chính loa chủ phát qua đường đồng bộ
    private int rate, channels;
    private long baseNs = -1;              // mốc phát của mẫu đầu tiên (đồng hồ loa chủ)
    private long framesSent;
    private boolean stereo;                // loa này kênh trái, loa đầu tiên trong nhóm kênh phải

    // ---- Loa thành viên ----
    private volatile Player remote;

    static synchronized Group get(Context ctx) {
        if (instance == null) instance = new Group(ctx.getApplicationContext());
        return instance;
    }

    private Group(Context ctx) {
        this.ctx = ctx;
        startClockServer();
        startAudioServer();
    }

    // ======================= Cấu hình / nơi phát =======================

    static String deviceName() {
        try {
            BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.getName() != null) return bt.getName();
        } catch (Exception ignored) {
        }
        return "Phicomm R1";
    }

    /** Tên người dùng đặt cho loa (Cài đặt, ô đầu trang), chưa đặt thì tên Bluetooth "Phicomm R1 9457". */
    static String displayName(Context ctx) {
        String t = Config.load(ctx).tenHienThi;
        return t.isEmpty() ? deviceName().replace('_', ' ') : t;
    }

    /** IP các loa trong nhóm (đặt trên Web UI, khoá group_members). */
    List<String> members() {
        List<String> out = new ArrayList<>();
        for (String s : Config.load(ctx).groupMembers.split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    /** Danh sách cho bảng chọn nơi phát của trang Nhạc (dạng thiết bị HA của Pi: entity, ten, loai). */
    JSONArray targets() throws Exception {
        JSONArray a = new JSONArray();
        List<String> m = members();
        if (!m.isEmpty()) {
            a.put(new JSONObject().put("entity", "nhom").put("loai", "speaker")
                    .put("ten", tenNhom()));
        }
        for (String ip : m) a.put(new JSONObject().put("entity", "loa:" + ip).put("loai", "speaker").put("ten", "Loa " + ip));
        return a;
    }

    private String tenNhom() {
        return "Nhóm loa (" + (members().size() + 1) + " loa" + (Config.load(ctx).groupStereo ? ", stereo" : "") + ")";
    }

    String target() {
        return target;
    }

    String targetName() {
        String t = target;
        if (t == null) return null;
        // Cùng chữ với mục trong danh sách chọn nơi phát (targets): trang Nhạc chờ trạng thái báo ĐÚNG tên đó mới tắt
        // vòng xoay "đang chuyển" - trước là "Nhóm loa" khác "Nhóm loa (2 loa)" nên nút Phát xoay mãi dù đã hát
        if (t.equals("nhom")) return tenNhom();
        return "Loa " + t.substring(4);
    }

    /** Đổi nơi phát; nhạc đang phát chuyển sang ngay từ mẫu kế tiếp. */
    synchronized void setTarget(String t) {
        if (t != null && !t.equals("nhom") && !t.startsWith("loa:")) t = null;
        if (t != null && t.equals(target)) return;
        closePeers();
        target = t;
        stereo = t != null && t.equals("nhom") && Config.load(ctx).groupStereo;
        baseNs = -1;
        BotLog.i(t == null ? "Nhạc: phát trên loa này" : "Nhạc: phát trên " + targetName());
    }

    boolean active() {
        return target != null;
    }

    // ======================= Loa chủ: nhận PCM từ ExoPlayer =======================

    /** TeeAudioProcessor báo định dạng mới / tua / đổi bài. */
    synchronized void onFlush(int sampleRate, int channelCount, int encoding) {
        rate = sampleRate;
        channels = channelCount;
        baseNs = -1;
        framesSent = 0;
        if (target == null) return;
        if (local != null) local.flush(rate, channels);
        for (Peer p : peers) p.send(FLUSH, 0, null, rate, channels);
    }

    /** PCM 16-bit xen kênh vừa giải mã (gọi trên luồng phát của ExoPlayer: không được chặn). */
    synchronized void onPcm(ByteBuffer buf) {
        if (target == null || rate == 0 || !buf.hasRemaining()) return;
        byte[] pcm = new byte[buf.remaining()];
        buf.duplicate().get(pcm);
        int frames = pcm.length / (2 * channels);
        long now = System.nanoTime();
        long playAt = baseNs < 0 ? -1 : baseNs + framesSent * 1_000_000_000L / rate;
        // Mới bắt đầu, hoặc dữ liệu tới trễ (mạng YouTube chậm, vừa tạm dừng): đặt lại mốc
        if (playAt < now + LEAD_NS / 3) {
            baseNs = now + LEAD_NS - framesSent * 1_000_000_000L / rate;
            playAt = baseNs + framesSent * 1_000_000_000L / rate;
        }
        framesSent += frames;
        ensurePeers();
        if (target.equals("nhom")) {
            if (local == null) local = new Player("nhóm (loa này)");
            local.enqueue(playAt, pcm, rate, channels, stereo ? ROLE_LEFT : ROLE_MIX);
        } else if (local != null) {
            local.close();
            local = null;
        }
        for (Peer p : peers) p.send(AUDIO, playAt, pcm, rate, channels);
    }

    /** Dừng nhạc / tắt nhóm: các loa thôi phát. */
    synchronized void onStop() {
        baseNs = -1;
        framesSent = 0;
        if (local != null) local.flush(rate, channels);
        for (Peer p : peers) p.send(END, 0, null, rate, channels);
    }

    private void ensurePeers() {
        if (!peers.isEmpty()) return;
        List<String> ips = new ArrayList<>();
        if (target.equals("nhom")) ips.addAll(members());
        else ips.add(target.substring(4));
        for (int i = 0; i < ips.size(); i++) {
            peers.add(new Peer(ctx, ips.get(i), stereo && i == 0 ? ROLE_RIGHT : ROLE_MIX));
        }
    }

    private void closePeers() {
        for (Peer p : peers) p.close();
        peers.clear();
        if (local != null) {
            local.close();
            local = null;
        }
    }

    /** Một loa thành viên: hàng gửi riêng + luồng gửi riêng, mất kết nối thì tự nối lại. */
    private static final class Peer {
        final Context ctx;
        final String ip;
        final int role;
        final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<>();
        volatile boolean closed;
        private boolean warned;

        Peer(Context ctx, String ip, int role) {
            this.ctx = ctx;
            this.ip = ip;
            this.role = role;
            new Thread(this::run, "group-send-" + ip).start();
        }

        void send(byte type, long playAt, byte[] pcm, int rate, int ch) {
            if (q.size() > 400) q.clear(); // loa này tắt / mất mạng lâu: đừng giữ âm thanh cũ
            q.offer(new Object[]{type, playAt, pcm, rate, ch});
        }

        void close() {
            closed = true;
            q.offer(new Object[]{END, 0L, null, 0, 0});
        }

        private void run() {
            while (!closed) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, AUDIO_PORT), 3000);
                    s.setTcpNoDelay(true);
                    DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
                    int sentRate = -1, sentCh = -1;
                    int sentVol = -1;      // -1: vừa nối -> gửi ngay để loa thành viên khớp âm lượng loa chủ
                    long volCheckedAt = 0;
                    if (warned) BotLog.i("Nhóm loa: đã nối lại " + ip);
                    warned = false;
                    while (true) {
                        // Đọc âm lượng loa chủ ~3 lần / giây, đổi thì gửi: bắt được MỌI cách chỉnh (web, app, giọng nói,
                        // nút trên loa) mà không phải sửa từng chỗ chỉnh âm lượng
                        long now = System.currentTimeMillis();
                        if (now - volCheckedAt > 300) {
                            volCheckedAt = now;
                            int vol = Music.get(ctx).volume();
                            if (vol != sentVol) {
                                out.writeByte(VOLUME);
                                out.writeByte(vol);
                                out.flush();
                                sentVol = vol;
                            }
                        }
                        Object[] m = q.poll(300, TimeUnit.MILLISECONDS);
                        if (closed && m == null) break;
                        if (m == null) {
                            out.writeByte(0); // giữ kết nối
                            out.flush();
                            continue;
                        }
                        byte type = (Byte) m[0];
                        int rate = (Integer) m[3], ch = (Integer) m[4];
                        if (type != END && (rate != sentRate || ch != sentCh)) {
                            out.writeByte(HELLO);
                            out.writeInt(rate);
                            out.writeByte(ch);
                            out.writeByte(role);
                            sentRate = rate;
                            sentCh = ch;
                        }
                        out.writeByte(type);
                        if (type == AUDIO) {
                            byte[] pcm = (byte[]) m[2];
                            out.writeLong((Long) m[1]);
                            out.writeInt(pcm.length);
                            out.write(pcm);
                        }
                        if (q.isEmpty()) out.flush();
                        if (type == END && closed) break;
                    }
                    out.flush();
                } catch (Exception e) {
                    if (!warned && !closed) {
                        warned = true;
                        BotLog.w("Nhóm loa: không gửi được tới " + ip + " (" + e.getMessage() + ")");
                    }
                    q.clear();
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }
    }

    // ======================= Loa thành viên: nhận và phát =======================

    private void startAudioServer() {
        new Thread(() -> {
            try (ServerSocket server = new ServerSocket(AUDIO_PORT)) {
                while (true) {
                    final Socket s = server.accept();
                    new Thread(() -> receive(s), "group-recv").start();
                }
            } catch (Exception e) {
                BotLog.w("Nhóm loa: không mở được cổng " + AUDIO_PORT + ": " + e.getMessage());
            }
        }, "group-server").start();
    }

    private void receive(Socket s) {
        String from = s.getInetAddress().getHostAddress();
        Player p = null;
        Clock clock = new Clock(from);
        try {
            s.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
            int rate = 0, ch = 0, role = ROLE_MIX;
            BotLog.i("Nhóm loa: nhận nhạc từ " + from);
            Music.get(ctx).stop(); // đang phát nhạc riêng thì nhường cho nhóm
            while (true) {
                byte type = in.readByte();
                if (type == 0) continue;
                if (type == HELLO) {
                    rate = in.readInt();
                    ch = in.readByte();
                    role = in.readByte();
                } else if (type == AUDIO) {
                    long playAt = in.readLong();
                    byte[] pcm = new byte[in.readInt()];
                    in.readFully(pcm);
                    if (p == null) {
                        p = new Player("nhóm (từ " + from + ")");
                        remote = p;
                    }
                    long local = clock.toLocal(playAt);
                    if (local != Long.MIN_VALUE) p.enqueue(local, pcm, rate, ch, role);
                } else if (type == VOLUME) {
                    int vol = in.readUnsignedByte();
                    if (vol != Music.get(ctx).volume()) {
                        Music.get(ctx).setVolume(vol);
                        BotLog.i("Nhóm loa: âm lượng theo loa chủ " + vol + "%");
                    }
                } else if (type == FLUSH) {
                    if (p != null) p.flush(rate, ch);
                } else if (type == END) {
                    if (p != null) p.flush(rate, ch);
                }
            }
        } catch (Exception e) {
            // loa chủ ngắt kết nối (đổi nơi phát / tắt nhóm)
        } finally {
            clock.stop = true;
            if (p != null) p.close();
            if (remote == p) remote = null;
            try {
                s.close();
            } catch (Exception ignored) {
            }
            BotLog.i("Nhóm loa: thôi nhận nhạc từ " + from);
        }
    }

    /** Đang phát nhạc của loa khác (bot nói thì tạm im loa này). */
    boolean receiving() {
        return remote != null;
    }

    /** Bot của loa này đang nghe / nói: phần nhạc nhóm phát ra loa này thành im lặng (vẫn giữ nhịp đồng bộ). */
    private static volatile boolean ducked;

    static void duck(boolean on) {
        ducked = on;
    }

    /**
     * Phát PCM có mốc thời gian đúng lúc: hàng chờ + luồng ghi AudioTrack. Mốc đã đổi sang đồng hồ
     * (System.nanoTime) của loa này. Đo lúc mẫu kế tiếp ra loa bằng AudioTrack.getTimestamp rồi bỏ /
     * chèn mẫu để khớp: lệch nhiều (> 20 ms) sửa ngay, lệch ít sửa tối đa 1 ms mỗi gói cho khỏi nghe thấy.
     */
    private static final class Player {
        private final String name;
        private final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<>();
        private volatile boolean closed;
        private volatile int flushes;
        private AudioTrack track;
        private int rate;
        private long written;
        private short[] monoBuf = new short[0]; // tái dùng, tránh cấp phát mỗi gói

        Player(String name) {
            this.name = name;
            new Thread(this::run, "group-play").start();
        }

        void enqueue(long playAt, byte[] pcm, int rate, int ch, int role) {
            if (q.size() > 600) return;
            q.offer(new Object[]{playAt, pcm, rate, ch, role, flushes});
        }

        void flush(int rate, int ch) {
            flushes++;
            q.clear();
        }

        void close() {
            closed = true;
            q.offer(new Object[0]);
        }

        private void run() {
            try {
                while (!closed) {
                    Object[] m = q.poll(500, TimeUnit.MILLISECONDS);
                    if (m == null || m.length == 0) continue;
                    if ((Integer) m[5] != flushes) continue; // gói của bài / chỗ tua cũ
                    long playAt = (Long) m[0];
                    int r = (Integer) m[2], ch = (Integer) m[3], role = (Integer) m[4];
                    monoBuf = toMono((byte[]) m[1], ch, role, monoBuf);
                    short[] mono = monoBuf;
                    if (track == null || r != rate) open(r);
                    // Ghi trước ~280 ms: trừ trễ ra loa còn ~240 ms chờ trong đệm AudioTrack (~250 ms) - đủ dày
                    // để CPU loa bận một lúc (giải mã, Wi-Fi) không làm hụt dữ liệu, giật tiếng
                    long wait = playAt - System.nanoTime() - 280_000_000L;
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    if (playAt < System.nanoTime() - 50_000_000L && written > 0 && !playing()) {
                        continue; // quá trễ (loa vừa bận), bỏ gói
                    }
                    write(mono, playAt);
                }
            } catch (InterruptedException ignored) {
            } finally {
                if (track != null) {
                    track.pause();
                    track.flush();
                    track.release();
                }
            }
        }

        private boolean playing() {
            return track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
        }

        private void open(int r) {
            if (track != null) track.release();
            rate = r;
            int min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bytes = Math.max(min, r / 4 * 2); // ~250 ms, vừa đủ cho lượng ghi trước (~240 ms)
            track = new AudioTrack(AudioManager.STREAM_MUSIC, r, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bytes, AudioTrack.MODE_STREAM);
            written = 0;
            track.play();
            // Android 5.1 chỉ bắt đầu phát một AudioTrack khi đệm được ghi ĐẦY một lần; ghi chưa đầy thì
            // vị trí phát đứng yên ở 0 mãi (đúng lỗi nhóm loa im lặng ở bản 1.43 / 1.44). Ghi sẵn một đệm
            // im lặng cho loa chạy, phần canh giờ trong write() tự khớp lại sau đó.
            silence(bytes / 2);
            inserted = 0;
            BotLog.i("Nhóm loa: phát " + name + " (" + r + " Hz)");
        }

        /** Trễ cố định từ lúc ghi vào loa phát hết đệm tới tai (ước lượng, giống nhau trên mọi R1). */
        private static final long OUT_LATENCY_NS = 40_000_000L;
        private long dropped, inserted, lastLog, lastHead = -1, lastHeadAt, errAvg = Long.MIN_VALUE;
        private long sumErr, nErr;

        /**
         * Canh bằng lượng chờ trong AudioTrack: mẫu đầu gói phải ra loa lúc playAt, tức lúc ghi thì trong loa
         * phải còn đúng (playAt - bây giờ - trễ ra loa) âm thanh chưa phát. Còn nhiều hơn = đang trễ -> bỏ bớt
         * đầu gói; ít hơn = đang sớm -> chèn im lặng. Lượng chờ = đã ghi - vị trí phát (getPlaybackHeadPosition);
         * không dùng getTimestamp (bản trước: mốc cũ làm mọi gói bị coi là trễ đúng 1 gói, bỏ sạch, im lặng).
         * Trễ ra loa sau AudioTrack giống nhau trên mọi R1 nên không ảnh hưởng độ khớp giữa các loa.
         */
        private void write(short[] pcm, long playAt) {
            long now = System.nanoTime();
            long head = track.getPlaybackHeadPosition() & 0xffffffffL;
            long buffered = Math.max(0, written - head);
            long want = (playAt - now - OUT_LATENCY_NS) * rate / 1_000_000_000L;
            long err = buffered - want; // > 0: trễ, < 0: sớm (đơn vị mẫu)
            // Vị trí phát đứng yên > 0,3 s mà vẫn còn dữ liệu = loa chưa chạy (đệm chưa đầy): ghi tiếp,
            // đừng bỏ, kẻo kẹt im lặng mãi
            if (head != lastHead) {
                lastHead = head;
                lastHeadAt = now;
            }
            boolean stuck = buffered > 0 && now - lastHeadAt > 300_000_000L;
            int start = 0;
            if (stuck) {
                err = 0;
            } else if (want < 0 && buffered == 0) {
                start = (int) Math.min(-want, pcm.length); // loa trống và gói đã quá giờ: bỏ phần đã qua
            } else {
                sumErr += err;
                nErr++;
                // Vị trí phát AudioTrack nhảy theo từng chu kỳ 10-20 ms -> err dao động; chỉnh theo trung bình
                // ~2 s. Bản trước chỉnh theo từng lần đo, chèn 1 ms im lặng / cắt 1 ms: nghe lách tách.
                errAvg = errAvg == Long.MIN_VALUE ? err : errAvg + (err - errAvg) / 64;
                long big = rate / 20;  // 50 ms: lệch thật (mạng nghẽn, vừa hụt dữ liệu) -> sửa ngay
                long dead = rate / 125; // 8 ms
                if (err > big) {
                    start = (int) Math.min(err, pcm.length);
                    errAvg = Long.MIN_VALUE;
                } else if (err < -big) {
                    silence((int) Math.min(-err, rate / 2));
                    errAvg = Long.MIN_VALUE;
                } else if (errAvg > dead) {
                    start = Math.min(2, pcm.length); // bỏ 2 mẫu (0,05 ms): tai không nhận ra
                } else if (errAvg < -dead && pcm.length > 0) {
                    track.write(new short[]{pcm[0], pcm[0]}, 0, 2); // nhân đôi mẫu đầu, không chèn số 0
                    written += 2;
                    inserted += 2;
                }
            }
            dropped += start;
            if (start < pcm.length) {
                if (!ducked) MusicLight.feed(pcm, playAt); // đèn theo nhạc đúng lúc tiếng ra loa này
                if (ducked) java.util.Arrays.fill(pcm, (short) 0); // tránh cấp phát mảng mới
                track.write(pcm, start, pcm.length - start);
                written += pcm.length - start;
            }
            if (now - lastLog > 10_000_000_000L) { // 10 s: ghi độ lệch để chỉnh
                lastLog = now;
                BotLog.i(String.format(java.util.Locale.ROOT,
                        "Nhóm loa %s: lệch TB %.1f ms, bỏ %d ms, chèn %d ms, chờ %d gói", name,
                        nErr > 0 ? sumErr * 1000.0 / nErr / rate : 0.0, dropped * 1000 / rate,
                        inserted * 1000 / rate, q.size()));
                sumErr = nErr = dropped = inserted = 0;
            }
        }

        private void silence(int frames) {
            if (frames <= 0) return;
            frames = Math.min(frames, rate * 2);
            track.write(new short[frames], 0, frames);
            written += frames;
            inserted += frames;
        }

        private static short[] toMono(byte[] pcm, int ch, int role, short[] buf) {
            int frames = pcm.length / (2 * Math.max(1, ch));
            short[] out = buf.length == frames ? buf : new short[frames];
            if (ch < 2) {
                for (int i = 0; i < frames; i++)
                    out[i] = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
            } else {
                int stride = 2 * ch;
                for (int i = 0; i < frames; i++) {
                    int p = i * stride;
                    short l = (short) ((pcm[p] & 0xff) | (pcm[p + 1] << 8));
                    short r = (short) ((pcm[p + 2] & 0xff) | (pcm[p + 3] << 8));
                    out[i] = role == ROLE_LEFT ? l : role == ROLE_RIGHT ? r : (short) ((l + r) >> 1);
                }
            }
            return out;
        }
    }

    // ======================= Đồng hồ chung =======================

    /** Trả giờ của loa này cho loa khác đo lệch: nhận 8 byte t0, gửi lại t0 + giờ của mình. */
    private void startClockServer() {
        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(CLOCK_PORT)) {
                byte[] buf = new byte[16];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    sock.receive(p);
                    if (p.getLength() < 8) continue;
                    ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).putLong(8, System.nanoTime());
                    sock.send(new DatagramPacket(buf, 16, p.getAddress(), p.getPort()));
                }
            } catch (Exception e) {
                BotLog.w("Nhóm loa: không mở được cổng đồng hồ " + CLOCK_PORT + ": " + e.getMessage());
            }
        }, "group-clock").start();
    }

    /** Đo lệch giờ với loa chủ (lấy lần đo có thời gian khứ hồi ngắn nhất trong 8 lần), đo lại mỗi 5 s. */
    private static final class Clock {
        private final String host;
        private volatile long offset;          // giờ loa chủ - giờ loa này
        private volatile boolean ready;
        private volatile boolean stop;

        Clock(String host) {
            this.host = host;
            Thread t = new Thread(() -> {
                while (!stop) {
                    measure();
                    try {
                        Thread.sleep(ready ? 5000 : 300);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "group-clock-sync");
            t.setDaemon(true);
            t.start();
        }

        private void measure() {
            try (DatagramSocket sock = new DatagramSocket()) {
                sock.setSoTimeout(300);
                InetAddress addr = InetAddress.getByName(host);
                long bestRtt = Long.MAX_VALUE, bestOff = 0;
                for (int i = 0; i < 8; i++) {
                    byte[] b = new byte[16];
                    long t0 = System.nanoTime();
                    ByteBuffer.wrap(b).putLong(0, t0);
                    sock.send(new DatagramPacket(b, 8, addr, CLOCK_PORT));
                    DatagramPacket r = new DatagramPacket(new byte[16], 16);
                    try {
                        sock.receive(r);
                    } catch (Exception e) {
                        continue;
                    }
                    long t1 = System.nanoTime();
                    ByteBuffer bb = ByteBuffer.wrap(r.getData());
                    if (bb.getLong(0) != t0) continue;
                    long rtt = t1 - t0;
                    if (rtt < bestRtt) {
                        bestRtt = rtt;
                        bestOff = bb.getLong(8) - (t0 + t1) / 2;
                    }
                }
                if (bestRtt < 100_000_000L) {
                    offset = bestOff;
                    ready = true;
                }
            } catch (Exception ignored) {
            }
        }

        /** Mốc giờ loa chủ -> giờ loa này; Long.MIN_VALUE nếu chưa đo được. */
        long toLocal(long masterNs) {
            return ready ? masterNs - offset : Long.MIN_VALUE;
        }
    }

    // ======================= Tìm loa trong mạng =======================

    /** Các loa R1 khác chạy app này trong cùng mạng /24: [{ip, ten, version}]. */
    static JSONArray scan() throws Exception {
        String me = localIp();
        JSONArray out = new JSONArray();
        if (me == null) return out;
        String prefix = me.substring(0, me.lastIndexOf('.') + 1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        final List<JSONObject> found = Collections.synchronizedList(new ArrayList<JSONObject>());
        for (int i = 1; i < 255; i++) {
            final String ip = prefix + i;
            if (ip.equals(me)) continue;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(ip, WebServer.PORT), 400);
                } catch (Exception e) {
                    return;
                }
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + ":" + WebServer.PORT + "/api/group/info")
                            .openConnection();
                    c.setConnectTimeout(1500);
                    c.setReadTimeout(2000);
                    JSONObject j = new JSONObject(LocalAdb.readAll(c.getInputStream()));
                    found.add(new JSONObject().put("ip", ip).put("ten", j.optString("ten"))
                            .put("version", j.optString("version")));
                } catch (Exception ignored) {
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(15, TimeUnit.SECONDS);
        for (JSONObject o : found) out.put(o);
        return out;
    }

    static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface n = ifs.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && n.getName().startsWith("wlan")) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
