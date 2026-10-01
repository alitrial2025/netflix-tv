package com.example.discovery

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Identical UTC calendar rules on phone and TV; API 24 compatible. */
object ReleasePolicy {
    private fun formatter() = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
    }
    private val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    @Volatile private var cachedDay: Pair<Long,String>? = null
    @Volatile private var cachedWindow: Triple<String,String,String>? = null
    fun day(now: Long = System.currentTimeMillis()): String {
        val bucket = Math.floorDiv(now,86_400_000L)
        cachedDay?.takeIf { it.first == bucket }?.let { return it.second }
        return formatter().format(java.util.Date(now)).also { cachedDay = bucket to it }
    }
    fun valid(date: String?): Boolean {
        if (date == null || !datePattern.matches(date)) return false
        val year = date.substring(0,4).toInt(); val month = date.substring(5,7).toInt(); val day = date.substring(8,10).toInt()
        if (year < 1 || month !in 1..12 || day < 1) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when(month) { 2 -> if (leap) 29 else 28; 4,6,9,11 -> 30; else -> 31 }
        return day <= days
    }
    private fun window(today: String): Triple<String,String,String> {
        cachedWindow?.takeIf { it.first == today }?.let { return it }
        return Triple(today,shiftMonths(today,-1),shiftMonths(today,3)).also { cachedWindow=it }
    }
    fun shiftMonths(today: String, months: Int): String {
        require(valid(today))
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        calendar.time = formatter().parse(today)!!; calendar.add(Calendar.MONTH, months)
        return formatter().format(calendar.time)
    }
    fun isNew(date: String?, today: String = day()): Boolean = valid(date) && date!! in window(today).second..today
    fun isUpcoming(date: String?, today: String = day()): Boolean = valid(date) && date!! in today..window(today).third
    fun isPlayableDate(date: String?, today: String = day()): Boolean = !valid(date) || date!! <= today
    fun badge(date: String): String = if (valid(date)) SimpleDateFormat("MMM d", Locale.ENGLISH).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(formatter().parse(date)!!).uppercase(Locale.ROOT) else ""
}

data class ReleaseTitle(
    val id: String, val kind: String, val title: String, val overview: String = "",
    val posterPath: String? = null, val backdropPath: String? = null, val date: String? = null,
    val genreIds: List<Int> = emptyList(), val voteAverage: Double = 0.0,
    val voteCount: Int = 0, val popularity: Double = 0.0, val majorOtt: Boolean = false
) { val key: String get() = "$kind:$id" }

data class ReleaseFeed(val upcoming: List<ReleaseTitle> = emptyList(), val recent: List<ReleaseTitle> = emptyList())

/** Curation never invents a release date or promotes an old title as new. */
object ReleaseCurator {
    fun curate(candidates: List<ReleaseTitle>, today: String = ReleasePolicy.day()): ReleaseFeed {
        val pool = candidates.filter { it.kind in setOf("movie", "tv") && it.id.toLongOrNull()?.let { id -> id > 0 } == true }
            .filter { !it.posterPath.isNullOrBlank() && ReleasePolicy.valid(it.date) }
            .groupBy { it.key }.map { (_, variants) -> variants.firstOrNull { it.majorOtt } ?: variants.first() }
        fun prominent(it: ReleaseTitle) = it.majorOtt || (it.kind == "movie" && it.popularity >= 10.0)
        return ReleaseFeed(
            upcoming = pool.filter { prominent(it) && ReleasePolicy.isUpcoming(it.date, today) }
                .sortedWith(compareBy<ReleaseTitle> { it.date }.thenByDescending { it.popularity }).take(60),
            recent = pool.filter { prominent(it) && ReleasePolicy.isNew(it.date, today) }
                .sortedWith(compareByDescending<ReleaseTitle> { it.popularity }.thenByDescending { it.date }).take(60)
        )
    }
}
