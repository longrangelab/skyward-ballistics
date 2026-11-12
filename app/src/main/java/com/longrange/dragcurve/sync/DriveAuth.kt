package com.longrange.dragcurve.sync

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    const val SCOPE_READONLY = "https://www.googleapis.com/auth/drive.readonly"

    class SignInRequiredException : Exception("Google Drive needs you to sign in again.")

    fun request(email: String?, scope: String = SCOPE): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(scope)))
        .also { b -> if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google")) }
        .build()

    fun authorize(activity: Activity, email: String? = null, scope: String = SCOPE): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(activity).authorize(request(email, scope))

    fun resultFromIntent(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)

    fun emailOf(r: AuthorizationResult): String? = runCatching { r.toGoogleSignInAccount()?.email }.getOrNull()

    fun tokenSilently(context: Context, email: String?, scope: String = SCOPE): String {
        val r = Tasks.await(Identity.getAuthorizationClient(context.applicationContext).authorize(request(email, scope)), 30, TimeUnit.SECONDS)
        if (r.hasResolution()) throw SignInRequiredException()
        return r.accessToken ?: throw SignInRequiredException()
    }

    fun clearToken(context: Context, token: String) {
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(context.applicationContext)
                .clearToken(ClearTokenRequest.builder().setToken(token).build()), 15, TimeUnit.SECONDS)
        }
    }

    fun revoke(context: Context, email: String?) {
        if (email.isNullOrBlank()) return
        runCatching {
            Tasks.await(Identity.getAuthorizationClient(context.applicationContext).revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(listOf(Scope(SCOPE))).build()
            ), 15, TimeUnit.SECONDS)
        }
    }

    fun explain(e: Throwable): String {
        val api = (e as? ApiException) ?: (e.cause as? ApiException)
        return when (api?.statusCode) {
            CommonStatusCodes.CANCELED, 12501 -> "Sign-in was cancelled."
            CommonStatusCodes.NETWORK_ERROR -> "No connection to Google. Try again when online."
            CommonStatusCodes.DEVELOPER_ERROR -> "Google Drive isn't set up for Skyward Ballistics yet. Google didn't accept this app: the Google Cloud project needs an Android OAuth client for package com.longrange.dragcurve with this build's SHA-1 — see docs/GOOGLE_CLOUD_SETUP.md."
            null -> e.message ?: e.javaClass.simpleName
            else -> "Google sign-in failed (code ${api.statusCode}): ${api.message}"
        }
    }
}
