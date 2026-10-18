// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import vn.laho.musicplayer.assistant.AssistantReply
import vn.laho.musicplayer.assistant.AssistantTool
import vn.laho.musicplayer.assistant.OnlineAssistant
import vn.laho.musicplayer.assistant.ToolCall
import vn.laho.musicplayer.assistant.ToolParameter
import vn.laho.musicplayer.assistant.ToolRegistry
import vn.laho.musicplayer.assistant.ToolResult

/** One model request: returns the model's turn for the given conversation. */
fun interface ModelBackend {
    suspend fun generate(systemInstruction: String, tools: ToolRegistry, history: List<Content>): Content
}

/** [ModelBackend] backed by Firebase AI Logic (Gemini Developer API). */
class FirebaseModelBackend(val modelName: String) : ModelBackend {
    private var cached: GenerativeModel? = null
    private var cachedKey: Pair<String, ToolRegistry>? = null

    override suspend fun generate(systemInstruction: String, tools: ToolRegistry, history: List<Content>): Content {
        val key = systemInstruction to tools
        val model = cached?.takeIf { cachedKey == key }
            ?: Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                tools = listOf(Tool.functionDeclarations(tools.tools.map(GeminiAssistant::declarationOf))),
                systemInstruction = content { text(systemInstruction) },
            ).also { cached = it; cachedKey = key }
        val response = model.generateContent(history)
        return response.candidates.firstOrNull()?.content
            ?: error("Gemini returned no candidates")
    }
}

/**
 * Online assistant: Gemini with function calling.
 *
 * Every [AssistantTool] is declared to the model and its calls run through
 * [ToolRegistry.execute], exactly like offline commands. Latency optimisations:
 *
 * 1. A compact library summary (counts, top genres/artists) is embedded in the system
 *    instruction, so mood requests can go straight to `search_and_play` without a
 *    `get_library_overview` round trip.
 * 2. When every tool call in a model turn is an *action* that succeeded (play, pause,
 *    volume…), the tool's own message is the answer: no extra request is made for the model
 *    to rephrase it. A synthetic model turn is appended to the history so the conversation
 *    stays valid (every function call is still followed by its response).
 *
 * Only the user's text, the library summary (artists, genres) and tool results are sent.
 */
