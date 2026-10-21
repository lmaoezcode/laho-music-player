// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

/**
 * Minimal Google Drive REST v3 client: `files.list` for audio files by name and
 * `files.get?alt=media` streaming download. Read-only (`drive.readonly` scope).
 *
 * The access token is sent only in the Authorization header and never logged (the shared
 * OkHttp logging interceptor is deliberately not used here). On 401 the token is refreshed
 * once through [tokens]; if that is impossible [DriveAuthRequiredException] is thrown.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val tokens: DriveTokenSource,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
) {

    suspend fun search(nameQuery: String, limit: Int): List<DriveFile> {
        val url = baseUrl.newBuilder()
            .addPathSegments("drive/v3/files")
            .addQueryParameter("q", audioNameQuery(nameQuery))
            .addQueryParameter("fields", "files($FIELDS)")
            .addQueryParameter("pageSize", limit.coerceIn(1, MAX_PAGE).toString())
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("spaces", "drive")
            .build()
        val json = getJson(url)
        val files = json.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).mapNotNull { parseFile(files.optJSONObject(it)) }
    }

    suspend fun file(id: String): DriveFile {
        val url = fileUrl(id).newBuilder().addQueryParameter("fields", FIELDS).build()
        return parseFile(getJson(url)) ?: throw DriveException("Drive trả về dữ liệu không hợp lệ")
    }

    /**
     * Streams the content of [id] into [out]. Stops with [DriveException] as soon as more
     * than [maxBytes] arrive (the size in the metadata is checked by the caller too, this
     * guards against a lying or missing size). Returns the number of bytes written.
     */
    suspend fun download(id: String, out: OutputStream, maxBytes: Long = DriveRules.MAX_DOWNLOAD_BYTES): Long {
        val url = fileUrl(id).newBuilder().addQueryParameter("alt", "media").build()
        return withContext(Dispatchers.IO) {
            execute(url).use { response ->
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxBytes) throw DriveException("Tệp vượt giới hạn ${DriveRules.formatSize(maxBytes)}")
                val buffer = ByteArray(BUFFER)
                var total = 0L
                body.byteStream().use { input ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw DriveException("Tệp vượt giới hạn ${DriveRules.formatSize(maxBytes)}")
                        out.write(buffer, 0, n)
                    }
                }
                out.flush()
                total
            }
        }
    }

    private fun fileUrl(id: String): HttpUrl {
        if (!DriveRules.isValidId(id)) throw DriveException("Mã tệp Drive không hợp lệ")
        return baseUrl.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).build()
    }

    private suspend fun getJson(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        execute(url).use { JSONObject(it.body.string()) }
    }

    /** Runs a GET with the bearer token, refreshing it once on 401. Caller closes the response. */
    private suspend fun execute(url: HttpUrl): Response {
        var token = tokens.token() ?: throw DriveAuthRequiredException()
        var response = call(url, token)
        if (response.code == 401) {
            response.close()
            token = tokens.refresh(token) ?: throw DriveAuthRequiredException()
            response = call(url, token)
            if (response.code == 401) {
                response.close()
                throw DriveAuthRequiredException()
            }
        }
        if (!response.isSuccessful) {
            val code = response.code
            val reason = runCatching { errorReason(response.body.string()) }.getOrNull()
            response.close()
            throw DriveException(
                when (code) {
                    403 -> "Google Drive từ chối truy cập${reason?.let { " ($it)" }.orEmpty()}"
                    404 -> "Không tìm thấy tệp trên Google Drive"
                    429 -> "Google Drive đang giới hạn yêu cầu, thử lại sau"
                    else -> "Google Drive lỗi HTTP $code${reason?.let { " ($it)" }.orEmpty()}"
                },
                code,
            )
        }
        return response
    }

    private suspend fun call(url: HttpUrl, token: String): Response = withContext(Dispatchers.IO) {
        http.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $token").get().build(),
        ).execute()
    }

    companion object {
        const val FIELDS = "id,name,size,mimeType,modifiedTime"
        private const val MAX_PAGE = 50
        private const val BUFFER = 64 * 1024

        /**
         * Drive search query for audio files whose name contains [name]. Backslashes and
         * single quotes are escaped as the Drive query language requires, so user or LLM
         * input cannot break out of the string literal.
         */
        fun audioNameQuery(name: String): String =
            "name contains '${escape(name.trim())}' and mimeType contains 'audio/' and trashed = false"

        fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        internal fun parseFile(o: JSONObject?): DriveFile? {
            if (o == null) return null
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
            return DriveFile(
                id = id,
                name = o.optString("name"),
                // Drive returns int64 values as JSON strings.
                sizeBytes = o.optString("size").toLongOrNull(),
                mimeType = o.optString("mimeType"),
                modifiedTime = o.optString("modifiedTime").takeIf { it.isNotEmpty() },
            )
        }

        private fun errorReason(body: String): String? =
            JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.take(120)
    }
}
