#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
# SPDX-License-Identifier: GPL-3.0-or-later
"""Evaluate LaHo's context-aware suggestions from the CSV files written by `/suggest eval`.

Inputs (copy them from Android/data/<package>/files/ on the phone, see docs/thesis/README.md):
  suggest_replay.csv     one row per (session, method, k): hits of the top-k against what was really played
  suggest_history.csv    raw listening events (used for dataset statistics)
  suggest_by_bucket.csv  the engine's top-20 per time bucket and weekday/weekend (qualitative table)

Outputs (in --out, default ./suggest_eval_out): summary.csv, summary.md, by_bucket.csv, dataset.txt and,
when matplotlib is installed, hit_rate.png.

Only the Python standard library is required. Usage:
  python eval_suggest.py --dir path/to/csvs [--out out_dir] [--ks 5,10,20]
"""
import argparse
import csv
import math
import os
import sys
from collections import defaultdict

METHOD_LABEL = {
    "engine": "LaHo (mood + giờ + lịch sử)",
    "mood_time": "Chỉ mood + giờ (cold start)",
    "popular": "Phổ biến nhất",
    "random": "Ngẫu nhiên",
}
METHOD_ORDER = ["engine", "mood_time", "popular", "random"]
BUCKETS = ["sang", "chieu", "toi", "khuya"]


def read_csv(path):
    if not os.path.exists(path):
        return []
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def wilson(hits, n, z=1.96):
    """95% Wilson interval for a proportion; reported next to hit rate because sessions are few."""
    if n == 0:
        return (float("nan"), float("nan"))
    p = hits / n
    d = 1 + z * z / n
    centre = (p + z * z / (2 * n)) / d
    half = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (max(0.0, centre - half), min(1.0, centre + half))


def summarize(rows, ks, bucket=None):
    """Per (method, k): sessions, hit rate (+CI), mean recall and precision."""
    groups = defaultdict(list)
    for r in rows:
        if bucket and r["bucket"] != bucket:
            continue
        k = int(r["k"])
        if k in ks:
            groups[(r["method"], k)].append(r)
    out = []
    for (method, k), rs in groups.items():
        n = len(rs)
        hits = sum(1 for r in rs if int(r["hits"]) > 0)
        lo, hi = wilson(hits, n)
        out.append({
            "method": method, "k": k, "sessions": n, "hit_rate": hits / n, "ci_low": lo, "ci_high": hi,
            "recall": mean([int(r["hits"]) / int(r["truth_size"]) for r in rs]),
            "precision": mean([int(r["hits"]) / k for r in rs]),
        })
    out.sort(key=lambda s: (METHOD_ORDER.index(s["method"]) if s["method"] in METHOD_ORDER else 99, s["k"]))
    return out


