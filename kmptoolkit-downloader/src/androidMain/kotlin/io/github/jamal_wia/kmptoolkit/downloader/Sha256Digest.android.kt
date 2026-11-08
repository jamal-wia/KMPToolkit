package io.github.jamal_wia.kmptoolkit.downloader

import java.security.MessageDigest

internal actual fun sha256Digest(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
