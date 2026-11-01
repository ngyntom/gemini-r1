# Gemini R1 – biến loa Phicomm R1 thành bot dùng Gemini

App Android chạy ngầm trên R1 (Android 5.1, không màn hình):

1. **Chờ từ đánh thức offline**, chạy ngay trên loa và không gửi gì lên mạng. Có 2 bộ nhận:
   - **microWakeWord** (mặc định): rất nhẹ, dùng model `.tflite` của ESPHome. Mặc định là **"Hey Jarvis"**.
     Có thể tự huấn luyện "Hey Gemini".
   - **Vosk**: từ đánh thức tiếng Việt, ví dụ **"trợ lý ơi"**, nhưng nặng CPU hơn.
2. Nghe thấy thì kêu "bíp", ghi câu hỏi của bạn (tự dừng khi bạn ngừng nói).
3. Gửi đoạn ghi âm cho Gemini (`gemini-2.5-flash`), Gemini vừa nghe vừa trả lời.
4. Đọc câu trả lời bằng giọng Gemini TTS qua loa R1.
5. Nghe tiếp 6 giây để bạn hỏi tiếp mà không cần gọi lại từ đánh thức.
6. Tự chạy lại khi R1 khởi động.

Ví dụ: "Hey Jarvis" → *bíp* → "Hôm nay thời tiết Hà Nội thế nào?"

## 1. Build APK

Lần build đầu tự tải về:
- model Vosk tiếng Việt (`vosk-model-small-vn-0.4`, 32 MB),
- 4 model microWakeWord của ESPHome (`hey_jarvis`, `okay_nabu`, `hey_mycroft`, `alexa`),
- mã C của bộ tiền xử lý âm thanh micro_speech (từ `rhasspy/pymicro-features`, Apache-2.0),
  được biên dịch bằng NDK + CMake.

APK nặng khoảng 45 MB. Cần **Android NDK** và **CMake 3.22.1**. GitHub Actions đã có sẵn;
trên máy riêng, Android Studio sẽ tự tải khi build.

**Cách A – GitHub Actions (không cần cài gì):** đẩy thư mục này lên một repo GitHub,
vào tab *Actions* → *Build APK*, rồi tải artifact `gemini-r1-apk` (file `app-debug.apk`).

**Cách B – build trên máy:** cài JDK 17 và Android SDK (hoặc Android Studio), rồi:

```
gradle assembleDebug
```

## 2. Cài lên R1

R1 mở sẵn adb qua Wi-Fi ở cổng 5555. Thay `192.168.1.50` bằng IP của loa:

```
adb connect 192.168.1.50:5555
adb install -r app-debug.apk
```

Ẩn trợ lý gốc của Phicomm để nó không giữ micro:

```
adb shell pm hide com.phicomm.speaker.device
```

(Muốn khôi phục: `adb shell pm unhide com.phicomm.speaker.device`.)

## 3. Web UI

Mở **http://<IP của R1>:8088** (ví dụ http://192.168.2.230:8088) từ điện thoại hoặc máy tính
trong cùng mạng. Trên đó có thể:

- xem trạng thái (chờ / đang nghe / đang nghĩ / đang nói), câu vừa nghe, câu trả lời và nhật ký;
- bấm **Nghe ngay** (không cần từ đánh thức), **Tạm dừng** (nhả micro cho app khác), **Xoá trí nhớ**;
- chỉnh âm lượng, gõ câu hỏi cho Gemini, hoặc gõ một câu để loa đọc;
- đổi API key, model, giọng đọc, tính cách, bộ nhận và từ đánh thức;
- tải model microWakeWord tự huấn luyện (.tflite) lên loa.

Web UI không có mật khẩu, nên chỉ dùng trong mạng nhà. Cổng 8080 và 8081 thường đã bị các app khác
trên R1 dùng (vd. AI BOX), nên app này dùng cổng 8088.

## 4. Cấu hình qua adb (tuỳ chọn)

Mọi thứ đều chỉnh được trên Web UI. Nếu muốn dùng adb thì lấy API key miễn phí tại
https://aistudio.google.com/apikey, rồi:

```
adb shell am startservice -n vn.r1.geminibot/.BotService --es api_key "AIza...."
```

Trong log sẽ có dòng `microWakeWord: "Hey Jarvis", ngưỡng 0.97, ...` khi bot đã sẵn sàng.

Các tuỳ chọn khác (đặt lúc nào cũng được, giá trị được lưu lại):

