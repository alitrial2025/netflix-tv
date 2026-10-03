package com.example.update

/** A cached required release remains required even if a later check fails or rolls back. */
internal object UpdateGatePolicy {
    sealed interface Decision {
        data object Current : Decision
        data object Unavailable : Decision
        data class Required(val release: UpdateRelease, val compatible: Boolean) : Decision
    }

    fun decide(installedVersion: Long, sdk: Int, latest: UpdateRelease?, cached: UpdateRelease?): Decision {
        val required = listOfNotNull(latest, cached)
            .filter { it.versionCode > installedVersion }.maxByOrNull { it.versionCode }
        if (required != null) return Decision.Required(required, required.minSdk <= sdk)
        return if (latest != null) Decision.Current else Decision.Unavailable
    }
}