class GeminiAssistant(
    private val backend: ModelBackend,
    private val clock: () -> Long = System::currentTimeMillis,
) : OnlineAssistant {

    private val history = mutableListOf<Content>()
    private var librarySummary: String? = null
    private var summaryAt = 0L

    /** Number of model requests made by the last [handle] call (for benchmarks/logs). */
    var lastModelRounds = 0
        private set

    override suspend fun handle(text: String, tools: ToolRegistry): AssistantReply {
        lastModelRounds = 0
        return try {
            converse(text, tools)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini request failed", e)
            rollbackToLastCompleteTurn()
            AssistantReply(friendlyError(e), AssistantReply.Source.NONE)
        }
    }

    /** Forgets the conversation and the cached library summary. */
    fun reset() {
        history.clear()
        librarySummary = null
    }

    private suspend fun converse(text: String, tools: ToolRegistry): AssistantReply {
        val system = SYSTEM_INSTRUCTION + "\n\n" + summary(tools)
        trimHistory()
        history += Content("user", listOf(TextPart(text)))

        var lastCall: ToolCall? = null
        var lastResult: ToolResult? = null
        repeat(MAX_TOOL_ROUNDS) {
            val modelTurn = backend.generate(system, tools, history.toList())
            lastModelRounds++
            history += modelTurn

            val calls = modelTurn.parts.filterIsInstance<FunctionCallPart>()
            if (calls.isEmpty()) {
                val answer = modelTurn.parts.filterIsInstance<TextPart>()
                    .filterNot { it.isThought }
                    .joinToString("") { it.text }.trim()
                    .ifEmpty { lastResult?.message ?: "Xong." }
                return AssistantReply(answer, AssistantReply.Source.ONLINE, lastCall, lastResult)
            }

            val executed = calls.map { fc ->
                val call = ToolCall(fc.name, JsonArgs.toKotlin(fc.args))
                val result = tools.execute(call)
                Log.i(TAG, "tool ${call.name}${call.args} -> ${result.success}: ${result.message}")
                lastCall = call
                lastResult = result
                Triple(fc, call, result)
            }
            history += Content(
                "user",
                executed.map { (fc, _, result) -> FunctionResponsePart(fc.name, JsonArgs.resultToJson(result), fc.id) },
            )

            if (executed.all { (_, call, result) -> result.success && call.name in ACTION_TOOLS }) {
                // Done: the action already happened and its message is user-facing.
                val answer = executed.joinToString(" ") { it.third.message }
                history += Content("model", listOf(TextPart(answer)))
                return AssistantReply(answer, AssistantReply.Source.ONLINE, lastCall, lastResult)
            }
        }
        return AssistantReply(
            lastResult?.message ?: "Mình đã thử vài bước nhưng chưa xong, bạn nói rõ hơn nhé.",
            AssistantReply.Source.ONLINE,
            lastCall,
            lastResult,
        )
    }

    private suspend fun summary(tools: ToolRegistry): String {
        val now = clock()
        librarySummary?.takeIf { now - summaryAt < SUMMARY_TTL_MS }?.let { return it }
        val overview = tools.execute(ToolCall("get_library_overview", mapOf("limit" to SUMMARY_LIMIT)))
        val text = if (!overview.success) {
            "Thư viện nhạc: ${overview.message}"
        } else buildString {
            append("Thư viện nhạc trên máy: ${overview.data["songCount"]} bài.")
            (overview.data["topGenres"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { append("\nThể loại: ${it.joinToString(", ")}.") }
                ?: append("\nThể loại: (không có thông tin thể loại).")
            (overview.data["topArtists"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { append("\nNghệ sĩ: ${it.joinToString(", ")}.") }
            (overview.data["sampleTitles"] as? List<*>)?.takeIf { it.isNotEmpty() }
                ?.let { append("\nVài bài mẫu: ${it.take(SAMPLE_TITLES).joinToString("; ")}.") }
        }
        librarySummary = text
        summaryAt = now
        return text
    }

    /** Keeps the last turns, always starting at a plain user message (never mid tool exchange). */
    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        var start = history.size - MAX_HISTORY
        while (start < history.size && !isUserText(history[start])) start++
        repeat(start) { history.removeAt(0) }
    }

    /** After a failure, drop the unfinished exchange so the next request starts cleanly. */
    private fun rollbackToLastCompleteTurn() {
        val lastUserText = history.indexOfLast(::isUserText)
        if (lastUserText >= 0) {
            while (history.size > lastUserText) history.removeAt(history.lastIndex)
        }
    }

    private fun isUserText(c: Content) = c.role == "user" && c.parts.all { it is TextPart }

    companion object {
        private const val TAG = "LahoGemini"

        /** Latest stable Flash (no Blaze plan needed, Oct 2026). */
        const val MODEL_FLASH = "gemini-3.8-flash"

        /** Latest stable Flash-Lite: cheaper and usually faster. */
        const val MODEL_FLASH_LITE = "gemini-3.5-flash-lite"

        /**
         * Benchmark on Galaxy J5 Prime (10 prompts, dry run): flash-lite median 1.4 s vs flash
         * 7.3 s, both 1 model round per prompt; flash-lite chosen for voice-style commands.
         */
        const val DEFAULT_MODEL = MODEL_FLASH_LITE

        private const val MAX_TOOL_ROUNDS = 5
        private const val MAX_HISTORY = 20
        private const val SUMMARY_TTL_MS = 5 * 60_000L
        private const val SUMMARY_LIMIT = 15
        private const val SAMPLE_TITLES = 10

        /** Tools whose successful result is a complete answer (no rephrasing round needed). */
        private val ACTION_TOOLS = setOf("playback_control", "set_volume", "search_and_play", "get_now_playing")

        private val SYSTEM_INSTRUCTION = """
            Bạn là trợ lý của ứng dụng nghe nhạc "LaHo Music Player" trên điện thoại.
            - Trả lời ngắn gọn (1-2 câu) bằng ngôn ngữ người dùng đang dùng, mặc định tiếng Việt.
            - Chỉ hành động thông qua các công cụ được cung cấp; không giả vờ đã làm điều gì.
            - Nhạc chỉ phát từ thư viện trên máy (tóm tắt bên dưới). Không bịa tên bài.
            - Yêu cầu theo tâm trạng, cảm xúc hay hoạt động (buồn, vui, tập gym, học bài, ngủ…):
              chọn ngay thể loại hoặc nghệ sĩ phù hợp trong tóm tắt thư viện và gọi search_and_play
              (genre/artist/query). Chỉ gọi get_library_overview khi tóm tắt không đủ. Nếu không tìm
              thấy, thử tối đa 2 lựa chọn khác rồi báo lại.
            - QUAN TRỌNG: chỉ phát khi thể loại/nghệ sĩ thật sự hợp với tâm trạng được yêu cầu.
              Nếu thư viện không có nhạc phù hợp (ví dụ cần nhạc sôi động mà chỉ có nhạc nhẹ, hoặc
              không có thông tin thể loại để biết), KHÔNG gọi search_and_play. Thay vào đó nói ngắn
              gọn là chưa có nhạc hợp, nêu 1-2 thể loại/nghệ sĩ gần nhất đang có và hỏi người dùng
              có muốn nghe không. Không bao giờ phát đại một bài chỉ vì nó là bài duy nhất.
            - Âm lượng dùng set_volume (level 0-100 hoặc delta).
            - Tính năng chưa có (ví dụ tải nhạc từ Google Drive, hẹn giờ tắt): nói rõ là chưa hỗ trợ,
              không gọi công cụ.
        """.trimIndent()

        /** Returns null when this build has no Firebase configuration. */
        fun createOrNull(context: Context, modelName: String = DEFAULT_MODEL): GeminiAssistant? =
            if (LahoFirebase.ensure(context)) GeminiAssistant(FirebaseModelBackend(modelName)) else null

        internal fun declarationOf(tool: AssistantTool): FunctionDeclaration = FunctionDeclaration(
            name = tool.name,
            description = tool.description,
            parameters = tool.parameters.associate { it.name to schemaOf(it) },
            optionalParameters = tool.parameters.filterNot { it.required }.map { it.name },
        )

        private fun schemaOf(p: ToolParameter): Schema = when {
            p.enumValues.isNotEmpty() -> Schema.enumeration(p.enumValues, p.description)
            p.type == ToolParameter.Type.INTEGER -> Schema.integer(p.description)
            else -> Schema.string(p.description)
        }

        internal fun isQuotaError(e: Throwable): Boolean {
            val lower = (e.message ?: "").lowercase()
            return "quota" in lower || "429" in lower || "resource exhausted" in lower || "rate limit" in lower
        }

        private fun friendlyError(e: Exception): String {
            val msg = e.message ?: e::class.simpleName.orEmpty()
            val lower = msg.lowercase()
            return when {
                e is IOException || e.cause is IOException || "unable to resolve host" in lower ->
                    "Không kết nối được trợ lý online. Kiểm tra mạng rồi thử lại."
                "app check" in lower || "appcheck" in lower || "attestation" in lower ->
                    "Trợ lý online bị từ chối (App Check). Hãy thêm debug token của máy này trong " +
                        "Firebase Console → Security → App Check → ⋮ → Manage debug tokens " +
                        "(token nằm trong logcat, tag DebugAppCheckProvider)."
                isQuotaError(e) -> "Trợ lý online đang hết lượt miễn phí, thử lại sau ít phút."
                else -> "Trợ lý online gặp lỗi: $msg"
            }
        }
    }
}
