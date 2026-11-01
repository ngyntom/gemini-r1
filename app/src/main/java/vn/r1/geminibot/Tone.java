package vn.r1.geminibot;

/** Âm "ding-dong" báo đã nghe thấy từ đánh thức (hai nốt Si5 → Mi6, tắt dần như chuông). */
final class Tone {
    static final int RATE = 24000;
    static final byte[] CHIME = chime();

    private Tone() {}

    /** Ampli của R1 tắt khi rảnh và mất ~0.5 s để bật: phát im lặng trước, không thì mất cả tiếng ding. */
    private static final int LEAD_MS = 500;

    private static byte[] chime() {
        double[][] notes = {{988, 110}, {1319, 260}}; // {Hz, ms}
        int lead = RATE * LEAD_MS / 1000;
        int total = lead;
        for (double[] n : notes) total += (int) (RATE * n[1] / 1000);
        byte[] pcm = new byte[total * 2];
        int pos = lead;
        for (double[] note : notes) {
            int n = (int) (RATE * note[1] / 1000);
            int attack = RATE / 400; // 2.5 ms, tránh tiếng "tách"
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, i / (double) attack) * Math.exp(-4.0 * i / n)
                        * Math.min(1.0, (n - 1 - i) / (double) attack);
                double s = Math.sin(2 * Math.PI * note[0] * i / RATE)
                        + 0.3 * Math.sin(2 * Math.PI * 2 * note[0] * i / RATE); // thêm bồi âm cho "trong"
                short v = (short) (s / 1.3 * 22000 * env);
                pcm[2 * pos] = (byte) v;
                pcm[2 * pos + 1] = (byte) (v >> 8);
                pos++;
            }
        }
        return pcm;
    }
}
