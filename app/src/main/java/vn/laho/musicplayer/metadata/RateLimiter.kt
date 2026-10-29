// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces calls at least [intervalMs] apart, across all callers sharing the instance
 * (MusicBrainz allows one request per second per application).
 */
class RateLimiter(
    private val intervalMs: Long,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var nextAllowed = Long.MIN_VALUE

    /** Suspends until the caller may send its request. */
    suspend fun acquire() = mutex.withLock {
        val t = now()
        val start = max(t, nextAllowed)
        if (start > t) sleep(start - t)
        nextAllowed = start + intervalMs
    }

    /** Pushes the next allowed request at least [ms] into the future (server asked us to slow down). */
    suspend fun penalize(ms: Long) = mutex.withLock {
        nextAllowed = max(nextAllowed, now() + ms)
    }
}
