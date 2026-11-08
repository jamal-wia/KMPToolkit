package io.github.jamal_wia.kmptoolkit.downloader

import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun renameNoReplace(from: String, to: String): Boolean {
    if (platform.posix.link(from, to) != 0) return false
    // A failed unlink leaves both names on the same bytes; the next pass drops the old one.
    platform.posix.unlink(from)
    return true
}