| extra | mặc định | ý nghĩa |
|---|---|---|
| `wake_engine` | `mww` | `mww` = microWakeWord, `vosk` = Vosk tiếng Việt |
| `mww_model` | `hey_jarvis` | `hey_jarvis`, `okay_nabu`, `hey_mycroft`, `alexa`, hoặc đường dẫn file `.tflite` |
| `mww_cutoff` | `0` | ngưỡng 0–1; `0` = lấy từ file `.json` của model. Tăng lên nếu kích hoạt nhầm, giảm nếu gọi khó ăn |
| `wake_word` | `trợ lý ơi` | (Vosk) từ đánh thức; nhiều từ thì cách nhau bằng dấu phẩy, vd. `"trợ lý ơi,loa ơi"` |
| `follow_up` | `6` | số giây nghe tiếp sau khi trả lời; `0` = luôn phải gọi lại từ đánh thức |
| `model` | `gemini-2.5-flash` | model trả lời |
| `tts_model` | `gemini-2.5-flash-preview-tts` | model đọc giọng |
| `voice` | `Kore` | giọng đọc (Puck, Charon, Aoede, Leda…) |
| `persona` | trợ lý ngắn gọn | tính cách / chỉ dẫn hệ thống |

Ví dụ đổi từ đánh thức:

```
# microWakeWord, dùng "Okay Nabu"
adb shell am startservice -n vn.r1.geminibot/.BotService --es mww_model okay_nabu
# Chuyển sang Vosk tiếng Việt
adb shell am startservice -n vn.r1.geminibot/.BotService --es wake_engine vosk --es wake_word "trợ lý ơi"
```

Lệnh tiện dụng:

```
# Thử loa (không cần micro)
adb shell am startservice -n vn.r1.geminibot/.BotService --es say "Xin chào, tôi là Gemini"
# Xoá trí nhớ hội thoại
adb shell am startservice -n vn.r1.geminibot/.BotService --ez reset true
# Xem log
adb logcat -s GeminiR1 VoskAPI
```

## Tự huấn luyện "Hey Gemini" (microWakeWord)

1. Mở notebook `basic_training_notebook.ipynb` trong repo
   https://github.com/kahrendt/microWakeWord (chạy trên Google Colab có GPU), đặt từ đánh thức là
   `hey gemini`. Notebook dùng giọng đọc tổng hợp Piper để tạo mẫu.
   Tác giả lưu ý thường phải thử chỉnh tham số vài lần mới ra model tốt.
2. Lấy file `.tflite` (streaming, quantized) mà notebook xuất ra. Nếu muốn, tạo kèm file `.json`
   cùng tên, theo mẫu của ESPHome:
   `{"wake_word":"Hey Gemini","micro":{"probability_cutoff":0.97,"sliding_window_size":5}}`
3. Chép vào loa và chọn model đó:

```
adb push hey_gemini.tflite /sdcard/hey_gemini.tflite
adb push hey_gemini.json /sdcard/hey_gemini.json
adb shell am startservice -n vn.r1.geminibot/.BotService --es mww_model /sdcard/hey_gemini.tflite
```

Không cần build lại app.

## Chọn từ đánh thức cho Vosk

- Phải là **từ tiếng Việt** có trong từ điển của model. "Gemini" hay "Alexa" không có nên Vosk
  sẽ không nhận ra. Nếu log `VoskAPI` báo `Ignoring word missing in vocabulary` thì từ đó không dùng được.
- Nên dùng **2–3 âm tiết** và ít gặp trong lúc nói chuyện, như "trợ lý ơi" hoặc "loa thông minh ơi".
  Từ quá ngắn như "ơi" sẽ bị kích hoạt nhầm liên tục.
- Nếu kích hoạt nhầm nhiều, dùng cụm dài hơn. Nếu gọi mãi không ăn, nói rõ hơn hoặc đổi cụm khác.

## Lưu ý

- Trên Android 5.1, mỗi lúc chỉ một app được dùng micro. Nếu loa đang chạy bot khác (AI BOX
  `info.dourok.voicebot`, `com.phicomm.r1.xiaozhi`, `com.mgt.r1voice`…) thì phải tắt bot đó,
  hoặc bấm **Tạm dừng** trên Web UI của app này khi muốn dùng bot kia.
- App chưa được thử trên R1 thật. Nếu log báo "Không mở được micro" thì một app gốc khác
  vẫn đang giữ mic. Liệt kê các app Phicomm bằng `adb shell pm list packages | grep phicomm`
  rồi `pm hide` dần từng cái.
- microWakeWord trên Android dùng TensorFlow Lite thay cho TFLite Micro của ESP32. Đặc trưng âm thanh
  được tính bằng chính mã C mà ESPHome dùng, nên kết quả phải giống nhau, nhưng điều này chưa được kiểm
  chứng trên R1. Nếu gọi mãi không ăn, thử giảm `mww_cutoff` (vd. `0.8`) rồi xem log.
- Giờ hệ thống R1 phải đúng, nếu không kết nối HTTPS tới Google sẽ lỗi chứng chỉ.
- Nếu Google đổi tên model, đổi bằng `--es model ...` / `--es tts_model ...`, không cần build lại.
