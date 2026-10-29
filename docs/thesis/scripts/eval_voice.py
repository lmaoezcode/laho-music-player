#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
# SPDX-License-Identifier: GPL-3.0-or-later
"""Compare LaHo's speech engines on the 30 sample commands.

Input CSV (see docs/thesis/data/voice_commands_template.csv), one row per (command, engine) trial:
  id               number of the command
  spoken_text      what you read aloud (the reference)
  engine           vosk | system | keyboard   (any label is accepted; "vosk_offline" etc.)
  recognised_text  what the app put in the chat after speaking (what the recogniser returned)
  correct          optional, 1/0 or y/n: did the assistant do the right thing? If empty it is
                   derived from the exact match.

To compare engines, copy each row once per engine you tested and fill in `engine` and
`recognised_text`. Rows with an empty `recognised_text` are skipped (not yet recorded).

Metrics per engine:
  exact-match rate   recognised == reference after normalisation (lower-case, no punctuation,
                     NFC; --fold also strips Vietnamese diacritics)
  WER                word error rate = (S + D + I) / N over all rows (micro-average), plus the
                     per-row mean
  command success    share of rows with correct = 1 (only when that column is filled)

Outputs: a table on stdout and, with --out DIR, summary.csv and summary.md.
Only the Python standard library is required.

Usage:
  python eval_voice.py voice_commands.csv [--out out_dir] [--fold]
"""
import argparse
import csv
import os
import re
import sys
import unicodedata
from collections import OrderedDict

SPOKEN_NUMBERS = {
    "không": "0", "một": "1", "hai": "2", "ba": "3", "bốn": "4", "năm": "5",
    "sáu": "6", "bảy": "7", "tám": "8", "chín": "9",
}


def normalise(text, fold=False):
    """Lower-case, NFC, drop punctuation and collapse spaces; optionally strip diacritics."""
    text = unicodedata.normalize("NFC", text or "").lower()
    if fold:
        text = text.replace("đ", "d")
        text = "".join(
            c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn"
        )
    text = re.sub(r"[^\w\s%]", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def words(text):
    return text.split() if text else []


def edit_distance(ref, hyp):
    """Word-level Levenshtein distance."""
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def parse_bool(value):
    v = (value or "").strip().lower()
    if v in ("1", "y", "yes", "true", "x", "đúng", "dung"):
        return True
    if v in ("0", "n", "no", "false", "sai"):
        return False
    return None


def load(path):
    with open(path, encoding="utf-8-sig", newline="") as fh:
        return list(csv.DictReader(fh))


def evaluate(rows, fold=False):
    """Returns an OrderedDict engine -> metrics dict."""
    stats = OrderedDict()
    for row in rows:
        hyp_raw = (row.get("recognised_text") or "").strip()
        if not hyp_raw:
            continue
        engine = (row.get("engine") or "").strip() or "?"
        ref = words(normalise(row.get("spoken_text", ""), fold))
        hyp = words(normalise(hyp_raw, fold))
        s = stats.setdefault(
            engine,
            {"n": 0, "exact": 0, "ref_words": 0, "errors": 0, "row_wers": [], "judged": 0, "correct": 0},
        )
        dist = edit_distance(ref, hyp)
        s["n"] += 1
        s["exact"] += int(ref == hyp)
        s["ref_words"] += len(ref)
        s["errors"] += dist
        s["row_wers"].append(dist / len(ref) if ref else (0.0 if not hyp else 1.0))
        verdict = parse_bool(row.get("correct"))
        if verdict is not None:
            s["judged"] += 1
            s["correct"] += int(verdict)
    result = OrderedDict()
    for engine, s in stats.items():
        result[engine] = {
            "samples": s["n"],
            "exact_match": s["exact"] / s["n"],
            "wer": s["errors"] / s["ref_words"] if s["ref_words"] else 0.0,
            "wer_mean_per_row": sum(s["row_wers"]) / len(s["row_wers"]),
            "command_success": (s["correct"] / s["judged"]) if s["judged"] else None,
            "judged": s["judged"],
        }
    return result


def fmt_pct(value):
    return "-" if value is None else f"{100 * value:.1f}%"


def render_table(result):
    header = ["engine", "samples", "exact match", "WER", "WER (mean/row)", "command success"]
    lines = [header]
    for engine, m in result.items():
        lines.append([
            engine, str(m["samples"]), fmt_pct(m["exact_match"]), fmt_pct(m["wer"]),
            fmt_pct(m["wer_mean_per_row"]),
            fmt_pct(m["command_success"]) + (f" ({m['judged']})" if m["judged"] else ""),
        ])
    widths = [max(len(r[i]) for r in lines) for i in range(len(header))]
    return "\n".join("  ".join(c.ljust(w) for c, w in zip(r, widths)) for r in lines)


def write_outputs(result, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "summary.csv"), "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["engine", "samples", "exact_match", "wer", "wer_mean_per_row", "command_success", "judged"])
        for engine, m in result.items():
            w.writerow([
                engine, m["samples"], f"{m['exact_match']:.4f}", f"{m['wer']:.4f}",
                f"{m['wer_mean_per_row']:.4f}",
                "" if m["command_success"] is None else f"{m['command_success']:.4f}", m["judged"],
            ])
    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as fh:
        fh.write("| Engine | Mẫu | Khớp chính xác | WER | WER TB/câu | Lệnh đúng |\n")
        fh.write("|---|---|---|---|---|---|\n")
        for engine, m in result.items():
            fh.write(
                f"| {engine} | {m['samples']} | {fmt_pct(m['exact_match'])} | {fmt_pct(m['wer'])} | "
                f"{fmt_pct(m['wer_mean_per_row'])} | {fmt_pct(m['command_success'])} |\n"
            )


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", help="filled-in voice_commands CSV")
    ap.add_argument("--out", help="directory for summary.csv / summary.md")
    ap.add_argument("--fold", action="store_true", help="ignore Vietnamese diacritics when comparing")
    args = ap.parse_args(argv)

    result = evaluate(load(args.csv), fold=args.fold)
    if not result:
        print("No recorded rows (recognised_text is empty everywhere).", file=sys.stderr)
        return 1
    print(render_table(result))
    if args.out:
        write_outputs(result, args.out)
        print(f"\nWritten to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
