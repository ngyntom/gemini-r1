package vn.r1.geminibot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Đổi tốc độ đọc mà không đổi cao độ giọng (WSOLA), dùng cho mọi bộ đọc.
 * Khung 30 ms chồng 50% với cửa sổ Hann; mỗi khung dò ±8 ms để khớp pha với khung trước.
 */
final class TimeStretch {
    private TimeStretch() {}

    static byte[] apply(byte[] pcm, int rate, float speed) {
        if (Math.abs(speed - 1f) < 0.03f || pcm.length < 4) return pcm;
        short[] in = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(in);

        int n = rate * 30 / 1000;
        int hs = n / 2;
        int tol = rate * 8 / 1000;
        float[] win = new float[n];
        for (int i = 0; i < n; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n));

        float[] out = new float[(int) (in.length / speed) + 2 * n];
        int prev = 0;
        int k = 0;
        while (true) {
            int target = (int) (k * hs * speed);
            if (target + n + tol >= in.length) break;
            int best = target;
            if (k > 0) {
                // Chọn vị trí giống nhất với phần "nối tiếp tự nhiên" của khung trước
                int nat = prev + hs;
                double bestScore = -Double.MAX_VALUE;
                for (int d = -tol; d <= tol; d += 2) {
                    int p = target + d;
                    if (p < 0) continue;
                    double score = 0;
                    for (int i = 0; i < hs; i += 4) score += (double) in[p + i] * in[nat + i];
                    if (score > bestScore) {
                        bestScore = score;
                        best = p;
                    }
                }
            }
            int o = k * hs;
            for (int i = 0; i < n; i++) out[o + i] += in[best + i] * win[i];
            prev = best;
            k++;
        }

        int len = Math.min(out.length, k * hs + hs);
        ByteBuffer bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < len; i++) {
            bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(out[i]))));
        }
        return bb.array();
    }
}
