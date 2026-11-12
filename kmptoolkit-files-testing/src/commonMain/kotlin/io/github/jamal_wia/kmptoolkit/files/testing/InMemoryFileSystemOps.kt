@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.foldCaseForKey
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import kotlin.concurrent.Volatile

private const val ROOT: Long = 1L
private const val MAX_LINK_HOPS: Int = 40
private const val MAX_NAME_UTF8_BYTES: Int = 255
private const val ELOOP: Int = 40
private const val EINVAL: Int = 22
private const val EBUSY: Int = 16
private const val PRUNE_THRESHOLD: Int = 256

private enum class NodeKind { File, Directory, Symlink }

/** One name inside a directory: the spelling it was created with, and the inode it points at. */
private class Entry(val name: String, val inode: Long)

/** An inode: what a rename carries and hard links share. */
private class Node(val kind: NodeKind) {
    /** File content. Never mutated in place: a write replaces the array, so a reader's snapshot stays valid. */
    var bytes: ByteArray = ByteArray(0)

    /** What a power loss leaves: the content at the last sync (empty for a file that was never synced). */
    var durableBytes: ByteArray = ByteArray(0)
    var mtime: Long = 0L
    var target: String = ""

    /** Directory: folded name to entry. */
    var entries: MutableMap<String, Entry> = HashMap()

    /** What a power loss leaves of the directory: the entries at the last syncDirectory or seeding. */
    var durableEntries: Map<String, Entry> = emptyMap()
    var links: Int = 0
}

/** Where a path lands: the directory that holds the last name, the name, and the inode there (null when absent). */
private class Located(val directory: Long, val name: String?, val inode: Long?, val ancestors: List<Long>)

private class Fault(
    val operation: FakeOperation,
    val error: FileError,
    var remaining: Int,
    val match: (String) -> Boolean,
)

/**
 * The in-memory [FileSystemOps] behind [FakeFileAccess]: POSIX path syntax, inodes with hard links and symbolic
 * links, per-file synced content and per-directory synced entries, fault and crash injection. Every state change
 * happens under one lock; hooks run outside it, on the caller's thread.
 */
internal class InMemoryFileSystemOps(private val caseSensitive: Boolean) : FileSystemOps {
    private val monitor: Monitor = Monitor()
    private val nodes: HashMap<Long, Node> = HashMap()
    private var nextId: Long = ROOT + 1

    private val faults: MutableList<Fault> = ArrayList()
    private var crashOperation: FakeOperation? = null
    private var crashOccurrence: Int = 0
    private var crashSeen: Int = 0
    private var crashed: Boolean = false
    private val log: MutableList<FakeOperationRecord> = ArrayList()

    @Volatile
    var nowMillisValue: Long = 0L

    @Volatile
    var hardLinksSupported: Boolean = true

    @Volatile
    var beforeOperation: ((FakeOperation, String) -> Unit)? = null

    /** Free bytes the volume reports; null = unknown. A write that does not fit fails with NoSpace. Guarded by [monitor]. */
    private var free: Long? = null

    init {
        nodes[ROOT] = Node(NodeKind.Directory).also { it.links = 1 }
    }

    override val windowsPaths: Boolean get() = false

    override fun nowMillis(): Long = nowMillisValue

    // ---- the operation envelope -------------------------------------------------------------------

    /** Hooks, crash freeze, fault matching, the log and crash-after counting around one low-level operation. */
    private fun <T> exec(operation: FakeOperation, path: String, second: String? = null, body: () -> T): T {
        beforeOperation?.invoke(operation, path)
        var crashNow = false
        val result: T = monitor.withLock {
            if (crashed) throw SimulatedCrash()
            log += FakeOperationRecord(operation, path, second)
            val fault: Fault? = faults.firstOrNull { it.operation == operation && it.remaining > 0 && it.match(path) }
            if (fault != null) {
                fault.remaining--
                throw FileOpException(fault.error)
            }
            val value: T = body()
            if (operation == crashOperation) {
                crashSeen++
                if (crashSeen == crashOccurrence) {
                    crashed = true
                    crashNow = true
                }
            }
            value
        }
        if (crashNow) throw SimulatedCrash()
        return result
    }

