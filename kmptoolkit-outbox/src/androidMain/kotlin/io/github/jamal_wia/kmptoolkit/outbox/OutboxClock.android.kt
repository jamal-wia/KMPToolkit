package io.github.jamal_wia.kmptoolkit.outbox

import java.util.UUID

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

internal actual fun randomOutboxItemId(): String = UUID.randomUUID().toString()
