@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** How the stand-in kills the child at the chosen system call (the Nth low-level operation, counted from 1). */
internal enum class KillModeR5 {
    /** Halts the process just before the Nth operation runs. */
    Before,

    /** The Nth operation must be a write: its first half reaches the file, then the process halts. */
    HalfWrite,
}

/** One operation of a scenario and what the parent needs to judge the state a kill leaves behind. */
internal class ScenarioR5(
    val name: String,
    /** Builds the state before the call; returns nothing, the parent knows the layout from [ScenarioLayoutR5]. */
    val setup: (ScenarioLayoutR5) -> Unit,
    /** The call under test, run in the child (and again in the parent after a kill to see that a following call recovers). */
    val run: (FileAccess, ScenarioLayoutR5) -> FileResult<Unit>,
    /** True when the call moves [ScenarioLayoutR5.source] to the destination (the source is consumed on success). */
    val isMove: Boolean,
    /** True when the destination held [ScenarioLayoutR5.oldBytes] before the call; false when it did not exist. */
    val destinationExistedBefore: Boolean,
    /** A rename of the source fails with CrossDevice once, which sends a move down its copy path. */
    val crossDevice: Boolean = false,
    /** The directory in which temporary files may appear. */
    val tempDir: (ScenarioLayoutR5) -> File,
)

internal class ScenarioLayoutR5(val root: File) {
    val directory: File = File(root, "d")
    val destination: File = File(directory, "dest.bin")
    val source: File = File(directory, "src.bin")
    val otherDirectory: File = File(root, "t")
    val nested: File = File(File(File(root, "a"), "b"), "dest.bin")
    val oldBytes: ByteArray = ByteArray(50_000) { (it % 251).toByte() }
    val newBytes: ByteArray = ByteArray(300_000) { ((it * 7) % 253).toByte() }
}

internal object ScenariosR5 {
    private fun seedOld(l: ScenarioLayoutR5) {
        l.directory.mkdirs()
        l.destination.writeBytes(l.oldBytes)
    }

    private fun seedSource(l: ScenarioLayoutR5) {
        l.directory.mkdirs()
        l.source.writeBytes(l.newBytes)
    }

    private fun write(durability: Durability, existing: ExistingFile = ExistingFile.Replace, parents: Boolean = false, tempInOther: Boolean = false) =
        { files: FileAccess, l: ScenarioLayoutR5 ->
            files.writeAtomically(
                l.destination.path,
                l.newBytes,
                WriteOptions(
                    durability = durability,
                    existing = existing,
                    createParentDirectories = parents,
                    tempDirectory = if (tempInOther) l.otherDirectory.path else null,
                ),
            )
        }

    val all: List<ScenarioR5> = listOf(
        ScenarioR5("replace-buffered", ::seedOld, write(Durability.Buffered), false, true, tempDir = { it.directory }),
        ScenarioR5("replace-synced", ::seedOld, write(Durability.Synced), false, true, tempDir = { it.directory }),
        ScenarioR5("replace-synced-with-directory", ::seedOld, write(Durability.SyncedWithDirectory), false, true, tempDir = { it.directory }),
        ScenarioR5(
            "replace-with-temp-directory",
            { seedOld(it); it.otherDirectory.mkdirs() },
            write(Durability.Synced, tempInOther = true),
            false,
            true,
            tempDir = { it.otherDirectory },
        ),
        ScenarioR5("create-no-replace", { it.directory.mkdirs() }, write(Durability.Synced, existing = ExistingFile.Fail), false, false, tempDir = { it.directory }),
        ScenarioR5(
            "create-with-parents-durable",
            { it.root.mkdirs() },
            { files, l ->
                files.writeAtomically(
                    l.nested.path,
                    l.newBytes,
                    WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true),
                )
            },
            false,
            false,
            tempDir = { it.nested.parentFile },
        ),
        ScenarioR5(
            "move-replace",
            { seedOld(it); seedSource(it) },
            { files, l -> files.move(l.source.path, l.destination.path) },
            true,
            true,
            tempDir = { it.directory },
        ),
        ScenarioR5(
            "move-no-replace",
            { seedSource(it) },
            { files, l -> files.move(l.source.path, l.destination.path, MoveOptions(existing = ExistingFile.Fail)) },
            true,
            false,
            tempDir = { it.directory },
        ),
        ScenarioR5(
            "move-replace-across-volumes",
            { seedOld(it); seedSource(it) },
            { files, l -> files.move(l.source.path, l.destination.path) },
            true,
            true,
            crossDevice = true,
            tempDir = { it.directory },
        ),
        ScenarioR5(
            "move-no-replace-across-volumes",
            { seedSource(it) },
            { files, l -> files.move(l.source.path, l.destination.path, MoveOptions(existing = ExistingFile.Fail)) },
            true,
            false,
            crossDevice = true,
            tempDir = { it.directory },
        ),
        ScenarioR5(
            "move-into-new-parent",
            { seedSource(it); it.root.mkdirs() },
            { files, l -> files.move(l.source.path, l.nested.path, MoveOptions(createParentDirectories = true)) },
            true,
            false,
            tempDir = { it.nested.parentFile },
        ),
    ).associateBy { it.name }.values.toList()

    fun byName(name: String): ScenarioR5 = all.first { it.name == name }
}

