package com.example.data

import android.content.Context
import android.provider.Settings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.UUID

class DeviceAccessException(message: String) : java.io.IOException(message)
class MembershipCheckException : java.io.IOException("We couldn't verify your membership and device. Reconnect and try again.")

internal object DeviceAccessPolicy {
    fun isSingleDevice(planId: String) = planId == "plan_mobile" || planId == "plan_basic"
    fun permits(planId: String, active: Boolean, tv: Boolean, boundDevice: String?, device: String): Boolean =
        !active || (!(tv && planId == "plan_mobile") &&
            (!isSingleDevice(planId) || boundDevice.isNullOrBlank() || boundDevice == device))
    fun confirmationStatusMatches(verified: String, displayed: String, expiry: Long, now: Long): Boolean =
        verified.equals(displayed, true) ||
            (RenewalPolicy.grantsAccess(verified, expiry, now) && RenewalPolicy.grantsAccess(displayed, expiry, now))
    fun screenCount(planId: String): Int = when (planId) {
        "plan_mobile", "plan_basic" -> 1
        "plan_standard" -> 2
        "plan_premium" -> 4
        else -> 0
    }
}

/** Login-device binding is separate from concurrent playback slots. Sign-out does not release a binding. */
object DeviceAccessGuard {
    private data class Confirmation(val uid: String, val plan: String, val expiry: Long, val status: String)
    @Volatile private var confirmed: Confirmation? = null

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("device_access", Context.MODE_PRIVATE)
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeUnless { it.isBlank() || it == "9774d56d682e549c" }
        val seed = androidId ?: synchronized(this) {
            prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("installation_id", it).commit()
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun clear() { confirmed = null }
    fun isConfirmed(uid: String, plan: String, expiry: Long, status: String): Boolean {
        val proof = confirmed ?: return false
        return uid.isNotBlank() && proof.uid == uid && proof.plan == plan && proof.expiry == expiry &&
            DeviceAccessPolicy.confirmationStatusMatches(proof.status, status, expiry, SubscriptionTime.now())
    }

    suspend fun confirm(context: Context, tv: Boolean): com.google.firebase.firestore.DocumentSnapshot {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser?.takeUnless { it.isAnonymous } ?: throw MembershipCheckException()
        val db = FirebaseFirestore.getInstance()
        val subRef = db.collection("users").document(user.uid).collection("subscription").document("current")
        val bindingRef = db.collection("users").document(user.uid).collection("device_binding").document("current")
        val device = deviceId(context)
        try {
            // Server transaction serializes two first-device logins, including Mobile -> Basic changes.
            val snapshot = kotlinx.coroutines.withTimeoutOrNull(15_000L) { db.runTransaction { tx ->
                val sub = tx.get(subRef)
                val plan = sub.getString("planId").orEmpty()
                val active = DeviceAccessPolicy.screenCount(plan) > 0 && RenewalPolicy.grantsAccess(
                    sub.getString("status").orEmpty(), sub.getLong("expiresAt") ?: 0L, SubscriptionTime.now())
                val binding = if (active && DeviceAccessPolicy.isSingleDevice(plan)) tx.get(bindingRef) else null
                if (!DeviceAccessPolicy.permits(plan, active, tv, binding?.getString("deviceId"), device)) {
                    throw DeviceAccessException(if (tv && plan == "plan_mobile")
                        "The Mobile plan works on one phone or tablet. Choose Basic, Standard or Premium for TV."
                    else "This plan is tied to another device. Use that device, upgrade your plan, or contact support to change devices.")
                }
                if (active && DeviceAccessPolicy.isSingleDevice(plan) && binding?.exists() != true) {
                    tx.set(bindingRef, mapOf("deviceId" to device, "deviceType" to if (tv) "tv" else "mobile",
                        "boundAt" to FieldValue.serverTimestamp()))
                }
                sub
            }.await() } ?: throw MembershipCheckException()
            if (auth.currentUser?.uid != user.uid) throw MembershipCheckException()
            confirmed = Confirmation(user.uid, snapshot.getString("planId").orEmpty(), snapshot.getLong("expiresAt") ?: 0L, snapshot.getString("status").orEmpty())
            return snapshot
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            confirmed = null
            val restriction = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<DeviceAccessException>().firstOrNull()
            if (restriction != null) throw restriction
            throw MembershipCheckException()
        }
    }
}

/** Each account has at most four fixed slots; claims race in one Firestore transaction. */
object ScreenLease {
    private data class Lease(val uid: String, val slot: Int, val device: String, val token: String)
    @Volatile private var lease: Lease? = null
    internal fun availableSlot(devices: List<String?>, heartbeats: List<Long>, released: List<Boolean>,
                               device: String, now: Long): Int? =
        devices.indexOf(device).takeIf { it >= 0 } ?: devices.indices.firstOrNull {
            devices[it] == null || released[it] || now - heartbeats[it] >= 45_000L
        }

