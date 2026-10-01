package com.example.update

import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

enum class UpdatePhase { CHECKING, CONTINUE, DOWNLOADING, VERIFYING, READY, PERMISSION, INSTALLING, APPROVAL, ERROR }

data class UpdateGateState(
    val phase: UpdatePhase = UpdatePhase.CHECKING,
    val release: UpdateRelease? = null,
    val progress: Float = 0f,
    val message: String = "Checking for updates…"
)

/** One launch check, a resumable OS download, and a verified self-update. No account is needed. */
class UpdateGateViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val installer = context.packageManager.packageInstaller
    private val _state = MutableStateFlow(UpdateGateState())
    val state = _state.asStateFlow()
    private var work: Job? = null
    private var checkTimeout: Job? = null
    private val installing = AtomicBoolean(false)
    private var approvalOpened = false
    private val verifiedFile get() = File(context.filesDir, "update-gate/verified.apk")

    init { checkOnLaunch() }

    private fun installedVersion(): Long = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    private fun checkOnLaunch() {
        work?.cancel()
        checkTimeout?.cancel()
        _state.value = UpdateGateState()
        // A slow/offline website never blocks normal app startup.
        checkTimeout = viewModelScope.launch {
            delay(3500L)
            if (_state.value.phase == UpdatePhase.CHECKING) {
                work?.cancel()
                _state.value = UpdateGateState(UpdatePhase.CONTINUE)
            }
        }
        work = viewModelScope.launch(Dispatchers.IO) {
            try {
                val source = UpdateRelease.manifestUrl(context)
                if (source == null) {
                    _state.value = UpdateGateState(UpdatePhase.CONTINUE)
                    return@launch
                }
                val version = installedVersion()
                val saved = prefs.getString("release", null)?.let {
                    runCatching { UpdateRelease.parse(it, source, context.packageName) }.getOrNull()
                }
                if (saved != null && saved.versionCode <= version) clearCompletedDownload()
                if (saved != null && saved.versionCode > version && saved.minSdk <= Build.VERSION.SDK_INT) {
                    if (prefs.getInt("install_session", -1) >= 0) {
                        monitorInstall(saved)
                        return@launch
                    }
                    if (prefs.getLong("download_id", -1L) >= 0L) {
                        monitorDownload(saved)
                        return@launch
                    }
                }
                val release = UpdateRelease.parse(fetchManifest(source), source, context.packageName)
                coroutineContext.ensureActive()
                if (_state.value.phase != UpdatePhase.CHECKING) return@launch
                if (release == null || release.versionCode <= version || release.minSdk > Build.VERSION.SDK_INT) {
                    _state.value = UpdateGateState(UpdatePhase.CONTINUE)
                } else {
                    require(prefs.edit().putString("release", release.manifestJson).commit())
                    startDownload(release)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (_state.value.phase == UpdatePhase.CHECKING) {
                    _state.value = UpdateGateState(UpdatePhase.CONTINUE)
                } else {
                    fail(if (_state.value.phase == UpdatePhase.VERIFYING) {
                        "This update could not be verified. Keep using the app and try again later."
                    } else {
                        "The update could not be downloaded. Check your connection and try again."
                    })
                }
            }
        }
    }

    private fun fetchManifest(source: String): String {
        var url = UpdateRelease.secureUrl(source)
        repeat(4) {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 1500
                readTimeout = 2000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Accept", "application/json")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val target = connection.getHeaderField("Location") ?: error("Missing redirect")
                    url = UpdateRelease.secureUrl(URL(url, target).toString())
                } else {
                    require(status == 200)
                    require(connection.contentLengthLong <= UpdateRelease.MAX_MANIFEST_BYTES)
                    val bytes = connection.inputStream.use { stream ->
                        stream.readBytesBounded(UpdateRelease.MAX_MANIFEST_BYTES)
                    }
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun downloadFile(release: UpdateRelease): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Storage unavailable"),
        "update-gate/${release.fileName}"
    )

    private suspend fun startDownload(release: UpdateRelease) {
        _state.value = UpdateGateState(UpdatePhase.DOWNLOADING, release, message = "Downloading your update…")
        val destination = downloadFile(release)
        require(destination.parentFile?.mkdirs() == true || destination.parentFile?.isDirectory == true)
        if (destination.exists()) require(destination.delete())
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("NetflixPro update ${release.versionName}")
            .setDescription("The app will verify this update before installation.")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .addRequestHeader("Accept-Encoding", "identity")
            .setDestinationInExternalFilesDir(
                context, Environment.DIRECTORY_DOWNLOADS, "update-gate/${release.fileName}"
            )
        val id = downloads.enqueue(request)
        if (!prefs.edit().putLong("download_id", id).commit()) {
            downloads.remove(id)
            error("Unable to save download")
        }
        monitorDownload(release)
    }

    private suspend fun monitorDownload(release: UpdateRelease) {
        try {
            monitorDownloadStatus(release)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Report the failing check without URLs, response bodies, or credential values.
            val check = error.stackTrace.firstOrNull { it.className.startsWith(javaClass.name) }
            android.util.Log.w("UpdateGate", "Update rejected: ${error.javaClass.simpleName} at ${check?.methodName}:${check?.lineNumber}")
            // A failed/mismatched response must not keep consuming storage in the background.
            val id = prefs.getLong("download_id", -1L)
            if (id >= 0L) runCatching { downloads.remove(id) }
            prefs.edit().remove("download_id").commit()
            verifiedFile.delete()
            throw error
        }
    }

    private suspend fun monitorDownloadStatus(release: UpdateRelease) {
        val id = prefs.getLong("download_id", -1L)
        require(id >= 0L)
        _state.value = UpdateGateState(UpdatePhase.DOWNLOADING, release, message = "Resuming your update…")
        while (true) {
            coroutineContext.ensureActive()
            val result = downloads.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                require(cursor != null && cursor.moveToFirst()) { "Download no longer available" }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val received = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val reportedSize = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                require(received <= release.sizeBytes && (reportedSize <= 0L || reportedSize == release.sizeBytes))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val local = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                        require(local.scheme == "file" && File(local.path ?: "").canonicalFile == downloadFile(release).canonicalFile)
                    }
                    DownloadManager.STATUS_FAILED -> error("Download failed")
                    else -> _state.value = UpdateGateState(
                        UpdatePhase.DOWNLOADING, release,
                        (received.toDouble() / release.sizeBytes).toFloat().coerceIn(0f, 1f),
                        if (status == DownloadManager.STATUS_PAUSED) "Waiting for a connection…" else "Downloading your update…"
                    )
                }
                status
            }
            if (result == DownloadManager.STATUS_SUCCESSFUL) {
                _state.value = UpdateGateState(UpdatePhase.VERIFYING, release, 1f, "Verifying your update…")
                verifyDownload(release)
                coroutineContext.ensureActive()
                _state.value = UpdateGateState(UpdatePhase.READY, release, 1f, "Your update is ready.")
                return
            }
            delay(800L)
        }
    }

    private suspend fun verifyDownload(release: UpdateRelease) {
        val downloaded = downloadFile(release)
        require(downloaded.isFile && downloaded.length() == release.sizeBytes)
        require(verifiedFile.parentFile?.mkdirs() == true || verifiedFile.parentFile?.isDirectory == true)
        // Copy to internal storage before inspecting/using it, preventing external-file replacement.
        downloaded.inputStream().use { input ->
            verifiedFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    require(written <= release.sizeBytes)
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
                require(written == release.sizeBytes)
            }
        }
        verifyPrivateApk(release)
    }

    @Suppress("DEPRECATION")
    private suspend fun verifyPrivateApk(release: UpdateRelease) {
        require(verifiedFile.length() == release.sizeBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        verifiedFile.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "APK hash mismatch" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        // Android 9 collects archive certificates only when GET_SIGNATURES is also set.
        // Keep GET_SIGNING_CERTIFICATES for the complete current signer set on newer APIs.
        val apk = context.packageManager.getPackageArchiveInfo(
            verifiedFile.absolutePath, flags or PackageManager.GET_SIGNATURES
        )
            ?: error("Invalid APK")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        require(apk.packageName == context.packageName && apk.packageName == release.packageName)
        require(PackageInfoCompat.getLongVersionCode(apk) == release.versionCode && release.versionCode > installedVersion())
        require(apk.versionName == release.versionName)
        val actualMinSdk = apk.applicationInfo?.minSdkVersion ?: error("Missing APK metadata")
        require(actualMinSdk == release.minSdk && actualMinSdk <= Build.VERSION.SDK_INT)
        require(apk.splitNames.isNullOrEmpty()) { "A standalone APK is required" }
        val expectedSigners = signingHashes(installed)
        require(expectedSigners.isNotEmpty() && signingHashes(apk) == expectedSigners) { "APK signer mismatch" }
    }

    @Suppress("DEPRECATION")
    private fun signingHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun onResume(activity: Activity) {
        val owner = activity as? androidx.lifecycle.LifecycleOwner ?: return
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (_state.value.phase == UpdatePhase.READY || (_state.value.phase == UpdatePhase.PERMISSION && canInstall())) install(activity)
        if (_state.value.phase == UpdatePhase.APPROVAL && !approvalOpened) openApproval(activity)
    }

    private fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun allowUpdates(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26) runCatching {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }.onFailure { fail("This device could not open install settings. Allow updates in Android settings, then retry.") }
    }

    fun install(activity: Activity) {
        if (activity.isFinishing || !((activity as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.RESUMED) ?: false)) return
        if (_state.value.phase !in listOf(UpdatePhase.READY, UpdatePhase.PERMISSION)) return
        val release = _state.value.release ?: return
        if (!canInstall()) {
            _state.value = UpdateGateState(UpdatePhase.PERMISSION, release, 1f, "Allow NetflixPro to install its updates.")
            return
        }
        if (!installing.compareAndSet(false, true)) return
        work?.cancel()
        _state.value = UpdateGateState(UpdatePhase.INSTALLING, release, 1f, "Installing your update…")
        work = viewModelScope.launch(Dispatchers.IO) {
            var sessionId = -1
            var committed = false
            try {
                // Re-check the private file immediately before staging it.
                verifyPrivateApk(release)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setAppLabel("NetflixPro")
                    setSize(release.sizeBytes)
                    if (Build.VERSION.SDK_INT >= 31) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                val token = UUID.randomUUID().toString()
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = "${context.packageName}.UPDATE_INSTALL_STATUS"
                    data = Uri.parse("netflixpro-update://session/$sessionId")
                    putExtra("gate_token", token)
                }
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                )
                require(prefs.edit().putInt("install_session", sessionId).putString("install_token", token)
                    .putLong("install_version", release.versionCode).putInt("install_status", INSTALL_IN_PROGRESS)
                    .remove("approval_intent").commit())
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0L, release.sizeBytes).use { output ->
                        verifiedFile.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                        session.fsync(output)
                    }
                    coroutineContext.ensureActive()
                    session.commit(pending.intentSender)
                    committed = true
                }
                monitorInstall(release)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fail("Android could not install this update. You can retry or continue using the app.")
            } finally {
                if (sessionId >= 0 && !committed) {
                    runCatching { installer.abandonSession(sessionId) }
                    prefs.edit().remove("install_session").remove("install_token").commit()
                }
                installing.set(false)
            }
        }
    }

    private suspend fun monitorInstall(release: UpdateRelease) {
        _state.value = UpdateGateState(UpdatePhase.INSTALLING, release, 1f, "Finishing your update…")
        while (true) {
            coroutineContext.ensureActive()
            when (prefs.getInt("install_status", INSTALL_IN_PROGRESS)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    // Normally Android replaces this process first; the next launch clears the files.
                    _state.value = UpdateGateState(UpdatePhase.CONTINUE)
                    return
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    if (installer.getSessionInfo(prefs.getInt("install_session", -1)) == null) {
                        fail("The install confirmation expired. Retry when you're ready.")
                        return
                    }
                    _state.value = UpdateGateState(UpdatePhase.APPROVAL, release, 1f, "Confirm the update in Android to finish.")
                }
                INSTALL_IN_PROGRESS -> {
                    if (installer.getSessionInfo(prefs.getInt("install_session", -1)) == null) {
                        fail("The installation did not finish. Retry when you're ready.")
                        return
                    }
                }
                else -> {
                    fail("The update was not installed. You can retry or continue using the app.")
                    return
                }
            }
            delay(500L)
        }
    }

    fun openApproval(activity: Activity) {
        val raw = prefs.getString("approval_intent", null) ?: return
        approvalOpened = true
        runCatching { activity.startActivity(Intent.parseUri(raw, 0)) }
            .onFailure { fail("Open the install confirmation again to finish your update.") }
    }

    fun retry() {
        if (installing.get() || _state.value.phase == UpdatePhase.INSTALLING) return
        work?.cancel()
        approvalOpened = false
        val release = _state.value.release
        work = viewModelScope.launch(Dispatchers.IO) {
            try {
                val session = prefs.getInt("install_session", -1)
                if (session >= 0) runCatching { installer.abandonSession(session) }
                prefs.edit().remove("install_session").remove("install_token").remove("approval_intent").commit()
                if (release == null) {
                    checkOnLaunch()
                } else {
                    // A cancelled install can reuse the already verified bytes.
                    val reusable = try {
                        verifyPrivateApk(release)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                    if (reusable) {
                        _state.value = UpdateGateState(UpdatePhase.READY, release, 1f, "Your update is ready.")
                        return@launch
                    }
                    val id = prefs.getLong("download_id", -1L)
                    if (id >= 0) downloads.remove(id)
                    prefs.edit().remove("download_id").commit()
                    verifiedFile.delete()
                    startDownload(release)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fail(if (_state.value.phase == UpdatePhase.VERIFYING) {
                    "This update could not be verified. Keep using the app and try again later."
                } else {
                    "The update could not be downloaded. Check your connection and try again."
                })
            }
        }
    }

    fun later() {
        if (_state.value.phase == UpdatePhase.INSTALLING) return
        checkTimeout?.cancel()
        work?.cancel()
        _state.value = UpdateGateState(UpdatePhase.CONTINUE)
        // DownloadManager continues in the background. Installation waits for the next launch.
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = message)
    }

    private fun clearCompletedDownload() {
        val id = prefs.getLong("download_id", -1L)
        if (id >= 0L) runCatching { downloads.remove(id) }
        val session = prefs.getInt("install_session", -1)
        if (session >= 0) runCatching { installer.abandonSession(session) }
        verifiedFile.delete()
        prefs.edit().clear().commit()
    }

    companion object {
        const val PREFS = "update_gate"
        private const val INSTALL_IN_PROGRESS = -100
    }
}
