package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlinx.coroutines.CloseableCoroutineDispatcher

/** A real confined database dispatcher that counts how often it is closed. */
internal interface CountingDispatcher {
    val dispatcher: CloseableCoroutineDispatcher
    val closeCount: Int
}

/** One [CountingDispatcher] over [createConfinedDatabaseDispatcher]; its wrapper type is platform-shaped. */
internal expect fun countingDispatcher(): CountingDispatcher
