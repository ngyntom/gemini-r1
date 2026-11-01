package vn.r1.geminibot;

/** Bộ tiền xử lý âm thanh micro_speech (C, qua JNI): 10 ms PCM -> 40 đặc trưng. */
final class MicroFrontend {
    static final int STEP_SAMPLES = 160;
    static final int FEATURES = 40;

    static {
        System.loadLibrary("microfrontend");
    }

    private long handle;

    MicroFrontend() {
        handle = nativeCreate();
        if (handle == 0) throw new IllegalStateException("Không khởi tạo được microfrontend");
    }

    /** Xử lý 160 mẫu từ offset; trả về số đặc trưng ghi vào out (0 khi cửa sổ chưa đầy). */
    int process(short[] samples, int offset, int[] out) {
        return nativeProcess(handle, samples, offset, out);
    }

    void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate();

    private static native void nativeDestroy(long handle);

    private static native int nativeProcess(long handle, short[] samples, int offset, int[] out);
}