/**
 * A stand-in for the real file operations that counts every low-level call and halts the process (no `finally`, no hook)
 * at a chosen one. Deterministic: the kill point is a call number, never a time.
 */
internal class HaltingOpsR5(
    private val real: FileSystemOps,
    private val killAt: Int,
    private val mode: KillModeR5,
    private val log: MutableList<String>,
    private val failRenameOf: String?,
) : FileSystemOps by real {
    private val calls: AtomicInteger = AtomicInteger(0)
    private var crossDeviceUsed: Boolean = false

    val count: Int get() = calls.get()

    private fun step(name: String) {
        log += name
        if (calls.incrementAndGet() == killAt && mode == KillModeR5.Before) Runtime.getRuntime().halt(EXIT_KILLED)
    }

    override fun metadata(path: String, followLinks: Boolean) = run { step("metadata"); real.metadata(path, followLinks) }
    override fun list(directory: String) = run { step("list"); real.list(directory) }
    override fun createDirectory(path: String) = run { step("createDirectory"); real.createDirectory(path) }
    override fun createNew(path: String, protection: FileProtection): WritableFile {
        step("createNew")
        val inner: WritableFile = real.createNew(path, protection)
        return object : WritableFile {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                log += "write"
                if (calls.incrementAndGet() == killAt) {
                    when (mode) {
                        KillModeR5.Before -> Runtime.getRuntime().halt(EXIT_KILLED)
                        KillModeR5.HalfWrite -> {
                            inner.write(bytes, offset, length / 2)
                            Runtime.getRuntime().halt(EXIT_KILLED)
                        }
                    }
                }
                inner.write(bytes, offset, length)
            }

            override fun sync(full: Boolean) = run { step("sync"); inner.sync(full) }
            override fun close() = run { step("close"); inner.close() }
        }
    }

    override fun openForRead(path: String) = run { step("openForRead"); real.openForRead(path) }
    override fun rename(from: String, to: String) {
        step("rename")
        if (failRenameOf != null && !crossDeviceUsed && from == failRenameOf) {
            crossDeviceUsed = true
            throw FileOpException(FileError.CrossDevice(from))
        }
        real.rename(from, to)
    }

    override fun link(existing: String, newPath: String) = run { step("link"); real.link(existing, newPath) }
    override fun remove(path: String) = run { step("remove"); real.remove(path) }
    override fun syncDirectory(directory: String, full: Boolean) = run { step("syncDirectory"); real.syncDirectory(directory, full) }
    override fun usableSpace(path: String) = run { step("usableSpace"); real.usableSpace(path) }

    companion object {
        const val EXIT_KILLED: Int = 137
    }
}

/**
 * The child JVM: `root scenario killAt mode`. killAt 0 = never kill (dry run); the list of operation names it saw is
 * written to `<root>/ops.txt` before the call returns control to the process end.
 */
internal object CrashChildR5 {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val scenario: ScenarioR5 = ScenariosR5.byName(args[1])
        val killAt: Int = args[2].toInt()
        val mode: KillModeR5 = KillModeR5.valueOf(args[3])
        val layout = ScenarioLayoutR5(root)
        val log: MutableList<String> = mutableListOf()
        val ops = HaltingOpsR5(NioFileSystemOps(), killAt, mode, log, if (scenario.crossDevice) layout.source.path else null)
        val files: FileAccess = fileAccessOver(ops)
        val result: FileResult<Unit> = scenario.run(files, layout)
        File(root, "ops.txt").writeText(log.joinToString("\n"))
        File(root, "result.txt").writeText(result.toString())
        System.exit(if (result.isSuccess) 0 else 3)
    }
}
