package vn.r1.geminibot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Đổi tốc độ đọc mà không đổi cao độ giọng (WSOLA), dùng cho mọi bộ đọc.
 * Cùng cách và cùng thông số mặc định với "sox … tempo" mà Voicebot trên Pi dùng: đoạn 82 ms,
 * dò ±15 ms, nối 12 ms bằng cross-fade tuyến tính. Bản cũ (khung 30 ms, dò thô) nghe hơi rè và
 * khác giọng trên Pi dù cùng tốc độ 1.25.
 */
final class TimeStretch {
    private static final int SEGMENT_MS = 82, SEARCH_MS = 15, OVERLAP_MS = 12;

    private TimeStretch() {}

    static byte[] apply(byte[] pcm, int rate, float speed) {
        if (Math.abs(speed - 1f) < 0.03f || pcm.length < 4) return pcm;
        short[] in = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(in);

        int seg = rate * SEGMENT_MS / 1000;
        int search = rate * SEARCH_MS / 1000;
        int ov = rate * OVERLAP_MS / 1000;
        int outStep = seg - ov;          // mỗi đoạn ghi thêm chừng này mẫu ra
        double inStep = outStep * speed; // và tiến chừng này mẫu trên đầu vào
        if (in.length < seg + search) return pcm;

        short[] out = new short[(int) (in.length / speed) + seg];
        int o = 0;
        // Đoạn đầu: chép nguyên, giữ lại phần đuôi (ov mẫu cuối) để nối với đoạn sau
        System.arraycopy(in, 0, out, 0, outStep);
        o = outStep;
        int tail = outStep; // vị trí đuôi của đoạn trước trên đầu vào

        for (int k = 1; ; k++) {
            int nominal = (int) Math.round(k * inStep);
            if (nominal + search + seg + 3 >= in.length) break; // +3: dò kỹ có thể lệch thêm 2 mẫu
            // Dò vị trí có đầu đoạn giống đuôi đoạn trước nhất (tương quan chéo trên vùng nối):
            // dò thô (mỗi 3 vị trí, mỗi 2 mẫu) rồi dò kỹ quanh chỗ tốt nhất - CPU của R1 yếu
            int best = bestMatch(in, tail, ov, Math.max(0, nominal - search), nominal + search, 3, 2);
            best = bestMatch(in, tail, ov, Math.max(0, best - 2), best + 2, 1, 1);
            // Vùng nối: mờ dần đuôi cũ, rõ dần đầu mới
            for (int i = 0; i < ov; i++) {
                float t = (float) i / ov;
                out[o++] = (short) Math.round(in[tail + i] * (1 - t) + in[best + i] * t);
            }
            // Phần giữa của đoạn mới
            int mid = seg - 2 * ov;
            System.arraycopy(in, best + ov, out, o, mid);
            o += mid;
            tail = best + seg - ov;
        }
        // Đuôi của đoạn cuối
        int rest = Math.min(ov, in.length - tail);
        System.arraycopy(in, tail, out, o, rest);
        o += rest;

        ByteBuffer bb = ByteBuffer.allocate(o * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(out, 0, o);
        return bb.array();
    }

    /** Vị trí p trong [from, to] (bước posStep) có in[p..p+ov) tương quan lớn nhất với in[tail..tail+ov). */
    private static int bestMatch(short[] in, int tail, int ov, int from, int to, int posStep, int sampleStep) {
        int best = from;
        long bestScore = Long.MIN_VALUE;
        for (int p = from; p <= to; p += posStep) {
            long score = 0;
            for (int i = 0; i < ov; i += sampleStep) score += (long) in[tail + i] * in[p + i];
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }
}
