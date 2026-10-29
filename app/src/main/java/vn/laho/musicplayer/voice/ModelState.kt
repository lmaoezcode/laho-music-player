// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

/** Lifecycle of the downloaded offline model. */
sealed interface ModelState {
    data object NotInstalled : ModelState

    /** [totalBytes] is 0 when unknown. */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState {
        val fraction: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    data object Verifying : ModelState
    data object Unpacking : ModelState
    data object Installed : ModelState
    data class Failed(val reason: FailureReason, val detail: String = "") : ModelState
}

enum class FailureReason { NETWORK, CHECKSUM, SIZE, STORAGE, UNPACK, CANCELLED }

sealed interface ModelEvent {
    data object StartDownload : ModelEvent
    data class Progress(val downloadedBytes: Long, val totalBytes: Long) : ModelEvent
    data object DownloadFinished : ModelEvent
    data object VerifiedOk : ModelEvent
    data object Unpacked : ModelEvent
    data class Error(val reason: FailureReason, val detail: String = "") : ModelEvent
    data object Cancel : ModelEvent
    data object Delete : ModelEvent

    /** Found a complete model on disk at start-up. */
    data object FoundOnDisk : ModelEvent
}

/** Pure reducer; illegal transitions keep the current state. */
object ModelStateMachine {

    fun reduce(state: ModelState, event: ModelEvent): ModelState = when (event) {
        ModelEvent.FoundOnDisk -> if (state is ModelState.NotInstalled || state is ModelState.Failed) ModelState.Installed else state
        ModelEvent.StartDownload -> when (state) {
            is ModelState.NotInstalled, is ModelState.Failed -> ModelState.Downloading(0, 0)
            else -> state
        }
        is ModelEvent.Progress -> if (state is ModelState.Downloading) {
            ModelState.Downloading(event.downloadedBytes, event.totalBytes)
        } else state
        ModelEvent.DownloadFinished -> if (state is ModelState.Downloading) ModelState.Verifying else state
        ModelEvent.VerifiedOk -> if (state is ModelState.Verifying) ModelState.Unpacking else state
        ModelEvent.Unpacked -> if (state is ModelState.Unpacking) ModelState.Installed else state
        is ModelEvent.Error -> when (state) {
            is ModelState.Downloading, ModelState.Verifying, ModelState.Unpacking -> ModelState.Failed(event.reason, event.detail)
            else -> state
        }
        ModelEvent.Cancel -> when (state) {
            is ModelState.Downloading, ModelState.Verifying, ModelState.Unpacking -> ModelState.NotInstalled
            else -> state
        }
        ModelEvent.Delete -> when (state) {
            ModelState.Installed, is ModelState.Failed -> ModelState.NotInstalled
            else -> state
        }
    }

    /** True while the model files are being fetched or prepared. */
    fun isBusy(state: ModelState): Boolean =
        state is ModelState.Downloading || state == ModelState.Verifying || state == ModelState.Unpacking
}
