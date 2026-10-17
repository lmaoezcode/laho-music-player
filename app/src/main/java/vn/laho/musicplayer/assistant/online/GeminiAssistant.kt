// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.assistant.online

import android.content.Context
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
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

/**
 * Online assistant: Gemini (Firebase AI Logic, Gemini Developer API) with function calling.
 *
 * Every [AssistantTool] in the registry is declared to the model, and the model can act only
 * by calling them; the calls run through [ToolRegistry.execute], exactly like offline
 * commands. Only the user's text and tool results (song titles, artists, genres) are sent.
 */
class GeminiAssistant private constructor(
    private val modelName: String,
) : OnlineAssistant {

    private var model: GenerativeModel? = null
    private var chat: Chat? = null
    private var modelTools: ToolRegistry? = null

    override suspend fun handle(text: String, tools: ToolRegistry): AssistantReply {
        return try {
            converse(text, tools)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Gemini request failed", e)
            AssistantReply(friendlyError(e), AssistantReply.Source.NONE)
        }
    }

    /** Forgets the conversation (the next message starts a fresh chat). */
    fun reset() {
        chat = null
    }

    private suspend fun converse(text: String, tools: ToolRegistry): AssistantReply {
        val chat = chatFor(tools)
        var response = chat.sendMessage(text)
        var lastCall: ToolCall? = null
        var lastResult: ToolResult? = null

        repeat(MAX_TOOL_ROUNDS) {
            val calls = response.functionCalls
            if (calls.isEmpty()) {
                val answer = response.text?.trim().orEmpty()
                    .ifEmpty { lastResult?.message ?: "Xong." }
                return AssistantReply(answer, AssistantReply.Source.ONLINE, lastCall, lastResult)
            }
            val responses = calls.map { fc ->
                val call = ToolCall(fc.name, JsonArgs.toKotlin(fc.args))
                val result = tools.execute(call)
                Log.i(TAG, "tool ${call.name}${call.args} -> ${result.success}: ${result.message}")
                lastCall = call
                lastResult = result
                FunctionResponsePart(fc.name, JsonArgs.resultToJson(result))
            }
            response = chat.sendMessage(content("user") { responses.forEach { part(it) } })
        }
        return AssistantReply(
            lastResult?.message ?: "Mình đã thử vài bước nhưng chưa xong, bạn nói rõ hơn nhé.",
            AssistantReply.Source.ONLINE,
            lastCall,
            lastResult,
        )
    }

    private fun chatFor(tools: ToolRegistry): Chat {
        if (modelTools !== tools || model == null) {
            model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                tools = listOf(Tool.functionDeclarations(tools.tools.map(::declarationOf))),
                systemInstruction = content { text(SYSTEM_INSTRUCTION) },
            )
            modelTools = tools
            chat = null
        }
        return chat ?: model!!.startChat().also { chat = it }
    }

    companion object {
        private const val TAG = "LahoGemini"

        /** Latest stable Flash model available without the Blaze plan (Oct 2026). */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val MAX_TOOL_ROUNDS = 5

        private val SYSTEM_INSTRUCTION = """
            Bạn là trợ lý của ứng dụng nghe nhạc "LaHo Music Player" trên điện thoại.
            - Trả lời ngắn gọn (1-2 câu) bằng ngôn ngữ người dùng đang dùng, mặc định tiếng Việt.
            - Chỉ hành động thông qua các công cụ được cung cấp; không giả vờ đã làm điều gì.
            - Nhạc chỉ phát từ thư viện trên máy. Không bịa tên bài không có trong thư viện.
            - Khi người dùng muốn nghe theo tâm trạng, cảm xúc hay hoạt động (buồn, vui, tập gym,
              học bài, ngủ…): gọi get_library_overview, chọn thể loại hoặc nghệ sĩ phù hợp có trong
              kết quả, rồi gọi search_and_play với genre/artist/query tương ứng. Nếu không tìm thấy,
              thử tối đa 2 lựa chọn khác rồi báo lại.
            - Lệnh âm lượng dùng set_volume (level 0-100 hoặc delta).
            - Tính năng chưa có (ví dụ tải nhạc từ Google Drive): nói rõ là chưa hỗ trợ.
        """.trimIndent()

        /** Returns null when this build has no Firebase configuration. */
        fun createOrNull(context: Context, modelName: String = DEFAULT_MODEL): GeminiAssistant? =
            if (LahoFirebase.ensure(context)) GeminiAssistant(modelName) else null

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

        private fun friendlyError(e: Exception): String {
            val msg = (e.message ?: e::class.simpleName.orEmpty())
            val lower = msg.lowercase()
            return when {
                e is IOException || e.cause is IOException || "unable to resolve host" in lower ->
                    "Không kết nối được trợ lý online. Kiểm tra mạng rồi thử lại."
                "app check" in lower || "appcheck" in lower || "attestation" in lower ||
                    "403" in lower || "permission" in lower ->
                    "Trợ lý online bị từ chối (App Check). Hãy thêm debug token của máy này trong " +
                        "Firebase Console → Security → App Check → ⋮ → Manage debug tokens " +
                        "(token nằm trong logcat, tag DebugAppCheckProvider)."
                "quota" in lower || "429" in lower || "resource exhausted" in lower ->
                    "Trợ lý online đang hết lượt miễn phí, thử lại sau ít phút."
                else -> "Trợ lý online gặp lỗi: $msg"
            }
        }
    }
}