    private fun fail(error: FileError): Nothing = throw FileOpException(error)

    // ---- name and path helpers --------------------------------------------------------------------

    private fun key(name: String): String = if (caseSensitive) name else foldCaseForKey(name)

    private fun node(id: Long): Node = nodes.getValue(id)

    private fun lookup(directory: Long, name: String): Long? = node(directory).entries[key(name)]?.inode

    private fun checkNameLength(name: String, path: String) {
        if (name.encodeToByteArray().size > MAX_NAME_UTF8_BYTES) fail(FileError.NameTooLong(path))
    }

    /**
     * Walks [path]. Intermediate symbolic links are always followed, the last one only with [followFinal]. A missing
     * intermediate directory is NotFound and a non-directory one NotADirectory (thrown, with [path]); a missing last
     * name gives `inode = null`.
     */
    private fun locate(path: String, followFinal: Boolean): Located {
        val queue: ArrayDeque<String> = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        val stack: ArrayList<Long> = arrayListOf(ROOT)
        var hops: Int = 0
        var lastName: String? = null
        var lastInode: Long? = ROOT
        while (queue.isNotEmpty()) {
            val segment: String = queue.removeFirst()
            if (segment == ".") continue
            if (segment == "..") {
                if (stack.size > 1) stack.removeAt(stack.size - 1)
                lastName = null
                lastInode = stack.last()
                continue
            }
            val directory: Long = stack.last()
            val found: Long? = lookup(directory, segment)
            val isFinal: Boolean = queue.none { it != "." }
            if (found == null) {
                if (!isFinal) fail(FileError.NotFound(path))
                lastName = segment
                lastInode = null
                break
            }
            val child: Node = node(found)
            if (child.kind == NodeKind.Symlink && (!isFinal || followFinal)) {
                if (++hops > MAX_LINK_HOPS) fail(FileError.Io(path, ELOOP))
                val targetSegments: List<String> = child.target.split('/').filter { it.isNotEmpty() }
                // path-hygiene-ignore: PH5 the fake file system is POSIX on every host, so its paths are logical names, not host paths
                if (child.target.startsWith("/")) {
                    stack.clear()
                    stack += ROOT
                }
                for (index: Int in targetSegments.indices.reversed()) queue.addFirst(targetSegments[index])
                if (isFinal && queue.isEmpty()) {
                    // An empty or root-only target: the link points at the directory we are in.
                    lastName = null
                    lastInode = stack.last()
                }
                continue
            }
            if (isFinal) {
                lastName = segment
                lastInode = found
            } else {
                if (child.kind != NodeKind.Directory) fail(FileError.NotADirectory(path))
                stack += found
            }
        }
        return Located(
            directory = if (lastName == null && lastInode != null && stack.size > 1) stack[stack.size - 2] else stack.last(),
            name = lastName,
            inode = lastInode,
            ancestors = stack.toList(),
        )
    }

    private fun unlink(id: Long) {
        val inode: Node = node(id)
        inode.links--
        if (inode.links <= 0) {
            if (inode.kind == NodeKind.File) free = free?.plus(inode.bytes.size.toLong())
            // Kept for now: a power loss that reverts the directory entry resurrects the old file, as a journal
            // that did not commit the rename would. Pruned once no durable entry can reach it.
            orphans += id
            if (orphans.size > PRUNE_THRESHOLD) pruneOrphans()
        }
    }

    private val orphans: MutableList<Long> = ArrayList()

