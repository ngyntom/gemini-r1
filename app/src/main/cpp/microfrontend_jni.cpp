// JNI cho bộ tiền xử lý micro_speech của TFLite Micro, cấu hình giống hệt
// ESPHome micro_wake_word (preprocessor_settings.h) để khớp với model microWakeWord.
#include <jni.h>

#include <cstdint>
#include <new>

#include "tensorflow/lite/experimental/microfrontend/lib/frontend.h"
#include "tensorflow/lite/experimental/microfrontend/lib/frontend_util.h"

namespace {
constexpr int kSampleRate = 16000;
constexpr int kStepSamples = 160;  // 10 ms
constexpr int kMaxFeatures = 64;
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_vn_r1_geminibot_MicroFrontend_nativeCreate(JNIEnv*, jclass) {
    FrontendConfig cfg;
    FrontendFillConfigWithDefaults(&cfg);
    cfg.window.size_ms = 30;
    cfg.window.step_size_ms = 10;
    cfg.filterbank.num_channels = 40;
    cfg.filterbank.lower_band_limit = 125.0f;
    cfg.filterbank.upper_band_limit = 7500.0f;
    cfg.noise_reduction.smoothing_bits = 10;
    cfg.noise_reduction.even_smoothing = 0.025f;
    cfg.noise_reduction.odd_smoothing = 0.06f;
    cfg.noise_reduction.min_signal_remaining = 0.05f;
    cfg.pcan_gain_control.enable_pcan = 1;
    cfg.pcan_gain_control.strength = 0.95f;
    cfg.pcan_gain_control.offset = 80.0f;
    cfg.pcan_gain_control.gain_bits = 21;
    cfg.log_scale.enable_log = 1;
    cfg.log_scale.scale_shift = 6;

    auto* st = new (std::nothrow) FrontendState();
    if (st == nullptr) return 0;
    if (!FrontendPopulateState(&cfg, st, kSampleRate)) {
        delete st;
        return 0;
    }
    return reinterpret_cast<jlong>(st);
}

extern "C" JNIEXPORT void JNICALL
Java_vn_r1_geminibot_MicroFrontend_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* st = reinterpret_cast<FrontendState*>(handle);
    if (st == nullptr) return;
    FrontendFreeStateContents(st);
    delete st;
}

// Xử lý đúng 10 ms (160 mẫu) bắt đầu từ offset. Trả về số đặc trưng ghi vào out (0 hoặc 40).
extern "C" JNIEXPORT jint JNICALL
Java_vn_r1_geminibot_MicroFrontend_nativeProcess(JNIEnv* env, jclass, jlong handle,
                                                 jshortArray samples, jint offset,
                                                 jintArray out) {
    auto* st = reinterpret_cast<FrontendState*>(handle);
    if (st == nullptr) return 0;

    jshort pcm[kStepSamples];
    env->GetShortArrayRegion(samples, offset, kStepSamples, pcm);

    size_t read = 0;
    FrontendOutput fo = FrontendProcessSamples(st, reinterpret_cast<const int16_t*>(pcm),
                                               kStepSamples, &read);
    if (fo.size == 0 || fo.size > kMaxFeatures) return 0;

    jint values[kMaxFeatures];
    for (size_t i = 0; i < fo.size; ++i) values[i] = fo.values[i];
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(fo.size), values);
    return static_cast<jint>(fo.size);
}
