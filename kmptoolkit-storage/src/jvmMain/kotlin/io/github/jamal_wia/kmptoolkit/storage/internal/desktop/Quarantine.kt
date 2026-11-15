package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Moves an unusable store file aside (plan D-5, 4.4): `<file>.undecryptable-<yyyyMMdd'T'HHmmss'Z'>`, then `-1`, `-2`, ... on
 * a name collision (two quarantines in one second). The rename never replaces anything (a hard link plus removal of the
 * source), so an earlier quarantine file survives; the library never deletes a quarantined file. A failed create after a
 * quarantine renames the file back ([restore]), still under the lock, so the store is byte-identical to before the call.
 */
internal object Quarantine {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private const val MAX_COLLISIONS: Int = 10_000

    /** The quarantine name of [file] at [now], with the collision suffix [counter] (`0` = none). Pure. */
    fun nameOf(file: File, now: Instant, counter: Int): File {
        require(counter >= 0) { "counter must not be negative" }
        val suffix: String = if (counter == 0) "" else "-$counter"
        return File(file.absoluteFile.parentFile, "${file.name}.undecryptable-${STAMP.format(now)}$suffix")
    }

    /**
     * Moves [file] to a free quarantine name and returns where it went. [AlreadyExists][FileError.AlreadyExists] moves on to
     * the next counter; every other failure throws an [IOException] with the file layer's error, and [file] is untouched.
     */
    fun moveAside(files: FileAccess, file: File, now: Instant): File {
        for (counter: Int in 0..MAX_COLLISIONS) {
            val target: File = nameOf(file, now, counter)
            val result: FileResult<Unit> =
                files.move(file.absolutePath, target.absolutePath, MoveOptions(existing = ExistingFile.Fail, acrossVolumes = false))
            if (result !is FileResult.Failure) return target
            if (result.error is FileError.AlreadyExists) continue
            throw IOException("Could not quarantine ${file.name}: ${result.error}", (result.error as? FileError.Io)?.cause)
        }
        throw IOException("No free quarantine name for ${file.name}")
    }

    /** Renames [quarantined] back to [file] (rollback of a failed create); the store file must be absent. */
    fun restore(files: FileAccess, quarantined: File, file: File) {
        val result: FileResult<Unit> =
            files.move(quarantined.absolutePath, file.absolutePath, MoveOptions(existing = ExistingFile.Fail, acrossVolumes = false))
        if (result is FileResult.Failure) throw IOException("Could not restore ${file.name} from quarantine: ${result.error}")
    }
}
