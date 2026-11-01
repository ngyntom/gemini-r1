package vn.r1.geminibot;

/** Đóng gói PCM 16-bit mono thành file WAV. */
final class Wav {
    private Wav() {}

    static byte[] wrap(byte[] pcm, int rate) {
        byte[] wav = new byte[44 + pcm.length];
        put(wav, 0, "RIFF");
        int32(wav, 4, 36 + pcm.length);
        put(wav, 8, "WAVE");
        put(wav, 12, "fmt ");
        int32(wav, 16, 16);
        int16(wav, 20, 1);          // PCM
        int16(wav, 22, 1);          // mono
        int32(wav, 24, rate);
        int32(wav, 28, rate * 2);   // byte rate
        int16(wav, 32, 2);          // block align
        int16(wav, 34, 16);         // bits per sample
        put(wav, 36, "data");
        int32(wav, 40, pcm.length);
        System.arraycopy(pcm, 0, wav, 44, pcm.length);
        return wav;
    }

    private static void put(byte[] b, int off, String s) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void int32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void int16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
