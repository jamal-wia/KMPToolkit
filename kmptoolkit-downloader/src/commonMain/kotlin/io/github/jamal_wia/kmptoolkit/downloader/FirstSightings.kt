package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A set of keys that answers "is this the first time?" exactly once per key, from any thread and
 * without a lock (a [MutableStateFlow] updated with `update {}`). Used to log a warning once per
 * offending path instead of on every call. Bounded by the host's catalogue, not by call count.
 */
internal class FirstSightings {
    private val seen: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    fun isFirst(key: String): Boolean {
        var first = false
        // update {} may rerun the lambda under contention, so `first` is recomputed each time.
        seen.update { current: Set<String> ->
            first = key !in current
            if (first) current + key else current
        }
        return first
    }
}
