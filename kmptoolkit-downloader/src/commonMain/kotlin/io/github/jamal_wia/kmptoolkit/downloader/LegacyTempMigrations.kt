package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Moves the 3.0.0 temp files of a unit whose names are now hashed to their hashed place, once per
 * process per unit, so a partial download of such a unit is resumed after an app update instead of
 * being orphaned. Units with a safe id have nothing to move and never get here.
 *
 * What may be moved is decided by [UnitFileLayout.migrationSteps]; this object only runs the steps.
 * It takes no lock, so it adds no lock-ordering edge: the set of finished units is a
 * [MutableStateFlow] updated with `update {}` (the owner's rule for state shared between threads),
 * and every step is an atomic move that is safe to repeat. Two threads that both see a unit as
 * not done each run the whole pass; the second finds nothing left to move. A process killed between
 * two steps leaves a state that the next process's pass completes, because the set is per process.
 *
 * A step never overwrites, for real: the move is [renameNoReplace], which fails instead of
 * replacing, so even a destination created between the existence check and the move survives. When
 * the destination exists it is the authoritative file (a transfer already began under the new name)
 * and the legacy file is deleted. A failing step is
 * logged and skipped, and the unit is still marked done, so one stuck file cannot cause a retry on
 * every call.
 *
 * The migration exists for one event, the update from 3.0.0, so a partial or complete file is only
 * moved when it predates the first use of the base directory by this version: [ensureLayout]
 * records that moment once per base in a marker file ([UnitFileLayout.LEGACY_LAYOUT_MARKER]), and a
 * legacy-named file whose modification time is not strictly older than the marker is somebody's
 * resource or a newer temp file and is never touched. A base without any 3.0.0 files is marked
 * `fresh` and never migrates. If the marker cannot be written or read, nothing is moved.
 */
internal object LegacyTempMigrations {

    /** The few file operations a pass needs, over absolute paths; one implementation per platform. */
    interface Files {
        fun exists(path: String): Boolean

        /** [renameNoReplace]: false when the destination exists (even if it appeared just now) or the move failed. */
        fun renameNoReplace(from: String, to: String): Boolean

        fun delete(path: String)

        fun createParentDirectory(path: String)

        /** Modification time in epoch milliseconds, null when the file is missing or the time is unknown. */
        fun lastModifiedMillis(path: String): Long?

        /** Whether [directory] holds any entry whose name is not [name]; false when it is missing or unreadable. */
        fun hasEntriesOtherThan(directory: String, name: String): Boolean

        /** The whole text of the file, null when it is missing or unreadable. */
        fun readText(path: String): String?

        /**
         * Creates [path] with [text], atomically and only when nothing exists there: writes a sibling
         * temporary file, then [renameNoReplace]s it onto [path]. False when [path] exists (the temporary
         * file is deleted then) or the write failed.
         */
        fun writeNew(path: String, text: String): Boolean
    }

    /** Which legacy-named temp data files of a base may still be moved. */
    sealed interface Cutoff {
        /** No partial or complete file may be moved. */
        data object None : Cutoff

        /** Only a file whose modification time is strictly before [millis] may be moved. */
        class Before(val millis: Long) : Cutoff
    }

    private val done: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    private val cutoffs: MutableStateFlow<Map<String, Cutoff>> = MutableStateFlow(emptyMap())

    private const val FRESH: String = "fresh"
    private const val UPGRADED: String = "upgraded"

    /**
     * Test seam: forgets which units were migrated, so a test can run the pass again for an id an
     * earlier test already used. The record is process-wide, so without this a test that reuses an
     * id silently skips its migration.
     */
    fun resetForTests() {
        done.value = emptySet()
        cutoffs.value = emptyMap()
    }

    /**
     * What may be migrated in [baseDirectory], decided once per process and base. Must run before
     * this version's first write under `tmp/` of that base, or its own temp file would make a fresh
     * base look upgraded. Without a marker, the base is `fresh` when `tmp/` holds nothing but the
     * storage's own directory, else `upgraded`; the marker is created without replacing, and
     * whoever loses the creation race reads the winner's value, so every caller agrees. Any I/O
     * failure or unknown content yields [Cutoff.None] (an orphaned partial is re-downloaded; a file of
     * unknown origin is never touched), logged once, and is cached for the process.
     */
    fun ensureLayout(baseDirectory: String, files: Files, logger: Logger): Cutoff {
        cutoffs.value[baseDirectory]?.let { return it }
        val marker = "$baseDirectory/${UnitFileLayout.LEGACY_LAYOUT_MARKER}"
        val cutoff: Cutoff = try {
            if (files.readText(marker) == null && !files.exists(marker)) {
                val fresh: Boolean = !files.hasEntriesOtherThan("$baseDirectory/tmp", UnitFileLayout.INTERNAL_DIRECTORY_NAME)
                files.createParentDirectory(marker)
                files.writeNew(marker, if (fresh) FRESH else UPGRADED)
            }
            when (files.readText(marker)?.trim()) {
                FRESH -> Cutoff.None
                UPGRADED -> files.lastModifiedMillis(marker)?.let { Cutoff.Before(it) } ?: unknown(marker, logger)
                else -> unknown(marker, logger)
            }
        } catch (e: Exception) {
            logger.w { "Could not read or create the layout marker $marker: ${e.message}; 3.0.0 temp files are not migrated" }
            Cutoff.None
        }
        cutoffs.update { if (baseDirectory in it) it else it + (baseDirectory to cutoff) }
        return cutoffs.value[baseDirectory] ?: cutoff
    }

    private fun unknown(marker: String, logger: Logger): Cutoff {
        logger.w { "The layout marker $marker is unreadable or unknown; 3.0.0 temp files are not migrated" }
        return Cutoff.None
    }

    fun migrateOnce(
        baseDirectory: String,
        unit: DownloadUnit,
        layout: UnitFileLayout,
        names: UnitFileLayout.Names,
        files: Files,
        logger: Logger,
        cutoff: Cutoff,
    ) {
        val key = "$baseDirectory\u0000${unit.id}\u0000${unit.tempExtension}"
        if (key in done.value) return
        for (step: UnitFileLayout.MigrationStep in layout.migrationSteps(unit, names)) {
            try {
                run(baseDirectory, step, files, logger, cutoff)
            } catch (e: Exception) {
                logger.w { "Could not migrate ${step.legacy} of $unit: ${e.message}" }
            }
        }
        done.update { it + key }
    }

    private fun run(
        baseDirectory: String,
        step: UnitFileLayout.MigrationStep,
        files: Files,
        logger: Logger,
        cutoff: Cutoff,
    ) {
        val legacy = "$baseDirectory/${step.legacy}"
        if (!files.exists(legacy)) return
        if (step.tempData && !predatesCutoff(files, legacy, cutoff)) {
            logger.d { "Leaving ${step.legacy}: not a 3.0.0 temp file (created after the layout marker, or no marker)" }
            return
        }
        val relativeDestination: String? = step.destination
        if (relativeDestination == null) {
            files.delete(legacy)
            return
        }
        val destination = "$baseDirectory/$relativeDestination"
        if (files.exists(destination)) {
            logger.w { "$relativeDestination already exists; dropping the 3.0.0 file ${step.legacy}" }
            files.delete(legacy)
            return
        }
        files.createParentDirectory(destination)
        if (!files.renameNoReplace(legacy, destination)) {
            if (files.exists(destination)) {
                // A transfer created it after the check above: it is authoritative, never replaced.
                logger.w { "$relativeDestination appeared meanwhile; dropping the 3.0.0 file ${step.legacy}" }
                files.delete(legacy)
            } else {
                logger.w { "Could not move ${step.legacy} to $relativeDestination" }
            }
        }
    }

    private fun predatesCutoff(files: Files, legacy: String, cutoff: Cutoff): Boolean {
        if (cutoff !is Cutoff.Before) return false
        val modified: Long = files.lastModifiedMillis(legacy) ?: return false
        return modified < cutoff.millis
    }
}
