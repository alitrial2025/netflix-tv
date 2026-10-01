package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** Device reminders only: no paid cloud scheduler or automatic payment is required. */
object SubscriptionReminders {
    private const val TAG = "subscription-renewal"
    private const val NOTIFICATION_ID = 7301
    fun schedule(context: Context, uid: String?, expiresAt: Long, eligible: Boolean) {
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        val identity = if (eligible && !uid.isNullOrBlank()) "$uid:$expiresAt" else ""
        if (prefs.getString("scheduled", "") == identity) return
        val work = WorkManager.getInstance(context)
        work.cancelAllWorkByTag(TAG)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        prefs.edit().putString("scheduled", identity).apply()
        if (identity.isEmpty()) return
        val delay = (expiresAt - SubscriptionTime.now() + RenewalPolicy.DAY_MS).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SubscriptionReminderWorker>()
            .setInputData(workDataOf("uid" to uid, "expiry" to expiresAt))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG).build()
        work.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
    }

    /** The banner/toast still works if Android notification permission is denied. */
    fun claimInApp(context: Context, uid: String?, expiry: Long, due: Boolean): Boolean {
        if (!due || uid.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        val identity = "$uid:$expiry"
        if (prefs.getString("shown", null) == identity) return false
        prefs.edit().putString("shown", identity).apply()
        return true
    }

    fun notify(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(TAG, "Membership reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        intent.putExtra("open_subscription", true)
        val tap = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, TAG)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Renew your NetflixPro membership")
            .setContentText("Payment is one day overdue. Renew today to keep watching tomorrow.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Payment is one day overdue. Your two-day renewal allowance ends tomorrow. Renew on your phone to keep watching."))
            .setContentIntent(tap).setAutoCancel(true).build()
        try { manager.notify(NOTIFICATION_ID, notification) } catch (_: SecurityException) { /* In-app reminder remains available. */ }
    }
}

class SubscriptionReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        SubscriptionTime.initialize(applicationContext)
        val uid = inputData.getString("uid") ?: return Result.success()
        val expiry = inputData.getLong("expiry", 0L)
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
        return try {
            val doc = FirebaseFirestore.getInstance().collection("users").document(uid)
                .collection("subscription").document("current").get(Source.SERVER).await()
            if (FirebaseAuth.getInstance().currentUser?.uid != uid || doc.metadata.hasPendingWrites()) return Result.success()
            val validPlan = doc.getString("planId") in setOf("plan_mobile", "plan_basic", "plan_standard", "plan_premium")
            val now = SubscriptionTime.now()
            if (validPlan && doc.getLong("expiresAt") == expiry &&
                RenewalPolicy.grantsAccess(doc.getString("status").orEmpty(), expiry, now) &&
                RenewalPolicy.reminderDue(expiry, now)) SubscriptionReminders.notify(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (SubscriptionTime.now() < RenewalPolicy.accessEndsAt(expiry)) Result.retry() else Result.success() }
    }
}