    private fun pruneOrphans() {
        val referenced: HashSet<Long> = HashSet()
        for (directory: Node in nodes.values) {
            for (entry: Entry in directory.durableEntries.values) referenced += entry.inode
        }
        val kept: MutableList<Long> = ArrayList()
        for (id: Long in orphans) {
            if (id in referenced) kept += id else nodes.remove(id)
        }
        orphans.clear()
        orphans += kept
    }

    private fun addEntry(directory: Long, name: String, inode: Long) {
        node(directory).entries[key(name)] = Entry(name, inode)
        node(directory).mtime = nowMillisValue
    }

    private fun newNode(kind: NodeKind): Pair<Long, Node> {
        val id: Long = nextId++
        val created: Node = Node(kind)
        created.mtime = nowMillisValue
        nodes[id] = created
        return id to created
    }

    // ---- FileSystemOps ----------------------------------------------------------------------------

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? =
        exec(FakeOperation.Metadata, path) {
            val located: Located = try {
                locate(path, followLinks)
            } catch (e: FileOpException) {
                if (e.error is FileError.NotFound || e.error is FileError.NotADirectory) return@exec null
                throw e
            }
            val id: Long = located.inode ?: return@exec null
            val inode: Node = node(id)
            when (inode.kind) {
                NodeKind.File -> FileMetadata(FileKind.RegularFile, inode.bytes.size.toLong(), inode.mtime)
                NodeKind.Directory -> FileMetadata(FileKind.Directory, 0L, inode.mtime)
                NodeKind.Symlink -> FileMetadata(FileKind.SymbolicLink, 0L, inode.mtime)
            }
        }

    override fun list(directory: String): List<String>? =
        exec(FakeOperation.List, directory) {
            val located: Located = try {
                locate(directory, true)
            } catch (e: FileOpException) {
                if (e.error is FileError.NotFound) return@exec null
                throw e
            }
            val id: Long = located.inode ?: return@exec null
            val inode: Node = node(id)
            if (inode.kind != NodeKind.Directory) fail(FileError.NotADirectory(directory))
            inode.entries.values.map { it.name }
        }

