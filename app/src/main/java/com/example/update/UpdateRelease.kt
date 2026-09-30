package com.example.update

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.URL

/** Public metadata only. APK identity is verified independently against the installed app. */
data class UpdateRelease(
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val sizeBytes: Long,
    val sha256: String,
    val apkUrl: String,
    val notes: String,
    val manifestJson: String
) {
    val fileName: String get() = "$versionCode-${sha256.take(16)}.apk"

    companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024

        fun manifestUrl(context: Context): String? {
            val config = context.assets.open("update-gate.json").bufferedReader().use { it.readText() }
            val siteUrl = JSONObject(config).optString("siteUrl").trim().trimEnd('/')
            if (siteUrl.isBlank()) return null
            val site = secureUrl(siteUrl)
            require(site.query == null && site.ref == null) { "Invalid update site" }
            val channel = channelFor(context.packageName)
            return "$siteUrl/updates/$channel.json"
        }

        fun channelFor(packageName: String): String = when (packageName) {
            "com.netflixprotv.apk" -> "tv"
            "com.netflixpro.apk" -> "mobile"
            else -> error("Unknown app package")
        }

        fun parse(json: String, sourceUrl: String, expectedPackage: String): UpdateRelease? {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES)
            val value = JSONObject(json)
            require(value.getInt("schemaVersion") == 1)
            require(value.getString("channel") == channelFor(expectedPackage))
            require(value.getString("packageName") == expectedPackage)
            if (!value.getBoolean("available")) return null
            val code = value.getLong("versionCode")
            val name = value.getString("versionName").trim()
            val sdk = value.getInt("minSdk")
            val size = value.getLong("sizeBytes")
            val hash = value.getString("sha256").lowercase()
            require(code in 1..Int.MAX_VALUE.toLong() && name.isNotEmpty() && name.length <= 80)
            require(sdk in 24..100 && size in 1..MAX_APK_BYTES)
            require(hash.matches(Regex("[0-9a-f]{64}")))
            val rawUrl = value.getString("apkUrl").trim()
            // Root-relative links make the same release files usable on local previews and Netlify.
            require(rawUrl.startsWith("https://") || (rawUrl.startsWith('/') && !rawUrl.startsWith("//")))
            val resolved = URL(secureUrl(sourceUrl), rawUrl)
            secureUrl(resolved.toString())
            return UpdateRelease(
                channelFor(expectedPackage), expectedPackage, code, name, sdk, size, hash,
                resolved.toString(), value.optString("releaseNotes").take(2000), json
            )
        }

        fun secureUrl(raw: String): URL {
            val url = URL(raw)
            require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
            require(url.port == -1 || url.port == 443)
            require(Uri.parse(raw).fragment == null)
            return url
        }
    }
}
