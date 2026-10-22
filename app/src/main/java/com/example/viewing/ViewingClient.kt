package com.example.viewing

import android.content.Context
import android.os.SystemClock
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class ViewingException(val code: String, message: String) : IOException(message)
class ViewingClient(context: Context, val kind: String) {
    private val prefs = context.applicationContext.getSharedPreferences("viewing_sessions", Context.MODE_PRIVATE)
    val deviceId: String = prefs.getString("device", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device", it).apply() }
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    private val serverClock = ViewingServerClock(SystemClock::elapsedRealtime)
    fun serverNowMs(): Long = serverClock.nowMs()
    fun savedRoom(owner: String, profile: String): String? = prefs.getString("room:$owner:$profile", null)
    fun saveRoom(owner: String, profile: String, code: String?) { prefs.edit().putString("room:$owner:$profile", code).apply() }
    fun consumed(owner: String, id: String) = prefs.getString("command:$owner", null) == id
    fun consume(owner: String, id: String) { prefs.edit().putString("command:$owner", id).apply() }
    suspend fun call(owner: String, action: String, data: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser?.takeIf { !it.isAnonymous && it.uid == owner }
            ?: throw ViewingException("SIGN_IN_REQUIRED", "Sign in to watch together.")
        val token = user.getIdToken(false).await().token ?: throw ViewingException("SIGN_IN_REQUIRED", "Sign in again.")
        val sent = SystemClock.elapsedRealtime()
        val payload = JSONObject(data.toString()).put("action", action).put("deviceId", deviceId).put("deviceKind", kind)
        val request = Request.Builder().url("https://npro-app.vercel.app/api/viewing/session")
            .header("Authorization", "Bearer $token").post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.byteStream()?.use { input ->
                val saved = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (saved.size() + count > 64 * 1024) throw IOException("Viewing response is too large")
                    saved.write(buffer, 0, count)
                }
                saved.toString("UTF-8")
            }?.takeIf { it.isNotBlank() }
            val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: throw ViewingException("VIEWING_UNAVAILABLE", "Could not connect. Try again shortly.")
            if (FirebaseAuth.getInstance().currentUser?.uid != owner) throw ViewingException("ACCOUNT_CHANGED", "Your account changed.")
            if (!response.isSuccessful) throw ViewingException(json.optString("code"), json.optString("message", "Could not connect."))
            val received = SystemClock.elapsedRealtime()
            val server = json.optLong("serverNowMs")
            serverClock.observe(server, sent, received)
            json
        }
    }
    fun listen(owner: String, collection: String, onSnapshot: (JSONObject?, Boolean) -> Unit): ListenerRegistration =
        FirebaseFirestore.getInstance().collection("users").document(owner).collection(collection).document(deviceId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (FirebaseAuth.getInstance().currentUser?.uid != owner) return@addSnapshotListener
                if (error != null) { onSnapshot(null, false); return@addSnapshotListener }
                if (snapshot?.metadata?.hasPendingWrites() == true) return@addSnapshotListener
                val data = snapshot?.data
                onSnapshot(data?.let { JSONObject(it) }, snapshot?.metadata?.isFromCache == false)
            }
}
