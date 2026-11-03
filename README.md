# Gemini R1 – biến loa Phicomm R1 thành bot dùng Gemini

App Android chạy ngầm trên R1 (Android 5.1, không màn hình):

1. **Chờ từ đánh thức offline**, chạy ngay trên loa và không gửi gì lên mạng. Có 2 bộ nhận:
   - **microWakeWord** (mặc định): rất nhẹ, dùng model `.tflite` của ESPHome. Trên loa đang dùng
     **"Okay Nabu"**. Có thể tự huấn luyện "Hey Gemini".
   - **Vosk**: từ đánh thức tiếng Việt, ví dụ **"trợ lý ơi"**, nhưng nặng CPU hơn.
     Model Vosk (32 MB) không nằm trong APK: loa tự tải khi cần.
2. Nghe thấy thì kêu "ding-dong" và đèn viền sáng trắng, rồi ghi câu hỏi (tự dừng khi bạn ngừng nói).
3. Hỏi giờ / ngày / thứ thì loa tự trả lời (Vosk offline), không gửi Gemini.
4. Câu khác thì gửi đoạn ghi âm cho Gemini (`gemini-2.5-flash`), Gemini vừa nghe vừa trả lời.
5. Đọc câu trả lời bằng giọng Google Dịch (gTTS, nhanh, không cần key), có chỉnh tốc độ.
   Có thể đổi sang Google Cloud TTS (cần key Cloud) hoặc Gemini TTS (chậm hơn).
6. Đèn viền sáng trắng lúc nghe và lúc nói, nhấp nháy lúc đang chờ Gemini, tắt khi rảnh.
7. Điều khiển thiết bị qua **Home Assistant** ("bật đèn phòng khách", "điều hoà 26 độ"…), xem bên dưới.
8. Tự chạy lại khi R1 khởi động.

Ví dụ: "Okay Nabu" → *ding-dong* → "Hôm nay thời tiết Hà Nội thế nào?"

## 1. Build APK

Lần build đầu tự tải về:
- 4 model microWakeWord của ESPHome (`hey_jarvis`, `okay_nabu`, `hey_mycroft`, `alexa`),
- mã C của bộ tiền xử lý âm thanh micro_speech (từ `rhasspy/pymicro-features`, Apache-2.0),
  được biên dịch bằng NDK + CMake.

APK nặng khoảng 5 MB. Cần **Android NDK** và **CMake 3.22.1**. GitHub Actions đã có sẵn;
trên máy riêng, Android Studio sẽ tự tải khi build.

**Cách A – GitHub Actions (không cần cài gì):** đẩy thư mục này lên một repo GitHub,
vào tab *Actions* → *Build APK*, rồi tải artifact `gemini-r1-apk` (file zip chứa `app-debug.apk`).
`build.yml` giữ lại khoá ký debug giữa các lần build, nhờ vậy bản mới cài đè lên bản cũ được.

**Cách B – build trên máy:** cài JDK 17 và Android SDK (hoặc Android Studio), rồi:

```
gradle assembleDebug
```

## 2. Cài lên R1

**Đã có app trên loa:** mở Web UI, thẻ **Cập nhật app**, chọn file zip vừa tải (hoặc `.apk`) rồi bấm cài.
Loa tự cài, tự chạy lại và giữ nguyên cài đặt. Nếu báo lỗi kết nối adb thì máy tính đang giữ adb của
loa (loa chỉ nhận một kết nối): chạy `adb disconnect` hoặc `adb kill-server` rồi thử lại.

**Lần đầu:** R1 mở sẵn adb qua Wi-Fi ở cổng 5555. Thay `192.168.1.50` bằng IP của loa:

```
adb connect 192.168.1.50:5555
adb install -r app-debug.apk
```

Ẩn trợ lý gốc của Phicomm (小讯) để nó không giữ micro. Nếu không ẩn, Web UI sẽ báo
**mic 0 · điểm 0.00** và gọi từ đánh thức không ăn:

```
adb shell "for p in device productiontest bugreport otaservice systemtool; do pm hide com.phicomm.speaker.$p; done"
```

(Muốn khôi phục: thay `pm hide` bằng `pm unhide`.)

## Khôi phục sau khi reset loa về mặc định

Reset xoá Wi-Fi, mọi app đã cài và bật lại các app gốc Phicomm. adb vẫn mở.

1. **Wi-Fi:** loa phát Wi-Fi riêng, không mật khẩu, tên `Phicomm_R1_xxxx`. Cho máy tính vào mạng đó, rồi chạy
   `powershell -ExecutionPolicy Bypass -File tools\r1-wifi.ps1` và nhập tên + mật khẩu Wi-Fi nhà.
   (Script gửi `POST http://192.168.43.1:8989/api/configwifi` với JSON
   `{"ssid","mac":"","level":"","secure":"WPA","password"}`; xem danh sách mạng loa thấy ở
   `http://192.168.43.1:8989/api/wifilist`.) Mở `192.168.43.1` trên trình duyệt sẽ không hiện gì, vì
   loa không có trang web ở cổng 80.
