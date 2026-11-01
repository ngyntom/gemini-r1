package vn.r1.geminibot;

/** Âm "ding-dong" báo đã nghe thấy từ đánh thức (hai nốt Si5 → Mi6, tắt dần như chuông). */
final class Tone {
    static final int RATE = 24000;
    static final byte[] CHIME = chime();

    private Tone() {}

    private static byte[] chime() {
        double[][] notes = {{988, 110}, {1319, 260}}; // {Hz, ms}
        int total = 0;
        for (double[] n : notes) total += (int) (RATE * n[1] / 1000);
        byte[] pcm = new byte[total * 2];
        int pos = 0;
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
