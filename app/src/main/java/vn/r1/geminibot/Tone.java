package vn.r1.geminibot;

/** Tiếng "bíp" báo đã nghe thấy từ đánh thức. */
final class Tone {
    private static final int RATE = 16000;
    private static final byte[] BEEP = make(880, 150);

    private Tone() {}

    static void beepAsync() {
        new Thread(() -> {
            try {
                PcmPlayer.play(BEEP, RATE);
            } catch (InterruptedException ignored) {
            }
        }).start();
    }

    private static byte[] make(double hz, int ms) {
        int n = RATE * ms / 1000;
        int fade = RATE / 200; // 5 ms vào/ra để không bị "tách"
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double env = Math.min(1.0, Math.min(i, n - 1 - i) / (double) fade);
            short v = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * 12000 * env);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        return pcm;
    }
}
