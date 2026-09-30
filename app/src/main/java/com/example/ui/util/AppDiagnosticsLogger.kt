package com.example.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppDiagnosticsLogger {
    private const val TAG = "DiagnosticsLogger"
    private val executor = Executors.newSingleThreadExecutor()
    private val targetFiles = mutableListOf<File>()
    private val memoryBuffer = ArrayDeque<String>(600)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext ?: context

        try {
            // 1. Guaranteed Internal Storage (/data/data/<pkg>/files/app_diagnostics_log.txt)
            val internalFile = File(appContext.filesDir, "app_diagnostics_log.txt")
            if (!internalFile.exists()) {
                internalFile.parentFile?.mkdirs()
                internalFile.createNewFile()
            }
            targetFiles.add(internalFile)

            // 2. External App Storage (/sdcard/Android/data/<pkg>/files/app_diagnostics_log.txt)
            appContext.getExternalFilesDir(null)?.let { extDir ->
                val extFile = File(extDir, "app_diagnostics_log.txt")
                if (!extFile.exists()) {
                    extFile.parentFile?.mkdirs()
                    extFile.createNewFile()
                }
                targetFiles.add(extFile)
            }

            // 3. Public Downloads Directory (/sdcard/Download/app_diagnostics_log.txt)
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir != null) {
                    downloadsDir.mkdirs()
                    val dlFile = File(downloadsDir, "app_diagnostics_log.txt")
                    if (!dlFile.exists()) dlFile.createNewFile()
                    targetFiles.add(dlFile)
                }
            } catch (_: Exception) {}

            // 4. Try legacy com.example path if accessible
            try {
                val legacyFile = File("/data/data/com.example/files/app_diagnostics_log.txt")
                legacyFile.parentFile?.mkdirs()
                if (legacyFile.parentFile?.canWrite() == true) {
                    if (!legacyFile.exists()) legacyFile.createNewFile()
                    targetFiles.add(legacyFile)
                }
            } catch (_: Exception) {}

            initialized = true
            logHeader()

            // Flush any buffered lines collected before init
            synchronized(memoryBuffer) {
                for (line in memoryBuffer) {
                    writeToFiles(line)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize diagnostics file logger", e)
        }
    }

    fun getLogFilePath(): String {
        return targetFiles.firstOrNull()?.absolutePath
            ?: "/data/data/com.netflixprotv.apk/files/app_diagnostics_log.txt"
    }

    fun getAllLogPaths(): List<String> {
        return targetFiles.map { it.absolutePath }
    }

    private fun logHeader() {
        val header = buildString {
            appendLine("=============================================================")
            appendLine("         NETFLIX PRO TV - DIAGNOSTICS & TELEMETRY LOG        ")
            appendLine("=============================================================")
            appendLine("Session Started: ${dateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("Active Log Locations:")
            targetFiles.forEach { appendLine("  -> ${it.absolutePath}") }
            appendLine("=============================================================")
        }
        writeLine(header)
    }

    // Capture general flow "What's Happening"
    fun event(category: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [EVENT] [$category] $message"
        Log.i(TAG, logLine)
        writeLine(logLine)
    }

    // Capture Performance Hotspots
    fun performance(hotspot: String, durationMs: Long, thresholdMs: Long, message: String = "") {
        val timestamp = dateFormat.format(Date())
        val isHot = durationMs > thresholdMs
        val prefix = if (isHot) "[PERF_HOTSPOT_WARNING]" else "[PERF_OK]"
        val details = if (message.isNotBlank()) " - $message" else ""
        val logLine = "[$timestamp] $prefix [$hotspot] took ${durationMs}ms (limit: ${thresholdMs}ms)$details"
        if (isHot) {
            Log.w(TAG, logLine)
        } else {
            Log.d(TAG, logLine)
        }
        writeLine(logLine)
    }

    // Capture exceptions, fallbacks or failures
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val stackTrace = throwable?.stackTraceToString()?.trim() ?: ""
        val logLine = if (stackTrace.isNotEmpty()) {
            "[$timestamp] [ERROR] [$tag] $message\nStacktrace:\n$stackTrace\n-------------------------------------------------------------"
        } else {
            "[$timestamp] [ERROR] [$tag] $message"
        }
        Log.e(TAG, logLine)
        writeLine(logLine)
    }

    private fun writeLine(line: String) {
        synchronized(memoryBuffer) {
            if (memoryBuffer.size >= 600) {
                memoryBuffer.removeFirst()
            }
            memoryBuffer.addLast(line)
        }

        executor.execute {
            writeToFiles(line)
        }
    }

    private fun writeToFiles(line: String) {
        for (file in targetFiles) {
            try {
                PrintWriter(FileWriter(file, true)).use { pw ->
                    pw.println(line)
                    pw.flush()
                }
            } catch (_: Exception) {}
        }
    }

    fun readLogFileContent(maxLines: Int = 400): String {
        // Try reading from file first
        for (file in targetFiles) {
            if (file.exists() && file.length() > 0L) {
                try {
                    val lines = file.useLines { it.toList() }
                    if (lines.isNotEmpty()) {
                        return lines.takeLast(maxLines).joinToString("\n")
                    }
                } catch (_: Exception) {}
            }
        }

        // Fallback to in-memory buffer
        synchronized(memoryBuffer) {
            if (memoryBuffer.isNotEmpty()) {
                return memoryBuffer.takeLast(maxLines).joinToString("\n")
            }
        }
        return "Log file is initialized and waiting for stream events."
    }

    fun copyLogsToClipboard(context: Context): Boolean {
        return try {
            val content = readLogFileContent(500)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("NetflixProDiagnostics", content)
            clipboard.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy logs to clipboard", e)
            false
        }
    }

    fun clearLogFile() {
        synchronized(memoryBuffer) {
            memoryBuffer.clear()
        }
        executor.execute {
            try {
                for (file in targetFiles) {
                    if (file.exists()) {
                        file.delete()
                        file.createNewFile()
                    }
                }
                logHeader()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear log files", e)
            }
        }
    }
}
