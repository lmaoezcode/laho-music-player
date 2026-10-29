// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** One file in the app's hidden Drive folder. */
data class AppDataFile(val id: String, val name: String, val sizeBytes: Long?, val modifiedTime: String?)

/** What the backup service needs from Drive's app data folder. Faked in tests. */
interface AppDataStore {
    /** Files in the app data folder whose name starts with [namePrefix], newest first. */
    suspend fun list(namePrefix: String): List<AppDataFile>

    suspend fun upload(name: String, content: ByteArray, mimeType: String): AppDataFile

    /** Downloads at most [maxBytes]; throws [DriveException] when the file is bigger. */
    suspend fun download(id: String, maxBytes: Long): ByteArray

    /** Deletes a file of the app data folder. */
    suspend fun delete(id: String)
}

/**
 * Drive REST v3 client for the hidden `appDataFolder` space (scope `drive.appdata`). Every
 * call is confined to that space: listing uses `spaces=appDataFolder`, uploads set
 * `parents=['appDataFolder']`, and delete/download only take ids the caller got from [list]
 * or [upload]. The token is sent only in the Authorization header and never logged; on 401
 * it is refreshed once through [tokens] (without UI).
 */
class DriveAppDataClient(
    private val http: OkHttpClient,
    private val tokens: DriveTokenSource,
    private val baseUrl: HttpUrl = "https://www.googleapis.com/".toHttpUrl(),
) : AppDataStore {

    override suspend fun list(namePrefix: String): List<AppDataFile> {
        val url = baseUrl.newBuilder().addPathSegments("drive/v3/files")
            .addQueryParameter("spaces", SPACE)
            .addQueryParameter("q", "name contains '${DriveClient.escape(namePrefix)}' and trashed = false")
            .addQueryParameter("fields", "files($FIELDS)")
            .addQueryParameter("orderBy", "createdTime desc")
            .addQueryParameter("pageSize", "100")
            .build()
        val body = runText { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").get().build() }
        val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).mapNotNull { parse(files.optJSONObject(it)) }
            .filter { it.name.startsWith(namePrefix) }
    }

    override suspend fun upload(name: String, content: ByteArray, mimeType: String): AppDataFile {
        val metadata = JSONObject().put("name", name).put("parents", JSONArray().put(SPACE)).put("mimeType", mimeType)
        val url = baseUrl.newBuilder().addPathSegments("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FIELDS)
            .build()
        val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()
        val text = runText { token ->
            Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()
        }
        return parse(JSONObject(text)) ?: throw DriveException("Drive trả về dữ liệu không hợp lệ")
    }

    override suspend fun download(id: String, maxBytes: Long): ByteArray {
        val url = fileUrl(id).newBuilder().addQueryParameter("alt", "media").build()
        return run { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").get().build() }
            .use { response ->
                val body = response.body
                if (body.contentLength() > maxBytes) throw DriveException("Tệp sao lưu quá lớn")
                withContext(Dispatchers.IO) {
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(BUFFER)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > maxBytes) throw DriveException("Tệp sao lưu quá lớn")
                            out.write(buffer, 0, n)
                        }
                    }
                    out.toByteArray()
                }
            }
    }

    override suspend fun delete(id: String) {
        val url = fileUrl(id)
        run { token -> Request.Builder().url(url).header("Authorization", "Bearer $token").delete().build() }.close()
    }

    private fun fileUrl(id: String): HttpUrl {
        if (!DriveRules.isValidId(id)) throw DriveException("Mã tệp Drive không hợp lệ")
        return baseUrl.newBuilder().addPathSegments("drive/v3/files").addPathSegment(id).build()
    }

    private suspend fun runText(build: (String) -> Request): String =
        run(build).use { response -> withContext(Dispatchers.IO) { response.body.string() } }

    /** Executes the request built by [build], refreshing the token once on 401. Caller closes the response. */
    private suspend fun run(build: (String) -> Request): Response = withContext(Dispatchers.IO) {
        var token = tokens.token() ?: throw DriveAuthRequiredException()
        var response = http.newCall(build(token)).execute()
        if (response.code == 401) {
            response.close()
            token = tokens.refresh(token) ?: throw DriveAuthRequiredException()
            response = http.newCall(build(token)).execute()
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
                    403 -> "Google Drive từ chối truy cập thư mục sao lưu${reason?.let { " ($it)" }.orEmpty()}. " +
                        "Kiểm tra quyền drive.appdata (/backup login)."
                    404 -> "Không tìm thấy tệp sao lưu trên Google Drive"
                    429 -> "Google Drive đang giới hạn yêu cầu, thử lại sau"
                    else -> "Google Drive lỗi HTTP $code${reason?.let { " ($it)" }.orEmpty()}"
                },
                code,
            )
        }
        response
    }

    private fun parse(o: JSONObject?): AppDataFile? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return AppDataFile(id, o.optString("name"), o.optString("size").toLongOrNull(), o.optString("modifiedTime").takeIf { it.isNotEmpty() })
    }

    private fun errorReason(body: String): String? =
        JSONObject(body).optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.take(120)

    companion object {
        const val SPACE = "appDataFolder"
        private const val FIELDS = "id,name,size,modifiedTime"
        private const val BUFFER = 64 * 1024
    }
}
