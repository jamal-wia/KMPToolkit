package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Refuses to open a path that is not a regular file. Opening a named pipe blocks in `open(2)` until a peer appears, and a
 * device or a directory is no store file either; a blocked open is native blocking, so neither a time budget nor the
 * caller's cancellation can end it, and the thread (and the store's in-process lock) is lost for good. The files layer
 * has the same rule for the store file of the encrypted stores ("Opening a pipe or a device could block forever");
 * this is the same rule for every other open of a path of the storage module: the lock file, and the properties file of
 * the plain store.
 *
 * Links are followed: a link to a regular file is a regular file. An absent path (including a link whose target is
 * missing) passes, because opening it fails with [NoSuchFileException] at once, and each caller has an answer for that.
 * Only metadata is read here, never an open, so the check itself cannot block.
 *
 * One window remains by design: the path can be swapped for a pipe between this check and the open. That needs another
 * program of the same user to race the call on purpose, and the JDK offers no way to open without blocking.
 *
 * @param what names the file in the message (`"The lock file"`).
 * @throws IOException when the path exists and is not a regular file, or its metadata cannot be read.
 */
internal fun refuseUnlessRegularOrAbsent(path: Path, what: String) {
    val attributes: BasicFileAttributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        return
    }
    if (!attributes.isRegularFile) throw IOException("$what is not a regular file (a directory, a named pipe or a device)")
}