2. Khoảng 1 phút sau loa vào mạng nhà (thường lấy lại IP cũ). Cài APK bằng adb như trên.
3. Ẩn lại các app gốc Phicomm (lệnh `pm hide` ở trên).
4. Nhập lại API key và cài đặt trên Web UI, hoặc chép lại file cài đặt đã sao lưu:
   `adb push gemini_r1_prefs.xml /data/local/tmp/gr1.xml`, rồi
   `adb shell "run-as vn.r1.geminibot sh -c 'mkdir -p shared_prefs && cat /data/local/tmp/gr1.xml > shared_prefs/gemini_r1.xml'; rm /data/local/tmp/gr1.xml"`.
5. Múi giờ của loa về Thượng Hải sau reset. App đã tự dùng giờ Việt Nam nên không cần chỉnh.

## 3. Web UI

Mở **http://<IP của R1>:8088** (ví dụ http://192.168.2.230:8088) từ điện thoại hoặc máy tính
trong cùng mạng. Trên đó có thể:

- xem trạng thái (chờ / đang nghe / đang nghĩ / đang nói), câu vừa nghe, câu trả lời và nhật ký;
- bấm **Nghe ngay** (không cần từ đánh thức), **Tạm dừng** (nhả micro cho app khác), **Xoá trí nhớ**;
- chỉnh âm lượng, gõ câu hỏi cho Gemini, hoặc gõ một câu để loa đọc;
- đổi API key, model, giọng đọc, tính cách, bộ nhận và từ đánh thức;
- chọn giọng đọc (gTTS / Google Cloud / Gemini), tốc độ đọc, bật/tắt đèn và thử đèn;
- chọn âm báo khi đánh thức (ding-dong / câu đáp "Dạ?" / im lặng), bật/tắt trả lời giờ tại loa,
  chỉnh độ nhạy mic; xem **mức micro / điểm nhận** và bấm **▶ Nghe mic** để nghe 5 giây mic vừa thu;
- **cập nhật app** từ file zip/apk;
- tải model microWakeWord tự huấn luyện (.tflite) lên loa.

Cổng 8080 và 8081 thường đã bị các app khác trên R1 dùng (vd. AI BOX), nên app này dùng cổng 8088.

### Đăng nhập và truy cập từ xa

Mặc định Web UI **không có mật khẩu**: ai vào được địa chỉ của loa đều điều khiển được loa, kể cả cài APK
lên loa. Muốn mở loa ra Internet (như Voicebot trên Pi) thì **đặt mật khẩu trước**:
**Cài đặt → Hệ thống → Đăng nhập**, nhập tên đăng nhập và mật khẩu (tối thiểu 6 ký tự).

Đặt rồi thì mọi API cần đăng nhập (phiên giữ 30 ngày, khởi động lại loa vẫn còn), trừ `/api/ping`
(app iOS dò địa chỉ), `/api/group/info` (loa khác dò nhóm loa) và chính các API đăng nhập. Sai 5 lần liên
tiếp thì bị khoá 1 phút, sai tiếp thì thời gian khoá tăng gấp đôi, tối đa 15 phút. Đổi mật khẩu thì mọi
máy khác bị đăng xuất.

Mở ra ngoài: cho proxy (vd. Nginx Proxy Manager trên Home Assistant, cùng chỗ đang mở Voicebot) trỏ một
tên miền HTTPS về `http://<IP loa>:8088`. Tắt buffering của proxy (nginx: `proxy_buffering off;`), không thì
trạng thái nhạc (SSE) bị trễ. Proxy có gửi `X-Forwarded-Proto: https` thì cookie đăng nhập được gắn cờ
`Secure`. **Chỉ mở cổng 8088.** Không mở 8089 / 8090: đó là cổng nhóm loa trong mạng nhà, không có mật khẩu.

App iOS (Capacitor): gõ tên miền đó ở màn nhập địa chỉ. Tên miền phải nằm trong `allowNavigation` của
`ios-app/capacitor.config.json` (đang cho phép `*.tnt-hassio.dynu.net`), không thì app mở bằng Safari.

**Quên mật khẩu** (như Voicebot): bấm **Quên mật khẩu?** trên màn đăng nhập → **Gửi mã xác thực**. Loa gửi mã 6 số qua
thông báo Home Assistant (dịch vụ đặt ở **Cài đặt → Home Assistant → Dịch vụ thông báo**, vd. `mobile_app_iphone`;
mặc định `notify`). Nhập mã + mật khẩu mới là đăng nhập luôn. Mã sống 5 phút, sai 5 lần phải xin mã mới, mỗi
lần xin mã cách nhau ít nhất 1 phút. Link chỉ hiện khi loa đã nối Home Assistant.

