package vn.r1.geminibot;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Giọng Google Dịch (translate_tts): miễn phí, không cần key, phản hồi nhanh (~0.5 s).
 * Không phải API chính thức nên Google có thể đổi hoặc giới hạn bất cứ lúc nào.
 */
final class FreeGoogleTts {
    private static final int MAX_CHUNK = 180; // translate_tts nhận tối đa ~200 ký tự mỗi lần

    private FreeGoogleTts() {}

    static PcmPlayer.Pcm synth(File cacheDir, String text) throws Exception {
        File mp3 = new File(cacheDir, "gtts.mp3");
        OutputStream out = new FileOutputStream(mp3);
        try {
            for (String part : chunks(text)) {
                // Ghép các file MP3 nối tiếp nhau vẫn là một luồng MP3 hợp lệ
                out.write(fetch(part));
            }
        } finally {
            out.close();
        }
        try {
            return decode(mp3);
        } finally {
            mp3.delete();
        }
    }

    private static byte[] fetch(String part) throws Exception {
        URL u = new URL("https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q="
                + URLEncoder.encode(part, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            if (c.getResponseCode() != 200) throw new Exception("Google Dịch TTS HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Cắt câu dài thành đoạn ≤ MAX_CHUNK ký tự, ưu tiên cắt ở dấu câu rồi tới dấu cách. */
    static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        String rest = text.trim();
        while (rest.length() > MAX_CHUNK) {
            int cut = -1;
            for (int i = MAX_CHUNK; i > MAX_CHUNK / 3 && cut < 0; i--) {
                char ch = rest.charAt(i - 1);
                if (ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ',' || ch == ':') cut = i;
            }
            if (cut < 0) cut = rest.lastIndexOf(' ', MAX_CHUNK);
            if (cut <= 0) cut = MAX_CHUNK;
            out.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** Giải mã MP3 bằng MediaCodec của máy thành PCM 16-bit mono. */
    private static PcmPlayer.Pcm decode(File mp3) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(mp3.getPath());
        ex.selectTrack(0);
        MediaFormat fmt = ex.getTrackFormat(0);
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        try {
            while (true) {
                if (!inputDone) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int n = ex.readSampleData(b, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer b = codec.getOutputBuffer(oi);
                    byte[] chunk = new byte[info.size];
                    b.position(info.offset);
                    b.get(chunk);
                    pcm.write(chunk);
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            codec.stop();
            codec.release();
            ex.release();
        }
        byte[] data = pcm.toByteArray();
        if (channels == 2) data = toMono(data);
        return new PcmPlayer.Pcm(data, rate);
    }

    private static byte[] toMono(byte[] st) {
        ByteBuffer in = ByteBuffer.wrap(st).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.allocate(st.length / 2).order(ByteOrder.LITTLE_ENDIAN);
        while (in.remaining() >= 4) out.putShort((short) ((in.getShort() + in.getShort()) / 2));
        return out.array();
    }
}