    suspend fun acquire(context: Context, tv: Boolean) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser?.takeUnless { it.isAnonymous } ?: throw MembershipCheckException()
        val device = DeviceAccessGuard.deviceId(context)
        val db = FirebaseFirestore.getInstance()
        val account = db.collection("users").document(user.uid)
        val token = UUID.randomUUID().toString()
        val slot = kotlinx.coroutines.withTimeoutOrNull(15_000L) { db.runTransaction { tx ->
            val sub = tx.get(account.collection("subscription").document("current"))
            val plan = sub.getString("planId").orEmpty()
            val max = DeviceAccessPolicy.screenCount(plan)
            if (max == 0 || !RenewalPolicy.grantsAccess(sub.getString("status").orEmpty(),
                    sub.getLong("expiresAt") ?: 0L, SubscriptionTime.now())) throw MembershipCheckException()
            val binding = if (DeviceAccessPolicy.isSingleDevice(plan))
                tx.get(account.collection("device_binding").document("current")).getString("deviceId") else null
            if (!DeviceAccessPolicy.permits(plan, true, tv, binding, device) ||
                (DeviceAccessPolicy.isSingleDevice(plan) && binding == null)) throw DeviceAccessException("This device isn't authorized for your plan. Sign in again.")
            val slots = (0 until max).map { tx.get(account.collection("stream_slots").document(it.toString())) }
            val available = availableSlot(slots.map { it.getString("deviceId") },
                slots.map { it.getTimestamp("lastHeartbeat")?.toDate()?.time ?: 0L },
                slots.map { it.getBoolean("released") == true }, device, SubscriptionTime.now())
                ?: throw DeviceAccessException("All $max screens on your plan are in use. Stop playback on another device or upgrade your plan.")
            tx.set(account.collection("stream_slots").document(available.toString()), mapOf(
                "deviceId" to device, "deviceType" to if (tv) "tv" else "mobile",
                "lastHeartbeat" to FieldValue.serverTimestamp(), "released" to false, "leaseToken" to token))
            available
        }.await() } ?: throw MembershipCheckException()
        if (auth.currentUser?.uid != user.uid) throw MembershipCheckException()
        synchronized(this) { lease = Lease(user.uid, slot, device, token) }
    }

    fun releaseIn(scope: CoroutineScope) {
        val captured = synchronized(this) { lease.also { lease = null } } ?: return
        scope.launch { releaseCaptured(captured) }
    }
    suspend fun release() {
        val captured = synchronized(this) { lease.also { lease = null } } ?: return
        releaseCaptured(captured)
    }
    private suspend fun releaseCaptured(captured: Lease) {
        if (FirebaseAuth.getInstance().currentUser?.uid != captured.uid) return
        val ref = FirebaseFirestore.getInstance().collection("users").document(captured.uid)
            .collection("stream_slots").document(captured.slot.toString())
        try {
            FirebaseFirestore.getInstance().runTransaction { tx ->
                val slot = tx.get(ref)
                if (slot.getString("deviceId") == captured.device && slot.getString("leaseToken") == captured.token) tx.update(ref,
                    mapOf("released" to true, "lastHeartbeat" to FieldValue.serverTimestamp()))
            }.await()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A disconnected device's slot expires after 45 seconds. */ }
    }
}
