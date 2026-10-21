# Dữ liệu và bản nháp khóa luận

## Gán nhãn tâm trạng (để đo độ chính xác mood index)

1. Trên Mi 11, mở trợ lý LaHo, gõ `/index` (đợi xong) rồi `/mood export`. Lấy file
   `Android/data/vn.laho.musicplayer.debug/files/mood_index.csv` về máy, đặt vào `docs/thesis/data/`.
2. Mở `data/mood_labels_template.csv`, điền mỗi bài một dòng (copy `song_id`, `title`, `artist` từ
   `mood_index.csv`), cột `true_quadrant` là một trong:
   - `Q1` vui, sôi động (valence cao, arousal cao)
   - `Q2` căng thẳng, dữ dội (valence thấp, arousal cao)
   - `Q3` buồn, u ám (valence thấp, arousal thấp)
   - `Q4` thư giãn, bình yên (valence cao, arousal thấp)
   Nên gán theo cảm nhận khi nghe, trước khi xem nhãn của máy. Khoảng 50 bài, cân bằng 4 ô.
3. Chạy:

```bash
python docs/thesis/scripts/eval_mood.py docs/thesis/data/mood_index.csv docs/thesis/data/mood_labels_template.csv
```

Script in accuracy, ma trận nhầm lẫn 4 ô và so sánh arousal tuyệt đối với arousal đã hiệu chỉnh.

## Chương kỹ thuật (bản nháp, tiếng Việt)

Nằm trong `chapters/`. Khi có mẫu của trường, nội dung sẽ được chuyển sang mẫu đó.
