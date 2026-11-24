// SPDX-License-Identifier: AGPL-3.0-only

package app.farsofa.vr.psn

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.farsofa.vr.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class PsnAccount(
    val accountId: ByteArray,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
)

class PsnException(message: String) : IOException(message)

/**
 * PSN sign-in for Remote Play. The endpoints, client id and scopes are the ones the Remote Play
 * protocol requires, as used by chiaki-ng (gui/include/psnaccountid.h).
 */
object PsnAuth {
    private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
    private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
    private const val AUTHORIZE_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize"
    private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
    private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read " +
        "pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
    const val REDIRECT_URL = "https://remoteplay.dl.playstation.net/remoteplay/redirect"

    fun loginUrl(duid: String?): String {
        val builder = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("service_entity", "urn:service-entity:psn")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URL)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("request_locale", "en_US")
            .appendQueryParameter("ui", "pr")
            .appendQueryParameter("service_logo", "ps")
            .appendQueryParameter("layout_type", "popup")
            .appendQueryParameter("smcid", "remoteplay")
            .appendQueryParameter("prompt", "always")
            .appendQueryParameter("PlatformPrivacyWs1", "minimal")
        if (duid != null) builder.appendQueryParameter("duid", duid)
        return builder.build().toString()
    }

    /** The authorization code from a redirect URL, or null if [url] is not the Remote Play redirect. */
    fun codeFromRedirect(url: String): String? {
        if (!url.startsWith(REDIRECT_URL)) return null
        return Uri.parse(url).getQueryParameter("code")
    }

    /** PSN account ids travel as the numeric user id in 8 little-endian bytes. */
    fun accountIdFromUserId(userId: String): ByteArray {
        val value = userId.toULong().toLong()
        return ByteArray(8) { (value ushr (8 * it)).toByte() }
    }

    suspend fun signIn(code: String): PsnAccount = withContext(Dispatchers.IO) {
        val tokens = postForm(
            "grant_type" to "authorization_code",
            "code" to code,
            "scope" to SCOPE,
            "redirect_uri" to REDIRECT_URL,
        )
        val accessToken = tokens.getString("access_token")
        val info = request("$TOKEN_URL/$accessToken", null)
        PsnAccount(
            accountId = accountIdFromUserId(info.getString("user_id")),
            accessToken = accessToken,
            refreshToken = tokens.getString("refresh_token"),
            expiresAtMs = System.currentTimeMillis() + tokens.getLong("expires_in") * 1000,
        )
    }

    suspend fun refresh(account: PsnAccount): PsnAccount = withContext(Dispatchers.IO) {
        val tokens = postForm(
            "grant_type" to "refresh_token",
            "refresh_token" to account.refreshToken,
            "scope" to SCOPE,
            "redirect_uri" to REDIRECT_URL,
        )
        PsnAccount(
            accountId = account.accountId,
            accessToken = tokens.getString("access_token"),
            refreshToken = tokens.optString("refresh_token").ifEmpty { account.refreshToken },
            expiresAtMs = System.currentTimeMillis() + tokens.getLong("expires_in") * 1000,
        )
    }

    private fun postForm(vararg fields: Pair<String, String>): JSONObject {
        val body = fields.joinToString("&") { (name, value) ->
            "$name=${URLEncoder.encode(value, "UTF-8")}"
        }
        return request(TOKEN_URL, body)
    }

    private fun request(url: String, formBody: String?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            val credentials = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $credentials")
            if (formBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(formBody.toByteArray()) }
            } else {
                connection.setRequestProperty("Content-Type", "application/json")
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw PsnException("PSN returned HTTP $status")
            return JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw PsnException("PSN returned an unexpected response")
        } finally {
            connection.disconnect()
        }
    }
}

class PsnStore(context: Context) {
    private val store = SecureStore(context, "psn")

    fun load(): PsnAccount? {
        val json = store.get(KEY) ?: return null
        return try {
            val item = JSONObject(json)
            PsnAccount(
                accountId = Base64.decode(item.getString("accountId"), Base64.NO_WRAP),
                accessToken = item.getString("accessToken"),
                refreshToken = item.getString("refreshToken"),
                expiresAtMs = item.getLong("expiresAtMs"),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun save(account: PsnAccount?) {
        if (account == null) {
            store.put(KEY, null)
            return
        }
        store.put(
            KEY,
            JSONObject()
                .put("accountId", Base64.encodeToString(account.accountId, Base64.NO_WRAP))
                .put("accessToken", account.accessToken)
                .put("refreshToken", account.refreshToken)
                .put("expiresAtMs", account.expiresAtMs)
                .toString()
        )
    }

    private companion object {
        const val KEY = "account"
    }
}