Không có Home Assistant thì xoá file mật khẩu qua adb rồi chạy lại app. Web UI trở về không cần đăng nhập:

```
adb connect <IP loa>:5555
adb shell run-as vn.r1.geminibot rm -f files/auth.json files/sessions.json
adb shell am force-stop vn.r1.geminibot
adb shell am startservice -n vn.r1.geminibot/.BotService
adb disconnect
```

## Home Assistant

1. Trong HA: bấm tên tài khoản (góc dưới bên trái) → tab **Bảo mật** → **Long-lived access tokens** →
   **Tạo token**, rồi chép token.
2. Trên Web UI, thẻ **Home Assistant**: nhập địa chỉ dạng `http://<IP của HA>:8123` và token, bấm
   **Lưu cấu hình** rồi **Kiểm tra kết nối**. Phải dùng IP và http: Android 5.1 không phân giải được
   `homeassistant.local` và không tin chứng chỉ Let's Encrypt.
3. Nói "bật đèn phòng khách", "tắt hết đèn", "đóng rèm", "điều hoà 26 độ", "nhiệt độ phòng ngủ bao nhiêu"…

Cách hoạt động: mỗi câu hỏi, loa lấy danh sách thiết bị qua `/api/states` (tối đa 300; chỉ các nhóm đèn,
công tắc, quạt, rèm, điều hoà, media, scene, script, khoá, input_*, máy hút bụi… cùng cảm biến) và đưa cho
Gemini. Gemini gọi hàm `ha_call(domain, service, entity_id, data_json)`, loa gọi
`/api/services/<domain>/<service>` rồi báo kết quả lại cho Gemini để nó trả lời. Chỉ gọi được các nhóm điều
khiển ở trên và các entity có trong danh sách. Lệnh hệ thống (`homeassistant.restart`, `shell_command`,
`hassio`…) bị chặn. Đặt tên tiếng Việt dễ gọi cho thiết bị trong HA sẽ giúp nhận đúng hơn.
Mỗi lệnh điều khiển tốn thêm một lượt gọi Gemini (khoảng 1 s).

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
| `follow_up` | `6` | số giây nghe tiếp sau khi trả lời; `0` = luôn phải gọi lại từ đánh thức (trên loa đang để `0` vì `6` hay tự trả lời liên tiếp) |
| `mic_gain` | `1` | khuếch đại mic; trên loa đang để `4` |
| `tts_engine` | `gfree` | `gfree` = Google Dịch, `google` = Cloud TTS (cần key `AIza…` có billing), `gemini` |
| `tts_rate` | `1` | tốc độ đọc 0.5–2 |
| `led` | `1` | đèn viền: `1` bật, `0` tắt |
| `wake_sound` | `chime` | `chime`, `voice` (đọc `wake_reply`, mặc định "Dạ?"), `none` |
| `local_time` | `1` | hỏi giờ / ngày / thứ thì trả lời tại loa |
| `ha_url` | rỗng | Home Assistant, vd. `http://192.168.2.10:8123` |
| `ha_token` | rỗng | long-lived access token của HA (`-` = xoá) |
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
- Nếu log báo "Không mở được micro" hoặc mức mic luôn 0 thì một app khác vẫn đang giữ mic.
  Liệt kê các app Phicomm bằng `adb shell pm list packages | grep phicomm` rồi `pm hide` dần từng cái.
- Gọi mãi không ăn: xem dòng "Gần nhận ... (ngưỡng 0.97)" trong log; nếu điểm thường chỉ 0.8–0.9 thì
  giảm `mww_cutoff` (vd. `0.85`).
- **Đèn:** viền đèn chỉ có LED trắng, độ sáng theo byte xanh dương của màu (`lights_test set 7fffff8000 <rgb>`),
  nên app luôn dùng `ffffff`. Các app khác như EchoService / AI BOX có thể giành quyền điều khiển đèn.
  **Không** ghi vào nhóm LED 1–15: loa sẽ khởi động lại.
- **Âm thanh:** ampli của R1 tắt khi rảnh và mất khoảng 0.5 s để bật lại, nên âm thanh ngắn phải có
  khoảng im lặng ở đầu (xem `Tone.java`), không thì mất tiếng.
- Giờ hệ thống R1 phải đúng, nếu không kết nối HTTPS tới Google sẽ lỗi chứng chỉ.
- Nếu Google đổi tên model, đổi bằng `--es model ...` / `--es tts_model ...`, không cần build lại.
