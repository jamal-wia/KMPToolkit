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
        setLastModified = { path: String, millis: Long -> File(path).setLastModified(millis) },
        cleanup = { root.deleteRecursively() },
    )
}
