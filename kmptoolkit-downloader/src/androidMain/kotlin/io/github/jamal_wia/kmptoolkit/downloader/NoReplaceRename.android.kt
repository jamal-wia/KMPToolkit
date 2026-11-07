package io.github.jamal_wia.kmptoolkit.downloader

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.file.Files

internal actual fun renameNoReplace(from: String, to: String): Boolean {
    if (!link(from, to)) return false
    // A failed removal leaves both names on the same bytes; the next pass drops the old one.
    File(from).delete()
    return true
}

/**
 * `link(2)`: `Files.createLink` from API 26, `Os.link` below it (the same system call; `java.nio.file`
 * is not available on API 24 and 25). Either fails when [to] exists, which is the whole point.
 */
private fun link(from: String, to: String): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Files.createLink(File(to).toPath(), File(from).toPath())
        } else {
            Os.link(from, to)
        }
        true
    } catch (e: IOException) {
        false
    } catch (e: ErrnoException) {
        false
    }
}
