package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.Logger
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
 */
internal object LegacyTempMigrations {

    /** The few file operations a pass needs, over absolute paths; one implementation per platform. */
    interface Files {
        fun exists(path: String): Boolean

        /** [renameNoReplace]: false when the destination exists (even if it appeared just now) or the move failed. */
        fun renameNoReplace(from: String, to: String): Boolean

        fun delete(path: String)

        fun createParentDirectory(path: String)
    }

    private val done: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    /**
     * Test seam: forgets which units were migrated, so a test can run the pass again for an id an
     * earlier test already used. The record is process-wide, so without this a test that reuses an
     * id silently skips its migration.
     */
    fun resetForTests() {
        done.value = emptySet()
    }

    fun migrateOnce(
        baseDirectory: String,
        unit: DownloadUnit,
        layout: UnitFileLayout,
        names: UnitFileLayout.Names,
        files: Files,
        logger: Logger,
    ) {
        val key = "$baseDirectory\u0000${unit.id}\u0000${unit.tempExtension}"
        if (key in done.value) return
        for (step: UnitFileLayout.MigrationStep in layout.migrationSteps(unit, names)) {
            try {
                run(baseDirectory, step, files, logger)
            } catch (e: Exception) {
                logger.w { "Could not migrate ${step.legacy} of $unit: ${e.message}" }
            }
        }
        done.update { it + key }
    }

    private fun run(baseDirectory: String, step: UnitFileLayout.MigrationStep, files: Files, logger: Logger) {
        val legacy = "$baseDirectory/${step.legacy}"
        if (!files.exists(legacy)) return
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
}
