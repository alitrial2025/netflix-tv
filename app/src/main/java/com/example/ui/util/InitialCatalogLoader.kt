package com.example.ui.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Publish useful content without waiting for the slowest initial feed. */
internal suspend fun <T> loadInitialCatalog(
    feeds: List<Pair<String, suspend () -> List<T>>>,
    concurrency: Int,
    onFirstContent: (List<Pair<String, List<T>>>) -> Unit
): List<Pair<String, List<T>>> = coroutineScope {
    val permits = Semaphore(concurrency)
    val completed = Channel<Pair<Int, List<T>>>(Channel.UNLIMITED)
    val results = arrayOfNulls<List<T>>(feeds.size)
    feeds.forEachIndexed { index, (_, fetch) ->
        launch {
            val items = permits.withPermit { fetch() }
            currentCoroutineContext().ensureActive()
            completed.send(index to items)
        }
    }
    fun rows() = feeds.mapIndexedNotNull { index, (name, _) ->
        results[index]?.takeIf { it.isNotEmpty() }?.let { name to it }
    }
    var published = false
    repeat(feeds.size) {
        val (index, items) = completed.receive()
        results[index] = items
        if (!published && items.isNotEmpty()) {
            published = true
            onFirstContent(rows())
        }
    }
    rows()
}