    override fun createDirectory(path: String) {
        exec(FakeOperation.CreateDirectory, path) {
            val located: Located = locate(path, false)
            val name: String = located.name ?: fail(FileError.AlreadyExists(path))
            if (located.inode != null) fail(FileError.AlreadyExists(path))
            checkNameLength(name, path)
            val (id: Long, _) = newNode(NodeKind.Directory)
            node(id).links = 1
            addEntry(located.directory, name, id)
        }
    }

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        val handleNode: Node = exec(FakeOperation.CreateNew, path) {
            val located: Located = locate(path, false)
            val name: String = located.name ?: fail(FileError.AlreadyExists(path))
            if (located.inode != null) fail(FileError.AlreadyExists(path))
            checkNameLength(name, path)
            val (created: Long, createdNode: Node) = newNode(NodeKind.File)
            createdNode.links = 1
            addEntry(located.directory, name, created)
            createdNode
        }
        return InMemoryWritableFile(handleNode, path)
    }

    override fun openForRead(path: String): ReadableFile {
        val snapshot: ByteArray = exec(FakeOperation.OpenForRead, path) {
            val located: Located = locate(path, true)
            val id: Long = located.inode ?: fail(FileError.NotFound(path))
            val inode: Node = node(id)
            if (inode.kind == NodeKind.Directory) fail(FileError.IsADirectory(path))
            inode.bytes
        }
        return object : ReadableFile {
            private var position: Int = 0

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= snapshot.size) return -1
                val count: Int = minOf(length, snapshot.size - position)
                snapshot.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }

            override fun close() {}
        }
    }

    override fun rename(from: String, to: String) {
        exec(FakeOperation.Rename, from, to) {
            val source: Located = locate(from, false)
            val sourceId: Long = source.inode ?: fail(FileError.NotFound(from))
            val sourceName: String = source.name ?: fail(FileError.Io(from, EBUSY))
            val target: Located = locate(to, false)
            val targetName: String = target.name ?: fail(FileError.Io(to, EBUSY))
            val moved: Node = node(sourceId)
            if (moved.kind == NodeKind.Directory && sourceId in target.ancestors) fail(FileError.Io(from, EINVAL))
            val existingId: Long? = target.inode
            if (existingId == sourceId && !(source.directory == target.directory && key(sourceName) == key(targetName) && sourceName != targetName)) {
                return@exec
            }
            if (existingId != null && existingId != sourceId) {
                val existing: Node = node(existingId)
                if (moved.kind == NodeKind.Directory) {
                    if (existing.kind != NodeKind.Directory) fail(FileError.NotADirectory(to))
                    if (existing.entries.isNotEmpty()) fail(FileError.DirectoryNotEmpty(to))
                } else if (existing.kind == NodeKind.Directory) {
                    fail(FileError.IsADirectory(to))
                }
            }
            if (targetName != sourceName || source.directory != target.directory) checkNameLength(targetName, to)
            // Replacing a different entry keeps the spelling it was created with; renaming an entry onto another
            // spelling of itself (File to file) changes the spelling.
            val spelling: String =
                if (existingId != null && existingId != sourceId) node(target.directory).entries.getValue(key(targetName)).name else targetName
            node(source.directory).entries.remove(key(sourceName))
            node(source.directory).mtime = nowMillisValue
            addEntry(target.directory, spelling, sourceId)
            if (existingId != null && existingId != sourceId) unlink(existingId)
        }
    }

    override fun link(existing: String, newPath: String) {
        exec(FakeOperation.Link, newPath, existing) {
            if (!hardLinksSupported) fail(FileError.Unsupported(newPath))
            val source: Located = locate(existing, false)
            val sourceId: Long = source.inode ?: fail(FileError.NotFound(existing))
            if (node(sourceId).kind == NodeKind.Directory) fail(FileError.Unsupported(newPath))
            val target: Located = locate(newPath, false)
            val name: String = target.name ?: fail(FileError.AlreadyExists(newPath))
            if (target.inode != null) fail(FileError.AlreadyExists(newPath))
            checkNameLength(name, newPath)
            addEntry(target.directory, name, sourceId)
            node(sourceId).links++
        }
    }

    override fun remove(path: String) {
        exec(FakeOperation.Remove, path) {
            val located: Located = locate(path, false)
            val id: Long = located.inode ?: fail(FileError.NotFound(path))
            val name: String = located.name ?: fail(FileError.Io(path, EBUSY))
            val inode: Node = node(id)
            if (inode.kind == NodeKind.Directory && inode.entries.isNotEmpty()) fail(FileError.DirectoryNotEmpty(path))
            node(located.directory).entries.remove(key(name))
            node(located.directory).mtime = nowMillisValue
            unlink(id)
        }
    }

    override fun syncDirectory(directory: String, full: Boolean) {
        exec(FakeOperation.SyncDirectory, directory) {
            val located: Located = locate(directory, true)
            val id: Long = located.inode ?: fail(FileError.NotFound(directory))
            val inode: Node = node(id)
            if (inode.kind != NodeKind.Directory) fail(FileError.NotADirectory(directory))
            inode.durableEntries = HashMap(inode.entries)
        }
    }

    override fun usableSpace(path: String): Long? = exec(FakeOperation.UsableSpace, path) { free }

    /** Holds the inode itself, not its id: a handle keeps working after its name was unlinked, as on a real system. */
    private inner class InMemoryWritableFile(private val inode: Node, private val path: String) : WritableFile {
        private var closed: Boolean = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            exec(FakeOperation.Write, path) {
                val available: Long? = free
                if (available != null && length > available) fail(FileError.NoSpace(path))
                free = available?.minus(length)
                inode.bytes = inode.bytes + bytes.copyOfRange(offset, offset + length)
                inode.mtime = nowMillisValue
            }
        }

        override fun sync(full: Boolean) {
            exec(FakeOperation.Sync, path) {
                inode.durableBytes = inode.bytes
            }
        }

        override fun close() {
            if (closed) return
            exec(FakeOperation.Close, path) { closed = true }
        }
    }

    // ---- state access for FakeFileAccess ----------------------------------------------------------

    fun freeSpace(): Long? = monitor.withLock { free }

    fun setFreeSpace(value: Long?) {
        monitor.withLock { free = value }
    }

    fun addFault(operation: FakeOperation, error: FileError, count: Int, match: (String) -> Boolean) {
        monitor.withLock { faults += Fault(operation, error, count, match) }
    }

    fun armCrash(operation: FakeOperation, occurrence: Int) {
        monitor.withLock {
            crashOperation = operation
            crashOccurrence = occurrence
            crashSeen = 0
        }
    }

    fun hasCrashed(): Boolean = monitor.withLock { crashed }

    fun operations(): List<FakeOperationRecord> = monitor.withLock { log.toList() }

    private fun requireAbsolute(path: String) {
        // path-hygiene-ignore: PH5 the fake file system is POSIX on every host, so its paths are logical names, not host paths
        require(path.startsWith("/") && '\u0000' !in path) { "A seeded path must be absolute, with '/' separators: '$path'" }
    }

    private fun checkAlive() {
        check(!crashed) { "The fake crashed; build a new one with restartAfterCrash" }
    }

    /** Creates every missing directory of [segments] below the root and returns the last one (durable entries). */
    private fun ensureDirectories(segments: List<String>, path: String): Long {
        var current: Long = ROOT
        for (segment: String in segments) {
            val found: Long? = lookup(current, segment)
            if (found == null) {
                val (id: Long, created: Node) = newNode(NodeKind.Directory)
                created.links = 1
                addEntry(current, segment, id)
                node(current).durableEntries = HashMap(node(current).entries)
                current = id
            } else {
                require(node(found).kind == NodeKind.Directory) { "'$segment' in '$path' is not a directory" }
                current = found
            }
        }
        return current
    }

    private fun splitForSeeding(path: String): Pair<List<String>, String> {
        requireAbsolute(path)
        val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty()) { "The root cannot be seeded: '$path'" }
        require(segments.none { it == "." || it == ".." }) { "A seeded path must not contain '.' or '..': '$path'" }
        return segments.dropLast(1) to segments.last()
    }

    fun seedFile(path: String, bytes: ByteArray) {
        monitor.withLock {
            checkAlive()
            val (parents: List<String>, name: String) = splitForSeeding(path)
            val directory: Long = ensureDirectories(parents, path)
            val existing: Long? = lookup(directory, name)
            if (existing != null) {
                require(node(existing).kind != NodeKind.Directory) { "'$path' is a directory" }
                node(directory).entries.remove(key(name))
                unlink(existing)
            }
            val (id: Long, created: Node) = newNode(NodeKind.File)
            created.links = 1
            created.bytes = bytes.copyOf()
            created.durableBytes = created.bytes
            addEntry(directory, name, id)
            node(directory).durableEntries = HashMap(node(directory).entries)
        }
    }

    fun seedDirectory(path: String) {
        monitor.withLock {
            checkAlive()
            requireAbsolute(path)
            val segments: List<String> = path.split('/').filter { it.isNotEmpty() }
            require(segments.none { it == "." || it == ".." }) { "A seeded path must not contain '.' or '..': '$path'" }
            ensureDirectories(segments, path)
        }
    }

    fun seedSymbolicLink(path: String, target: String) {
        monitor.withLock {
            checkAlive()
            val (parents: List<String>, name: String) = splitForSeeding(path)
            val directory: Long = ensureDirectories(parents, path)
            require(lookup(directory, name) == null) { "'$path' already exists" }
            val (id: Long, created: Node) = newNode(NodeKind.Symlink)
            created.links = 1
            created.target = target
            addEntry(directory, name, id)
            node(directory).durableEntries = HashMap(node(directory).entries)
        }
    }

    fun seedLastModified(path: String, millis: Long) {
        monitor.withLock {
            checkAlive()
            requireAbsolute(path)
            val located: Located = locate(path, false)
            val id: Long = requireNotNull(located.inode) { "'$path' does not exist" }
            node(id).mtime = millis
        }
    }

    fun markAllDurable() {
        monitor.withLock {
            checkAlive()
            for (inode: Node in nodes.values) {
                inode.durableBytes = inode.bytes
                inode.durableEntries = HashMap(inode.entries)
            }
        }
    }

    fun snapshotEntries(): Map<String, FakeEntry> = monitor.withLock {
        val result: LinkedHashMap<String, FakeEntry> = LinkedHashMap()
        fun walk(directory: Long, prefix: String) {
            for (entry: Entry in node(directory).entries.values.sortedBy { it.name }) {
                val path: String = "$prefix/${entry.name}"
                val inode: Node = node(entry.inode)
                when (inode.kind) {
                    NodeKind.File -> result[path] = FakeEntry.File(inode.bytes)
                    NodeKind.Symlink -> result[path] = FakeEntry.SymbolicLink(inode.target)
                    NodeKind.Directory -> {
                        result[path] = FakeEntry.Directory
                        walk(entry.inode, path)
                    }
                }
            }
        }
        walk(ROOT, "")
        result
    }

    /**
     * A new file system holding what survives the crash. [powerLoss]: every file keeps only its last synced content
     * and, with [revertDirectories], every directory only the entries of its last sync; whatever is then unreachable
     * is gone. Otherwise (process death) everything is kept. The result starts without faults, crash or log.
     */
    fun restarted(powerLoss: Boolean, revertDirectories: Boolean): InMemoryFileSystemOps = monitor.withLock {
        check(crashed) { "No crash happened: there is nothing to restart" }
        val next: InMemoryFileSystemOps = InMemoryFileSystemOps(caseSensitive)
        next.nowMillisValue = nowMillisValue
        next.hardLinksSupported = hardLinksSupported
        next.free = free
        next.nextId = nextId
        next.nodes.clear()
        for ((id: Long, inode: Node) in nodes) {
            val copy: Node = Node(inode.kind)
            copy.mtime = inode.mtime
            copy.target = inode.target
            copy.links = inode.links
            copy.durableBytes = inode.durableBytes
            copy.durableEntries = HashMap(inode.durableEntries)
            copy.bytes = if (powerLoss) inode.durableBytes else inode.bytes
            copy.entries = HashMap(if (powerLoss && revertDirectories) inode.durableEntries else inode.entries)
            next.nodes[id] = copy
        }
        if (powerLoss) {
            next.dropUnreachable()
            for (inode: Node in next.nodes.values) {
                inode.durableBytes = inode.bytes
                inode.durableEntries = HashMap(inode.entries)
            }
        }
        next
    }

    /** Removes inodes no entry reaches and recounts links, as a file system check after a power loss would. */
    private fun dropUnreachable() {
        val counts: HashMap<Long, Int> = HashMap()
        val queue: ArrayDeque<Long> = ArrayDeque(listOf(ROOT))
        counts[ROOT] = 1
        while (queue.isNotEmpty()) {
            val directory: Node = node(queue.removeFirst())
            if (directory.kind != NodeKind.Directory) continue
            for (entry: Entry in directory.entries.values) {
                val seen: Boolean = counts.containsKey(entry.inode)
                counts[entry.inode] = (counts[entry.inode] ?: 0) + 1
                if (!seen && nodes.containsKey(entry.inode)) queue.addLast(entry.inode)
            }
        }
        nodes.keys.retainAll(counts.keys)
        for ((id: Long, count: Int) in counts) nodes[id]?.links = count
    }
}