def write_summary(path_csv, path_md, summary, by_bucket):
    with open(path_csv, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["scope", "method", "k", "sessions", "hit_rate", "ci_low", "ci_high", "recall", "precision"])
        for scope, rows in [("all", summary)] + [(b, s) for b, s in by_bucket.items()]:
            for s in rows:
                w.writerow([scope, s["method"], s["k"], s["sessions"], f"{s['hit_rate']:.4f}", f"{s['ci_low']:.4f}",
                            f"{s['ci_high']:.4f}", f"{s['recall']:.4f}", f"{s['precision']:.4f}"])
    lines = ["| Phương pháp | k | Phiên | Hit rate@k | 95% CI | Recall@k | Precision@k |", "|---|---|---|---|---|---|---|"]
    for s in summary:
        lines.append(f"| {METHOD_LABEL.get(s['method'], s['method'])} | {s['k']} | {s['sessions']} | {s['hit_rate']:.1%} | "
                     f"{s['ci_low']:.1%}–{s['ci_high']:.1%} | {s['recall']:.1%} | {s['precision']:.1%} |")
    with open(path_md, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    return lines


def dataset_stats(history, replay):
    lines = []
    if history:
        skips = sum(1 for h in history if h["skip"] == "true" or h["skip"] == "True")
        days = {h["iso_time"][:10] for h in history}
        songs = {h["song_id"] for h in history}
        per_bucket = defaultdict(int)
        for h in history:
            hour = int(h["hour"])
            per_bucket["sang" if 5 <= hour <= 10 else "chieu" if 11 <= hour <= 16 else "toi" if 17 <= hour <= 21 else "khuya"] += 1
        lines += [
            f"Lượt nghe: {len(history)} (bỏ qua: {skips}, {skips / len(history):.1%})",
            f"Số ngày có dữ liệu: {len(days)}; số bài khác nhau: {len(songs)}",
            "Lượt nghe theo buổi: " + ", ".join(f"{b}={per_bucket[b]}" for b in BUCKETS),
        ]
    sessions = {(r["date"], r["bucket"]) for r in replay}
    lines.append(f"Phiên (ngày x buổi) dùng để phát lại: {len(sessions)}")
    return lines


def plot(summary, path):
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib không có: bỏ qua hit_rate.png", file=sys.stderr)
        return
    ks = sorted({s["k"] for s in summary})
    methods = [m for m in METHOD_ORDER if any(s["method"] == m for s in summary)]
    width = 0.8 / max(1, len(methods))
    fig, ax = plt.subplots(figsize=(7, 4))
    for i, m in enumerate(methods):
        vals = {s["k"]: s for s in summary if s["method"] == m}
        xs = [j + i * width for j in range(len(ks))]
        hs = [vals[k]["hit_rate"] * 100 if k in vals else 0 for k in ks]
        err = [[(vals[k]["hit_rate"] - vals[k]["ci_low"]) * 100 if k in vals else 0 for k in ks],
               [(vals[k]["ci_high"] - vals[k]["hit_rate"]) * 100 if k in vals else 0 for k in ks]]
        ax.bar(xs, hs, width, yerr=err, capsize=2, label=METHOD_LABEL.get(m, m))
    ax.set_xticks([j + width * (len(methods) - 1) / 2 for j in range(len(ks))])
    ax.set_xticklabels([f"k={k}" for k in ks])
    ax.set_ylabel("Hit rate (%)")
    ax.set_ylim(0, 100)
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=200)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="folder with the CSV files exported by /suggest eval")
    ap.add_argument("--out", default="suggest_eval_out")
    ap.add_argument("--ks", default="5,10,20")
    args = ap.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")  # Windows consoles default to cp1252
    ks = [int(k) for k in args.ks.split(",")]

    replay = read_csv(os.path.join(args.dir, "suggest_replay.csv"))
    history = read_csv(os.path.join(args.dir, "suggest_history.csv"))
    by_bucket_rows = read_csv(os.path.join(args.dir, "suggest_by_bucket.csv"))
    if not replay:
        print("Không có suggest_replay.csv hoặc file rỗng: chưa đủ lịch sử nghe để phát lại "
              "(xem docs/thesis/README.md).", file=sys.stderr)
        return 1
    os.makedirs(args.out, exist_ok=True)

    summary = summarize(replay, ks)
    by_bucket = {b: summarize(replay, ks, b) for b in BUCKETS}
    by_bucket = {b: s for b, s in by_bucket.items() if s}
    table = write_summary(os.path.join(args.out, "summary.csv"), os.path.join(args.out, "summary.md"), summary, by_bucket)
    stats = dataset_stats(history, replay)
    with open(os.path.join(args.out, "dataset.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(stats) + "\n")
    if by_bucket_rows:
        with open(os.path.join(args.out, "by_bucket.csv"), "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(by_bucket_rows[0].keys()))
            w.writeheader()
            w.writerows(by_bucket_rows)
    plot(summary, os.path.join(args.out, "hit_rate.png"))

    print("\n".join(stats))
    print()
    print("\n".join(table))
    for b, s in by_bucket.items():
        e = [x for x in s if x["method"] == "engine" and x["k"] == ks[min(1, len(ks) - 1)]]
        if e:
            print(f"  buổi {b}: {e[0]['sessions']} phiên, hit rate@{e[0]['k']} = {e[0]['hit_rate']:.1%}")
    print(f"\nĐã ghi kết quả vào {args.out}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
