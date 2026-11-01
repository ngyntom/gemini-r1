cmake_minimum_required(VERSION 3.18.1)
project(microfrontend C CXX)

# Mã nguồn TFLM microfrontend + kissfft (tải tự động bởi task downloadMicroFrontend)
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)
set(LIB ${TP}/tensorflow/lite/experimental/microfrontend/lib)

add_library(microfrontend SHARED
        microfrontend_jni.cpp
        ${LIB}/fft.cc
        ${LIB}/fft_util.cc
        ${LIB}/filterbank.cc
        ${LIB}/filterbank_util.cc
        ${LIB}/frontend.cc
        ${LIB}/frontend_util.cc
        ${LIB}/kiss_fft_int16.cc
        ${LIB}/log_lut.cc
        ${LIB}/log_scale.cc
        ${LIB}/log_scale_util.cc
        ${LIB}/noise_reduction.cc
        ${LIB}/noise_reduction_util.cc
        ${LIB}/pcan_gain_control.cc
        ${LIB}/pcan_gain_control_util.cc
        ${LIB}/window.cc
        ${LIB}/window_util.cc)

target_include_directories(microfrontend PRIVATE ${TP} ${TP}/kissfft)
target_compile_options(microfrontend PRIVATE -O2)
target_link_libraries(microfrontend log)
