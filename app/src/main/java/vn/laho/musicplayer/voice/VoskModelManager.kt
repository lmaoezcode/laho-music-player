// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** The model LaHo offers for offline Vietnamese speech recognition. */
data class VoskModelSpec(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val checksumAlgorithm: String,
    val checksumHex: String,
) {
    val sizeMegabytes: Int get() = ((sizeBytes + 1_048_575) / 1_048_576).toInt()

    companion object {
        /**
         * vosk-model-small-vn-0.4 (Apache-2.0, Alpha Cephei). The MD5 and size are the values
         * published in https://alphacephei.com/vosk/models/model-list.json; the download goes over HTTPS.
         */
        val VI_SMALL = VoskModelSpec(
            name = "vosk-model-small-vn-0.4",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip",
            sizeBytes = 33_656_337L,
            checksumAlgorithm = "MD5",
            checksumHex = "b31b474a1ef75488c5fa575f8e2a1269",
        )
    }
}

/**
 * Downloads, verifies and unpacks the model into the app's private files directory
 * (`files/vosk/<name>`). The model is never bundled in the APK. State is exposed as a
 * [StateFlow] driven by [ModelStateMachine]; the work runs in an app-wide scope, so it survives
 * leaving the assistant screen.
 */
class VoskModelManager private constructor(
    context: Context,
    private val spec: VoskModelSpec = VoskModelSpec.VI_SMALL,
) {
    private val root = File(context.applicationContext.filesDir, "vosk")
    private val modelDir = File(root, spec.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    val modelPath: File get() = modelDir

    init {
        if (isInstalledOnDisk()) dispatch(ModelEvent.FoundOnDisk)
    }

    val spec_: VoskModelSpec get() = spec

    private fun isInstalledOnDisk() = File(modelDir, READY_MARKER).isFile

    @Synchronized
    private fun dispatch(event: ModelEvent) {
        _state.value = ModelStateMachine.reduce(_state.value, event)
    }

    /** Starts the download. Call only after the user confirmed the size warning. */
    @Synchronized
    fun download() {
        if (ModelStateMachine.isBusy(_state.value) || _state.value == ModelState.Installed) return
        dispatch(ModelEvent.StartDownload)
        job = scope.launch { runDownload() }
    }

    fun cancel() {
        job?.cancel()
        dispatch(ModelEvent.Cancel)
        cleanTemp()
    }

    fun delete() {
        if (ModelStateMachine.isBusy(_state.value)) return
        VoskEngine.unload()
        modelDir.deleteRecursively()
        dispatch(ModelEvent.Delete)
    }

    private fun cleanTemp() {
        File(root, "${spec.name}.zip.part").delete()
        File(root, "${spec.name}.tmp").deleteRecursively()
    }

    private suspend fun CoroutineScope.runDownload() {
        val zip = File(root, "${spec.name}.zip.part")
        try {
            root.mkdirs()
            if (root.usableSpace < spec.sizeBytes * 3) {
                dispatch(ModelEvent.Error(FailureReason.STORAGE, "need ${spec.sizeMegabytes * 3} MB free"))
                return
            }
            download(zip)
            if (!isActive) return
            dispatch(ModelEvent.DownloadFinished)
            if (zip.length() != spec.sizeBytes) {
                dispatch(ModelEvent.Error(FailureReason.SIZE, "${zip.length()} != ${spec.sizeBytes}"))
                zip.delete()
                return
            }
            val ok = zip.inputStream().buffered().use { Checksum.matches(it, spec.checksumAlgorithm, spec.checksumHex) }
            if (!ok) {
                dispatch(ModelEvent.Error(FailureReason.CHECKSUM, spec.checksumAlgorithm))
                zip.delete()
                return
            }
            dispatch(ModelEvent.VerifiedOk)
            unpack(zip)
            dispatch(ModelEvent.Unpacked)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "model install failed", e)
            val reason = if (_state.value == ModelState.Unpacking) FailureReason.UNPACK else FailureReason.NETWORK
            dispatch(ModelEvent.Error(reason, e.message.orEmpty()))
        } catch (e: Exception) {
            Log.w(TAG, "model install failed", e)
            dispatch(ModelEvent.Error(FailureReason.UNPACK, e.message.orEmpty()))
        } finally {
            zip.delete()
            File(root, "${spec.name}.tmp").deleteRecursively()
        }
    }

    private fun CoroutineScope.download(target: File) {
        val request = Request.Builder().url(spec.url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 } ?: spec.sizeBytes
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                target.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (isActive) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= REPORT_EVERY_BYTES) {
                            lastReport = done
                            dispatch(ModelEvent.Progress(done, total))
                        }
                    }
                }
            }
            dispatch(ModelEvent.Progress(done, total))
        }
    }

    private fun unpack(zip: File) {
        val tmp = File(root, "${spec.name}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val canonicalTmp = tmp.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(tmp, entry.name)
                // zip-slip guard
                if (!out.canonicalPath.startsWith(canonicalTmp)) throw IOException("bad entry ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { zin.copyTo(it) }
                }
            }
        }
        // The archive holds one folder (vosk-model-small-vn-0.4/) with am/, conf/, graph/ ...
        val inner = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: tmp
        if (!File(inner, "am").isDirectory) throw IOException("model layout not recognised")
        File(inner, READY_MARKER).writeText(spec.name)
        modelDir.deleteRecursively()
        if (!inner.renameTo(modelDir)) throw IOException("cannot move model into place")
        tmp.deleteRecursively()
    }

    companion object {
        private const val TAG = "VoskModelManager"
        private const val READY_MARKER = ".laho_ready"
        private const val REPORT_EVERY_BYTES = 256 * 1024L

        @Volatile
        private var instance: VoskModelManager? = null

        fun get(context: Context): VoskModelManager =
            instance ?: synchronized(this) {
                instance ?: VoskModelManager(context.applicationContext).also { instance = it }
            }
    }
}
