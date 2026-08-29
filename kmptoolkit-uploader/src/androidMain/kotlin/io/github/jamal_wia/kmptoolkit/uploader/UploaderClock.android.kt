package io.github.jamal_wia.kmptoolkit.uploader

import java.util.UUID

internal actual fun currentEpochMillis(): Long = System.currentTimeMillis()

internal actual fun randomUploaderItemId(): String = UUID.randomUUID().toString()
