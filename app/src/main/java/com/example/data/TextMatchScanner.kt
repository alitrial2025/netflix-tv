package com.example.data

/** Detached groups from one match; never retain an Android native Matcher per result. */
internal data class TextMatch(val groupValues: List<String>) {
    val value: String get() = groupValues[0]
}

/**
 * Kotlin Regex.findAll creates a new Matcher for every result. Android's ICU
 * Matcher copies the entire input into native memory, outside the Java heap.
 * Reuse one Matcher when scanning provider pages containing thousands of links.
 */
internal fun Regex.scanMatches(input: CharSequence): Sequence<TextMatch> = sequence {
    val matcher = toPattern().matcher(input)
    while (matcher.find()) {
        yield(TextMatch((0..matcher.groupCount()).map { matcher.group(it).orEmpty() }))
    }
}
