# Khung chương kỹ thuật (bản nháp)

Số liệu trong ngoặc vuông `[ĐO]` là chỗ cần điền sau khi đo ở tuần 3.

## Chương A. Kiến trúc tổng thể
- Fork GPL-3.0 của Rhythm (Kotlin, Jetpack Compose, Media3 1.11). Mã mới nằm trong package
  `vn.laho.musicplayer` (dsp, usb, assistant, mood, và sau khi gộp: drive, metadata); chỉ chèn vài
  điểm móc vào mã gốc để vẫn gộp được bản sửa từ upstream. Danh sách sửa đổi: `FORK_NOTICE.md`.
- Một quyết định duy nhất cho đường âm thanh: `AudioOutputPolicy` (chuẩn / DSP float / USB bit-perfect).

## Chương B. Xử lý âm thanh số: PEQ 32-bit float
- Vấn đề: Media3 bỏ qua `AudioProcessor` khi xuất float, hoặc cắt về 16-bit nếu không.
- Giải pháp: `LahoDspAudioSink` đổi PCM sang float, áp biquad (công thức RBJ, dạng TDF-II, trạng thái
  double), xuất float; bộ phân tích tệp AutoEQ `ParametricEQ.txt`; EQ hệ thống giữ phẳng khi DSP bật.
- Kiểm chứng: 33 unit test; đáp tuyến tần số và null test `[ĐO]`.

## Chương C. USB DAC bit-perfect trên Android 12
- Vấn đề: Android trộn và resample qua AudioFlinger, nên không bit-perfect.
- Giải pháp: driver USB Audio Class 2 ở userspace (ISO URB, endpoint feedback không đồng bộ, tính
  gói theo `bInterval`), engine FLAC native, pipeline ExoPlayer cho định dạng khác.
- Lỗi đã gặp và cách sửa (đưa vào phần "Khó khăn"): âm thanh vấp do `bInterval=2`; chuyển bài
  chậm/mất tiếng do crossfade, drain URB, `TrimmingAudioProcessor`.
- Kiểm chứng: MD5 mẫu gửi xuống URB so với STREAMINFO `[ĐO]`; underrun và độ trễ chuyển bài `[ĐO]`.
- Hạn chế: chỉ thử trên Sonata BHD; crossfade tắt ở chế độ USB.

## Chương D. Trợ lý AI
- `ToolRegistry`/`AssistantTool`, `OfflineCommandParser` (bỏ dấu) chạy trước, Gemini
  (Firebase AI Logic, function calling) khi cần; độ trễ offline < 100 ms, Gemini flash-lite trung
  vị ~1,5 s.
- Mood index: không gian valence/arousal, đặc trưng âm thanh (STFT, nhịp, độ rõ xung), phân loại bằng
  LLM theo lô, hiệu chỉnh theo phân vị thư viện; đánh giá bằng `scripts/eval_mood.py` `[ĐO]`.
- Drive tối thiểu (đăng nhập, tìm, tải có xác nhận); metadata MusicBrainz (gợi ý rồi người dùng duyệt).
- An toàn: tên tệp từ Drive chỉ là dữ liệu; xác nhận trước khi tải; chế độ hàng loạt không sửa tệp.

## Chương E. Hạn chế và hướng phát triển
Crossfade ở chế độ USB; hiệu ứng cũ của Rhythm không chạy khi DSP bật; APK release không dùng được
Gemini nếu không cài qua Play (Play Integrity); Drive OAuth ở chế độ Testing; MusicBrainz chỉ miễn phí
cho mục đích phi thương mại; Android TV và Windows là hướng tiếp theo.
