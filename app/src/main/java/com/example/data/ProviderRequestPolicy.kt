package com.example.data

import okhttp3.Request

/** Serialize provider lookups/handshakes, while direct media and TMDB stay independent. */
internal object ProviderRequestPolicy {
    fun needsPacing(request: Request, activeDomain: String, knownDomains: List<String>): Boolean {
        // Explicit provider cookies also cover a newly redirected mirror that
        // has not yet been saved as the active origin.
        val cookie = request.header("Cookie").orEmpty()
        if (cookie.contains("addhash=") || cookie.contains("t_hash_t=")) return true
        val host = request.url.host
        return (knownDomains + activeDomain).any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }
}
