// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.metadata

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RateLimiterTest {
    private var clock = 10_000L
    private val sleeps = mutableListOf<Long>()
    private fun limiter(interval: Long = 1_000) =
        RateLimiter(interval, now = { clock }, sleep = { sleeps += it; clock += it })

    @Test
    fun firstCallIsImmediateThenOneSecondApart() = runBlocking {
        val l = limiter()
        l.acquire()
        l.acquire()
        l.acquire()
        assertEquals(listOf(1_000L, 1_000L), sleeps)
        assertEquals(12_000L, clock)
    }

    @Test
    fun noWaitWhenEnoughTimeHasPassed() = runBlocking {
        val l = limiter()
        l.acquire()
        clock += 5_000
        l.acquire()
        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun partialElapsedTimeOnlyWaitsTheRemainder() = runBlocking {
        val l = limiter()
        l.acquire()
        clock += 400
        l.acquire()
        assertEquals(listOf(600L), sleeps)
    }

    @Test
    fun concurrentCallersAreSerialised() = runBlocking {
        val l = limiter()
        (1..4).map { async { l.acquire() } }.awaitAll()
        assertEquals(listOf(1_000L, 1_000L, 1_000L), sleeps)
    }

    @Test
    fun penaltyDelaysNextRequest() = runBlocking {
        val l = limiter()
        l.acquire()
        l.penalize(5_000)
        l.acquire()
        assertEquals(listOf(5_000L), sleeps)
    }
}
