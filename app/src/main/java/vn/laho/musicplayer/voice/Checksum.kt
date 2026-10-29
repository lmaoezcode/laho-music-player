// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

import java.io.InputStream
import java.security.MessageDigest

/** Streaming checksum verification for downloaded files. */
object Checksum {

    /** Hex digest of [input] (read fully; the stream is not closed). */
    fun hex(input: InputStream, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Case-insensitive comparison of the digest of [input] with [expectedHex]. */
    fun matches(input: InputStream, algorithm: String, expectedHex: String): Boolean =
        hex(input, algorithm).equals(expectedHex.trim(), ignoreCase = true)
}
