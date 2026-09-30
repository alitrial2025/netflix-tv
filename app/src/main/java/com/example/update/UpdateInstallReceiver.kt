package com.example.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/** Explicit, non-exported callback. Never accepts an APK path or URL from an external intent. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(UpdateGateViewModel.PREFS, Context.MODE_PRIVATE)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val target = prefs.getLong("install_version", 0L)
            val installed = PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0)
            )
            if (target > 0 && installed >= target) {
                prefs.edit().putInt("install_status", PackageInstaller.STATUS_SUCCESS)
                    .remove("approval_intent").commit()
                // Android can restrict background launches. The launcher always remains usable.
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) runCatching {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    PendingIntent.getActivity(
                        context, 7391, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ).send()
                }
            }
            return
        }
        if (intent.action != "${context.packageName}.UPDATE_INSTALL_STATUS") return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sessionId < 0 || sessionId != prefs.getInt("install_session", -2)) return
        val token = prefs.getString("install_token", null) ?: return
        if (intent.getStringExtra("gate_token") != token) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val edit = prefs.edit().putInt("install_status", status)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            }
            if (confirmation == null) {
                edit.putInt("install_status", PackageInstaller.STATUS_FAILURE).commit()
            } else {
                // The visible gate opens this trusted system intent; a background receiver does not.
                edit.putString("approval_intent", confirmation.toUri(0)).commit()
            }
        } else {
            edit.remove("approval_intent").commit()
        }
    }
}
