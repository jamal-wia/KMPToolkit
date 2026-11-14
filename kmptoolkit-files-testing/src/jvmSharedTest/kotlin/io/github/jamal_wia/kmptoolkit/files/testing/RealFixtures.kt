package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import java.io.File
import java.nio.file.Files

/**
 * A contract fixture on the host's real file system, in a fresh temporary directory that the fixture removes.
 * Symbolic links are offered only when the host lets the test user create one (not every Windows user can).
 */
internal fun realFixture(files: FileAccess): FileAccessFixture {
    val root: File = Files.createTempDirectory("kmptoolkit-files-contract").toFile()
    // path-hygiene-ignore: PH1 the fixture tells the contract which separator joins a real path on this host
    val linkSeparator: Char = File.separatorChar
    val canLink: Boolean = try {
        val probe: File = File(root, ".probe-link")
        Files.createSymbolicLink(probe.toPath(), root.toPath())
        probe.delete()
        true
    } catch (_: Exception) {
        false
    }
    return FileAccessFixture(
        files = files,
        root = root.path,
        separator = linkSeparator,
        createSymbolicLink = if (canLink) { path: String, target: String ->
            Files.createSymbolicLink(File(path).toPath(), File(target).toPath())
        } else null,
        setLastModified = { path: String, millis: Long -> setTime(path, millis) },
        cleanup = { root.deleteRecursively() },
    )
}

/** Dates the entry itself: a symbolic link is dated without following it where the host can, else its target is. */
private fun setTime(path: String, millis: Long): Boolean {
    val target: java.nio.file.Path = File(path).toPath()
    if (Files.isSymbolicLink(target)) {
        try {
            val time: java.nio.file.attribute.FileTime = java.nio.file.attribute.FileTime.fromMillis(millis)
            Files.getFileAttributeView(target, java.nio.file.attribute.BasicFileAttributeView::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .setTimes(time, time, null)
            return true
        } catch (_: Exception) {
            // Falls through: the contract skips the link check when the link itself was not dated.
        }
    }
    return File(path).setLastModified(millis)
}
