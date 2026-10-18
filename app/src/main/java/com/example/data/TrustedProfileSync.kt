package com.example.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProfileSyncException(val statusCode: Int, message: String) : java.io.IOException(message)

/** Only the server may add cloud profiles; it counts the account's existing profiles atomically. */
object TrustedProfileSync {
    private val client by lazy { OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build() }
    internal fun payload(profiles: List<Map<String, Any?>>, activeProfileId: String?): JSONObject {
        val body = JSONObject().put("profiles", JSONArray().apply {
            profiles.forEach { put(JSONObject(it.filterKeys { key -> key != "updatedAt" })) }
        })
        activeProfileId?.let { body.put("activeProfileId", it) }
        return body
    }
    suspend fun save(uid: String, profiles: List<Map<String, Any?>>, activeProfileId: String? = null) = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser?.takeIf { it.uid == uid } ?: throw ProfileSyncException(401, "Your account changed. Sign in again to sync profiles.")
        val body = payload(profiles, activeProfileId).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        for (attempt in 0..1) {
            val token = user.getIdToken(attempt > 0).await().token ?: throw ProfileSyncException(401, "Sign in again to sync profiles.")
            if (auth.currentUser?.uid != uid) throw ProfileSyncException(401, "Your account changed. Sign in again to sync profiles.")
            val request = Request.Builder().url("https://npro-app.vercel.app/api/profiles/sync")
                .header("Authorization", "Bearer $token").post(body).build()
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && attempt == 0) return@use
                val result = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                if (!response.isSuccessful) {
                    val message = result?.optString("message")
                    throw ProfileSyncException(response.code, message?.takeIf(String::isNotBlank) ?: "Profiles are saved on this device. Reconnect to sync them.")
                }
                val saved = result?.optJSONArray("saved")
                val expected = profiles.map { it["id"] }.toSet()
                if (saved == null || (0 until saved.length()).map { saved.optString(it) }.toSet() != expected)
                    throw java.io.IOException("Profile sync could not be confirmed. Reconnect to retry.")
                return@withContext
            }
        }
        throw ProfileSyncException(401, "Sign in again to sync profiles.")
    }
}
