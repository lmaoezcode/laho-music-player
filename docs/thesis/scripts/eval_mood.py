#!/usr/bin/env python3
"""Đánh giá mood index so với nhãn gán tay.

Dùng: eval_mood.py mood_index.csv labels.csv
Chỉ dùng thư viện chuẩn của Python.
"""
import csv
import sys

QUADS = ["Q1", "Q2", "Q3", "Q4"]
NAMES = {"Q1": "vui/sôi động", "Q2": "căng thẳng", "Q3": "buồn", "Q4": "thư giãn"}


def quadrant(valence, arousal):
    if valence >= 0:
        return "Q1" if arousal >= 0 else "Q4"
    return "Q2" if arousal >= 0 else "Q3"


def load(path):
    with open(path, encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def fnum(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def report(name, pairs):
    n = len(pairs)
    if n == 0:
        print(f"{name}: không có dữ liệu")
        return
    ok = sum(1 for t, p in pairs if t == p)
    print(f"\n== {name}: accuracy {ok}/{n} = {ok / n:.1%}")
    print("hàng = nhãn thật, cột = dự đoán")
    print("      " + "".join(f"{q:>5}" for q in QUADS) + "   (đúng/tổng)")
    for t in QUADS:
        row = [sum(1 for tt, p in pairs if tt == t and p == q) for q in QUADS]
        tot = sum(row)
        print(f"{t:>4}  " + "".join(f"{v:>5}" for v in row) + f"   {row[QUADS.index(t)]}/{tot}  {NAMES[t]}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    index = {r["song_id"]: r for r in load(sys.argv[1])}
    labels = [r for r in load(sys.argv[2]) if r.get("true_quadrant", "").strip() in QUADS]
    missing = [r["song_id"] for r in labels if r["song_id"] not in index]
    if missing:
        print("Cảnh báo: không có trong mood_index.csv:", ", ".join(missing[:10]))
    combined, audio_cal, audio_abs = [], [], []
    for r in labels:
        m = index.get(r["song_id"])
        if not m:
            continue
        t = r["true_quadrant"].strip()
        v, a = fnum(m.get("combined_valence")), fnum(m.get("combined_arousal"))
        if v is not None and a is not None:
            combined.append((t, quadrant(v, a)))
        # Arousal-only comparison: high/low arousal vs the label's arousal half.
        want_high = t in ("Q1", "Q2")
        for lst, key in ((audio_cal, "arousal_audio_calibrated"), (audio_abs, "arousal_audio_abs")):
            x = fnum(m.get(key))
            if x is not None:
                lst.append((want_high, x >= 0.5))
    report("Kết hợp audio + LLM (4 ô)", combined)
    for name, lst in (("Arousal âm thanh, đã hiệu chỉnh", audio_cal), ("Arousal âm thanh, thang tuyệt đối", audio_abs)):
        if lst:
            ok = sum(1 for w, p in lst if w == p)
            print(f"\n== {name}: đúng nửa cao/thấp {ok}/{len(lst)} = {ok / len(lst):.1%}")


if __name__ == "__main__":
    main()
