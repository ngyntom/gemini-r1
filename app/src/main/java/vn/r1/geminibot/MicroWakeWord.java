package vn.r1.geminibot;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Từ đánh thức bằng model streaming microWakeWord (.tflite của ESPHome hoặc tự huấn luyện).
 *
 * Model có sẵn trong app: hey_jarvis, okay_nabu, hey_mycroft, alexa.
 * Model riêng: chép file .tflite (và .json cùng tên nếu có) vào loa rồi đặt
 * mww_model = đường dẫn tuyệt đối, ví dụ /sdcard/hey_gemini.tflite.
 */
class MicroWakeWord implements WakeEngine {
    private static final String TAG = BotService.TAG;
    private static final float FEATURE_SCALE = 0.0390625f; // = 1/25.6, như microWakeWord khi huấn luyện
    private static final int COOLDOWN_INFERENCES = 33;     // ~1 s sau mỗi lần kích hoạt / khi bắt đầu nghe

    private final Context ctx;
    private Interpreter tflite;
    private MicroFrontend frontend;
    private String loadedKey;

    private String wakeName;
    private float cutoff;
    private float[] window;
    private int windowPos;
    private int stride;
    private boolean floatInput;
    private float inScale;
    private int inZero;
    private ByteBuffer input;
    private ByteBuffer output;
    private int rowsFilled;
    private int cooldown;
    private float scorePeak;
    private int scoreCount;
    private final int[] features = new int[MicroFrontend.FEATURES];

    MicroWakeWord(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public void configure(Config cfg) throws Exception {
        String key = cfg.mwwModel + "|" + cfg.mwwCutoff;
        if (key.equals(loadedKey)) return;
        close();

        byte[] model;
        JSONObject manifest;
        if (cfg.mwwModel.startsWith("/")) {
            File f = new File(cfg.mwwModel);
            model = readAll(new FileInputStream(f));
            File json = new File(f.getPath().replaceAll("\\.tflite$", "") + ".json");
            manifest = json.exists() ? new JSONObject(new String(readAll(new FileInputStream(json)), "UTF-8"))
                    : new JSONObject();
            wakeName = f.getName().replaceAll("\\.tflite$", "");
        } else {
            String base = "mww/" + cfg.mwwModel;
            model = readAll(ctx.getAssets().open(base + ".tflite"));
            manifest = new JSONObject(new String(readAll(ctx.getAssets().open(base + ".json")), "UTF-8"));
            wakeName = cfg.mwwModel;
        }
        wakeName = manifest.optString("wake_word", wakeName);
        JSONObject micro = manifest.optJSONObject("micro");
        if (micro == null) micro = new JSONObject();
        cutoff = cfg.mwwCutoff > 0 ? cfg.mwwCutoff : (float) micro.optDouble("probability_cutoff", 0.97);
        window = new float[Math.max(1, micro.optInt("sliding_window_size", 5))];

        ByteBuffer buf = ByteBuffer.allocateDirect(model.length).order(ByteOrder.nativeOrder());
        buf.put(model).rewind();
        Interpreter.Options opts = new Interpreter.Options().setNumThreads(1);
        tflite = new Interpreter(buf, opts);

        Tensor in = tflite.getInputTensor(0);
        int[] shape = in.shape(); // [1, stride, 40]
        stride = shape.length >= 2 ? shape[1] : 1;
        floatInput = in.dataType() == DataType.FLOAT32;
        inScale = in.quantizationParams().getScale();
        inZero = in.quantizationParams().getZeroPoint();
        input = ByteBuffer.allocateDirect(in.numBytes()).order(ByteOrder.nativeOrder());
        output = ByteBuffer.allocateDirect(tflite.getOutputTensor(0).numBytes())
                .order(ByteOrder.nativeOrder());

        frontend = new MicroFrontend();
        rowsFilled = 0;
        loadedKey = key;
        BotLog.wakeName = wakeName;
        BotLog.i("microWakeWord: \"" + wakeName + "\", ngưỡng " + cutoff
                + ", cửa sổ " + window.length + ", stride " + stride + ", input " + in.dataType());
    }

    @Override
    public String waitFor(VoiceRecorder mic) {
        java.util.Arrays.fill(window, 0f);
        cooldown = COOLDOWN_INFERENCES;
        while (true) {
            short[] frame = mic.readFrame();
            if (frame == null) return null;
            if (mic.takeTrigger()) return "web";
            if (mic.takeReload()) return null; // vòng chính nạp lại cấu hình (model mới) rồi chờ tiếp
            mic.trackNoise(frame);
            for (int off = 0; off + MicroFrontend.STEP_SAMPLES <= frame.length;
                 off += MicroFrontend.STEP_SAMPLES) {
                int n = frontend.process(frame, off, features);
                if (n == 0) continue;
                addRow(features, n);
                if (rowsFilled < stride) continue;
                rowsFilled = 0;
                if (infer()) {
                    java.util.Arrays.fill(window, 0f);
                    cooldown = COOLDOWN_INFERENCES;
                    return wakeName;
                }
            }
        }
    }

    private void addRow(int[] f, int n) {
        int base = rowsFilled * MicroFrontend.FEATURES;
        for (int i = 0; i < n && i < MicroFrontend.FEATURES; i++) {
            float v = f[i] * FEATURE_SCALE;
            if (floatInput) {
                input.putFloat((base + i) * 4, v);
            } else {
                int q = Math.round(v / inScale) + inZero;
                input.put(base + i, (byte) Math.max(-128, Math.min(127, q)));
            }
        }
        rowsFilled++;
    }

    /** Chạy model một bước; trả về true khi trung bình cửa sổ trượt vượt ngưỡng. */
    private boolean infer() {
        input.rewind();
        output.rewind();
        tflite.run(input, output);

        Tensor out = tflite.getOutputTensor(0);
        float p;
        if (out.dataType() == DataType.FLOAT32) {
            p = output.getFloat(0);
        } else {
            int raw = out.dataType() == DataType.UINT8 ? (output.get(0) & 0xff) : output.get(0);
            p = (raw - out.quantizationParams().getZeroPoint()) * out.quantizationParams().getScale();
        }
        window[windowPos] = p;
        windowPos = (windowPos + 1) % window.length;

        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        float sum = 0;
        for (float w : window) sum += w;
        float avg = sum / window.length;
        scorePeak = Math.max(scorePeak, avg);
        if (++scoreCount >= 33) { // ~1 s
            BotLog.wakeScore = scorePeak;
            if (scorePeak >= 0.3f && scorePeak < cutoff) {
                BotLog.i(String.format(java.util.Locale.ROOT, "Gần nhận \"%s\": %.2f (ngưỡng %.2f)",
                        wakeName, scorePeak, cutoff));
            }
            scorePeak = 0;
            scoreCount = 0;
        }
        return avg >= cutoff;
    }

    @Override
    public void close() {
        if (tflite != null) tflite.close();
        if (frontend != null) frontend.close();
        tflite = null;
        frontend = null;
        loadedKey = null;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = is.read(b)) > 0) bos.write(b, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
