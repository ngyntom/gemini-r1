package vn.r1.geminibot;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Phát PCM 16-bit mono ra loa, chặn cho tới khi phát xong. */
public final class PcmPlayer {
    private PcmPlayer() {}

    public static void play(byte[] pcm, int rate) throws InterruptedException {
        if (pcm == null || pcm.length < 2) return;
        int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf, rate), AudioTrack.MODE_STREAM);
        try {
            t.play();
            int len = pcm.length & ~1;
            int off = 0;
            while (off < len) {
                int w = t.write(pcm, off, Math.min(8192, len - off));
                if (w <= 0) break;
                off += w;
            }
            int totalFrames = len / 2;
            long deadline = System.currentTimeMillis() + 2000L + totalFrames * 1000L / rate;
            while (t.getPlaybackHeadPosition() < totalFrames && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            t.stop();
            t.release();
        }
    }
}
