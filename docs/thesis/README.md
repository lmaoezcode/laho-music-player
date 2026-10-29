# Thesis material

Scripts and notes used to produce the numbers in the graduation thesis.

## Context-aware suggestions: collecting data and running the evaluation

The suggestion engine (`vn.laho.musicplayer.suggest`) ranks library songs by
mood fit for the time of day (valence/arousal from the mood index) plus what
the user really listens to: affinity per song, artist and part of the day,
minus a recency penalty and a skip penalty. All weights are named constants in
`SuggestionEngine.kt`.

### 1. Collect listening history

LaHo reuses Rhythm's own playback log (`playback_history.json`, kept for 90
days); no extra tracking is added. Every listen is stored with the time it
started and how long it was heard. A listen shorter than 25% of the song (or
30 seconds when the length is unknown) counts as a **skip**.

What to do on the phone:

1. Use the app normally for at least **2 weeks** (the more the better; 90 days
   is the maximum). Listen at different times of day, and skip songs you do not
   like: skips are the negative signal.
2. Run `/index` once in the assistant (mood index), so songs have a mood.
3. Do not clear app data in between, that deletes the log.

A session is one calendar day x one part of the day (morning 05-10,
afternoon 11-16, evening 17-21, late night 22-04). The replay needs at least 20
earlier listens before a session, so collecting a few weeks is the practical
minimum for stable numbers.

### 2. Export

In the assistant chat type:

```
/suggest eval
```

It writes three files to `Android/data/<package>/files/` and prints a summary:

| File | Content |
|---|---|
| `suggest_history.csv` | every listen: song, artist, time, hour, weekday, listened ms, fraction, skip |
| `suggest_replay.csv` | one row per (session, method, k): hits of the top-k vs. what was really played |
| `suggest_by_bucket.csv` | the engine's top 20 per part of the day and weekday/weekend, with the score terms |

Copy them to the PC, for example:

```
adb pull /sdcard/Android/data/<package>/files/suggest_history.csv
adb pull /sdcard/Android/data/<package>/files/suggest_replay.csv
adb pull /sdcard/Android/data/<package>/files/suggest_by_bucket.csv
```

(`<package>` is the applicationId of the build you installed; `/suggest` alone
shows the current top 8 without playing.)

### 3. Run the evaluation

```
python docs/thesis/scripts/eval_suggest.py --dir <folder with the CSVs> --out suggest_eval_out
```

Python 3 standard library only; `matplotlib` is optional and adds
`hit_rate.png`. Output in `--out`: `summary.csv`, `summary.md` (table to paste
in the thesis), `by_bucket.csv`, `dataset.txt` (dataset statistics) and the
chart.

### Method and metrics (for the thesis text)

Offline replay: for each session the engine only sees the history *before* the
session starts and ranks the whole library for that part of the day. The ground
truth is the set of songs the user then played in that session without
skipping. Reported per cut-off k (5, 10, 20):

* **hit rate@k**: share of sessions in which at least one played song is in the top k;
* **recall@k**: mean share of the played songs found in the top k;
* **precision@k**: mean share of the top k that was played.

Compared methods: `engine` (full), `mood_time` (cold start: mood + time of day
only, no history), `popular` (most played songs so far) and `random`
(deterministic seed). The 95% Wilson interval is printed with the hit rate
because the number of sessions is small.

Limits to state honestly: the log keeps 90 days; the engine includes a recency
penalty that lowers songs just played, which a pure "popular" baseline does
not; hits on songs the user would have played anyway do not prove that a
suggestion was better, so also report the qualitative table in
`suggest_by_bucket.csv`. An online study (accepted/skipped suggestions) is
future work.
