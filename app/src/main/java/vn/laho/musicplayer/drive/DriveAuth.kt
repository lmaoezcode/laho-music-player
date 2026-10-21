// SPDX-FileCopyrightText: 2026 LamHoang (LaHo Music Player)
// SPDX-License-Identifier: GPL-3.0-or-later

package vn.laho.musicplayer.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Google Drive authorization through Google Play services (`AuthorizationClient`).
 *
 * The access token is kept in memory only (never persisted, never logged); there is no
 * refresh token: Play services hands out a new access token when the user's grant still
 * exists. When consent is needed, [authorize] exposes the resolution [PendingIntent] in
 * [pending] and the assistant screen launches it.
 */
class DriveAuth(context: Context) : DriveTokenSource {

    private val appContext = context.applicationContext
    private val client by lazy { Identity.getAuthorizationClient(appContext) }

    @Volatile
    private var accessToken: String? = null

    private val _pending = MutableStateFlow<PendingIntent?>(null)

    /** Consent screen waiting to be launched by the UI (null when none). */
    val pending: StateFlow<PendingIntent?> = _pending

    val isAuthorized: Boolean get() = accessToken != null

    override suspend fun token(): String? = accessToken ?: authorize(interactive = true)

    override suspend fun refresh(expired: String): String? {
        if (accessToken == expired) accessToken = null
        return authorize(interactive = true)
    }

    /**
     * Asks Play services for a token. Returns it when the grant already exists; otherwise
     * returns null and, if [interactive], publishes the consent screen in [pending].
     */
    suspend fun authorize(interactive: Boolean): String? {
        val result = try {
            client.authorize(request()).await()
        } catch (e: ApiException) {
            throw DriveException(
                "Không xin được quyền Google Drive (mã ${e.statusCode}). " +
                    "Kiểm tra OAuth client Android (package + SHA-1) trong Google Cloud.",
            )
        }
        if (result.hasResolution()) {
            if (interactive) _pending.value = result.pendingIntent
            return null
        }
        return result.accessToken?.also { accessToken = it }
    }

    /** The UI took the pending consent screen (it is launched once). */
    fun consumePending(): PendingIntent? = _pending.getAndSet(null)

    /** Handles the result of the consent screen; true when a token was granted. */
    fun onConsentResult(data: Intent?): Boolean = try {
        accessToken = client.getAuthorizationResultFromIntent(data).accessToken
        accessToken != null
    } catch (e: ApiException) {
        false
    }

    /** Forgets the in-memory token (the Google-side grant stays until revoked in the account). */
    fun signOut() {
        accessToken = null
        _pending.value = null
    }

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE_DRIVE_READONLY))).build()

    companion object {
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}

private fun <T> MutableStateFlow<T?>.getAndSet(value: T?): T? = this.value.also { this.value = value }

private suspend fun Task<AuthorizationResult>.await(): AuthorizationResult =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
