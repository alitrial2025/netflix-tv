package com.example.ui.screens.details

import com.example.model.Movie
import com.example.model.catalogMediaKind
import com.example.model.isKidSafeMovie
import com.example.model.isSeriesContent

/** Stable ordering without sorting or materializing the whole catalog. */
internal fun selectDetailsRecommendations(
    catalog: List<Movie>, current: Movie, kidsOnly: Boolean, limit: Int = 12
): List<Movie> {
    if (limit <= 0) return emptyList()
    val preferred = ArrayList<Movie>(limit)
    val fallback = ArrayList<Movie>(limit)
    val seen = HashSet<String>()
    val series = current.isSeriesContent()
    for (candidate in catalog) {
        val key = "${candidate.catalogMediaKind()}:${candidate.id}"
        if (candidate.id == current.id && candidate.catalogMediaKind() == current.catalogMediaKind()) continue
        if (!seen.add(key) || (kidsOnly && !isKidSafeMovie(candidate))) continue
        if (candidate.isSeriesContent() == series) preferred.add(candidate)
        else if (fallback.size < limit) fallback.add(candidate)
        if (preferred.size == limit) break
    }
    return (preferred + fallback).take(limit)
}
