package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 1 of the breaker for unit G: an exhaustive model check of writeAtomically on the fake. Every durability, both
 * existing-file modes, with and without an old file, every crash point (after the n-th low-level operation of the
 * write) and every crash kind: the state after the restart must satisfy the documented contract of Durability.
 */
class FakePowerLossModelCheckR1Test {

    private val oldBytes: ByteArray = "OLD-CONTENT-1234".encodeToByteArray()
    private val newBytes: ByteArray = "NEW-CONTENT-ABCDEFGHIJ".encodeToByteArray()

    private var executed: Int = 0

    private class Variant(val kind: CrashKind, val revert: Boolean) {
        override fun toString(): String = "$kind revert=$revert"
    }

    private val variants: List<Variant> = listOf(
        Variant(CrashKind.ProcessDeath, false),
        Variant(CrashKind.PowerLoss, false),
        Variant(CrashKind.PowerLoss, true),
    )

    private fun content(fake: FakeFileAccess, path: String): String? {
        val entry: FakeEntry? = fake.entries()[path]
        return (entry as? FakeEntry.File)?.bytes?.decodeToString()
    }

    private fun runCase(durability: Durability, existing: ExistingFile, withOld: Boolean, crashOp: FakeOperation, occurrence: Int, variant: Variant) {
        val fake: FakeFileAccess = FakeFileAccess()
        fake.createDirectory("/d")
        if (withOld) fake.createFile("/d/f", oldBytes)
        fake.markAllDurable()
        fake.crashAfter(crashOp, occurrence)
        var crashed: Boolean = false
        var result: FileResult<Unit>? = null
        try {
            result = fake.writeAtomically("/d/f", newBytes, WriteOptions(durability = durability, existing = existing))
        } catch (_: SimulatedCrash) {
            crashed = true
        }
        if (!crashed) return // that operation does not occur this often under this configuration
        executed++
        val restarted: FakeFileAccess = fake.restartAfterCrash(variant.kind, variant.revert)
        val label: String = "$durability $existing old=$withOld crash after #$occurrence $crashOp, $variant"
        val atPath: String? = content(restarted, "/d/f")
        val old: String? = if (withOld) oldBytes.decodeToString() else null
        val new: String = newBytes.decodeToString()
        val power: Boolean = variant.kind == CrashKind.PowerLoss
        val allowed: MutableSet<String?> = mutableSetOf(old, new)
        if (power && durability == Durability.Buffered) allowed += ""
        assertTrue(atPath in allowed, "$label: the path holds '$atPath', allowed $allowed")
        // After the directory sync of a SyncedWithDirectory write the new name and content are durable.
        val directorySyncDone: Boolean = durability == Durability.SyncedWithDirectory && crashOp == FakeOperation.SyncDirectory
        if (directorySyncDone) assertEquals(new, atPath, "$label: a synced directory entry must survive power loss")
        // A process death never loses what the process had already renamed into place.
        val renamedIn: Boolean = crashOp == FakeOperation.SyncDirectory || (existing == ExistingFile.Replace && crashOp == FakeOperation.Rename) ||
            (existing == ExistingFile.Fail && (crashOp == FakeOperation.Link || crashOp == FakeOperation.Remove))
        if (variant.kind == CrashKind.ProcessDeath) {
            assertEquals(if (renamedIn) new else old, atPath, "$label")
        }
        // Without a revert of unsynced directory entries a Synced write that reached the rename keeps the whole new content.
        if (power && !variant.revert && renamedIn && durability != Durability.Buffered) assertEquals(new, atPath, "$label")
        // At most one temporary file is left, and it is told apart by its name.
        val leftovers: List<String> = restarted.entries().keys.filter { it.startsWith("/d/") && it != "/d/f" }
        assertTrue(leftovers.size <= 1, "$label: leftovers $leftovers")
        // A rename moves the temp name away: nothing is left once a replacing write got past it (or a no-replace one removed its temp).
        val settledClean: Boolean = renamedIn && (existing == ExistingFile.Replace || crashOp == FakeOperation.Remove || crashOp == FakeOperation.SyncDirectory)
        if (settledClean && variant.kind == CrashKind.ProcessDeath) assertEquals(emptyList(), leftovers, "$label: the temp name must be gone")
        for (leftover: String in leftovers) assertTrue(FileNames.isTempName(leftover.removePrefix("/d/")), "$label: '$leftover' is not a temp name")
        // The restarted fake is a working file system: the write can be redone and succeeds.
        val redo: FileResult<Unit> = restarted.writeAtomically("/d/f", newBytes, WriteOptions(durability = durability))
        assertTrue(redo is FileResult.Success, "$label: redo failed $redo")
        assertEquals(new, content(restarted, "/d/f"), label)
        assertTrue(result == null)
    }

    @Test
    fun `every crash point of a replacing write satisfies the durability contract`() {
        val operations: List<FakeOperation> = listOf(
            FakeOperation.Metadata, FakeOperation.CreateNew, FakeOperation.Write, FakeOperation.Sync, FakeOperation.Close,
            FakeOperation.Rename, FakeOperation.SyncDirectory, FakeOperation.Remove,
        )
        for (durability: Durability in Durability.entries) for (withOld: Boolean in listOf(false, true)) for (op: FakeOperation in operations) {
            for (occurrence: Int in 1..2) for (variant: Variant in variants) runCase(durability, ExistingFile.Replace, withOld, op, occurrence, variant)
        }
        assertTrue(executed >= 100, "the model check ran only $executed crash cases")
    }

    @Test
    fun `every crash point of a no-replace write satisfies the durability contract`() {
        val operations: List<FakeOperation> = listOf(
            FakeOperation.Metadata, FakeOperation.CreateNew, FakeOperation.Write, FakeOperation.Sync, FakeOperation.Close,
            FakeOperation.Link, FakeOperation.Remove, FakeOperation.SyncDirectory,
        )
        for (durability: Durability in Durability.entries) for (op: FakeOperation in operations) {
            for (occurrence: Int in 1..2) for (variant: Variant in variants) runCase(durability, ExistingFile.Fail, false, op, occurrence, variant)
        }
        assertTrue(executed >= 50, "the model check ran only $executed crash cases")
    }

    @Test
    fun `a write with created parents reaches durability only through every created directory level`() {
        var crashes: Int = 0
        for (variant: Variant in variants.filter { it.revert }) {
            for (crashOccurrence: Int in 1..4) {
                val fake: FakeFileAccess = FakeFileAccess()
                fake.createDirectory("/d")
                fake.markAllDurable()
                fake.crashAfter(FakeOperation.SyncDirectory, crashOccurrence)
                var crashed: Boolean = false
                try {
                    fake.writeAtomically(
                        "/d/a/b/c/f", newBytes,
                        WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true),
                    )
                } catch (_: SimulatedCrash) {
                    crashed = true
                }
                if (!crashed) continue
                crashes++
                val restarted: FakeFileAccess = fake.restartAfterCrash(variant.kind, variant.revert)
                val reachable: Boolean = content(restarted, "/d/a/b/c/f") != null
                // Only when every level (a, b, c under their parents) and the file's directory are synced may the file survive:
                // three created levels plus the final sync are four directory syncs in all.
                if (crashOccurrence < 4) assertTrue(!reachable || crashOccurrence == 4, "crash after directory sync #$crashOccurrence: the file survived with an unsynced level: ${restarted.entries().keys}")
                if (crashOccurrence == 4) assertEquals(newBytes.decodeToString(), content(restarted, "/d/a/b/c/f"))
            }
        }
        assertEquals(4, crashes, "each of the four directory syncs must be a crash point")
    }
}
