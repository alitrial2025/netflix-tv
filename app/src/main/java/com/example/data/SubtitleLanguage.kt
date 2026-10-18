package com.example.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Unknown or contradictory metadata is not evidence of an English subtitle. */
internal object SubtitleLanguage {
    data class Identity(val label: String, val code: String)
    private val aliases: Map<String, String> by lazy {
        buildMap {
            Locale.getISOLanguages().forEach { code ->
                val normalized = when (code) { "iw" -> "he"; "in" -> "id"; "ji" -> "yi"; else -> code }
                val locale = Locale(normalized)
                put(code, normalized)
                put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), normalized)
                runCatching { locale.isO3Language }.getOrNull()?.let { put(it, normalized) }
            }
            put("fre", "fr"); put("ger", "de"); put("dut", "nl"); put("chi", "zh")
            put("cze", "cs"); put("gre", "el"); put("rum", "ro"); put("esp", "es")
            put("français", "fr"); put("español", "es"); put("deutsch", "de")
        }
    }
    fun code(value: String): String? {
        val clean = value.trim().lowercase(Locale.ROOT).substringBefore('[').substringBefore('(').trim()
            .removeSuffix(" cc").removeSuffix(" sdh").removeSuffix(" forced").trim()
        if (clean.isBlank() || clean == "und") return null
        return aliases[clean] ?: clean.replace('_', '-').takeIf { it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})+")) }
            ?.substringBefore('-')?.let(aliases::get)
    }
    private fun filenameCode(url: String): String? {
        val name = runCatching { URLDecoder.decode(URI(url).rawPath.substringAfterLast('/'), "UTF-8") }.getOrDefault("")
        val values = Regex("(?:^|[-_.])([A-Za-z]{2,3}(?:-[A-Za-z]{2}|-[0-9]{3})?)(?=[.\\[]|$)")
            .findAll(name).mapNotNull { code(it.groupValues[1]) }.distinct().toList()
        return values.singleOrNull()
    }
    fun identity(label: String, languageCode: String, url: String = ""): Identity {
        if (label == "Subtitles (language unconfirmed)" && code(languageCode) == null) return Identity(label, "und")
        val labelled = code(label)
        val coded = code(languageCode)
        val filename = filenameCode(url)
        val candidates = listOfNotNull(labelled, coded, filename).distinct()
        if (candidates.size > 1) return Identity("Subtitles (language unconfirmed)", "und")
        val resolved = candidates.singleOrNull() ?: return Identity(label.trim().ifBlank { "Unknown language" }, "und")
        val display = if (labelled == resolved && label.trim().length > 3) label.trim() else Locale(resolved).getDisplayLanguage(Locale.ENGLISH)
        val tag = languageCode.trim().replace('_', '-').takeIf {
            coded == resolved && it.matches(Regex("(?i)[a-z]{2,3}(?:-(?:[a-z]{4}|[a-z]{2}|[0-9]{3}))?")) && !it.endsWith("-cc", true)
        } ?: resolved
        return Identity(display, tag.lowercase(Locale.ROOT))
    }
    fun matches(label: String, languageCode: String, selected: String, url: String = ""): Boolean {
        if (selected.isBlank() || selected.equals("Off", true)) return false
        val identity = identity(label, languageCode, url)
        val target = code(selected)
        return if (target != null) code(identity.code) == target
            else identity.code == "und" && identity.label.equals(selected.trim(), true)
    }
    fun normalize(caption: Caption): Caption {
        if (caption.type in setOf("thumbnails", "chapters")) return caption
        val identity = identity(caption.language, caption.languageCode, caption.url)
        return caption.copy(language = identity.label, languageCode = identity.code)
    }
}
