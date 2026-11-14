package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.exists
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import io.github.jamal_wia.kmptoolkit.files.withIo
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * What [FileAccessContract] needs to check one [FileAccess]: the instance, an empty directory to work in, and how to
 * make a symbolic link where the file system has them.
 *
 * @property files the implementation under test.
 * @property root an existing, empty directory, absolute in [files]' path syntax. Every check works inside it.
 * @property separator the character that joins a directory and a name in [files]' syntax.
 * @property createSymbolicLink creates a symbolic link at `path` pointing at `target` (an absolute path in the same
 *   syntax), or `null` where the checks that need a link are to be skipped.
 * @property setLastModified sets the modification time of the entry at `path` to `millis` (epoch milliseconds) and
 *   returns whether it worked, or is `null` where the age checks of `sweep` are to be skipped. A check whose call
 *   returns `false` fails with a [FileAccessContractViolation] saying the fixture could not set the time.
 * @property nowMillis the clock of the file system under test in epoch milliseconds (default: the wall clock); the
 *   age checks date entries relative to it. A [FakeFileAccess] fixture passes `{ fake.nowMillis }`.
 * @property cleanup runs after the check, even when it failed: remove what the fixture created.
 * @since 5.0.0
 */
public class FileAccessFixture(
    public val files: FileAccess,
    public val root: String,
    // path-hygiene-ignore: PH5 the default separator of a fixture is the POSIX one of the fake; real fixtures pass their own
    public val separator: Char = '/',
    public val createSymbolicLink: ((path: String, target: String) -> Unit)? = null,
    public val setLastModified: ((path: String, millis: Long) -> Boolean)? = null,
    public val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    public val cleanup: () -> Unit = {},
)

/**
 * A contract of [FileAccess] that an implementation does not keep. The message starts with the name of the check.
 *
 * @since 5.0.0
 */
public class FileAccessContractViolation(message: String) : AssertionError(message)

/**
 * Every observable [FileAccess] contract as a plain check, for the implementations that must keep it: a decorator
 * that adds logging, retries or a quota, a custom [FileAccess], or a new platform. Each check builds its own fixture
 * with [createFixture], works only inside the fixture's root, runs the fixture's cleanup and throws
 * [FileAccessContractViolation] naming the rule when the implementation breaks it. Crash and power-loss behaviour is
 * not observable through the interface and is covered by `FakeFileAccess` and its own tests.
 *
 * ```kotlin
 * @Test
 * fun `my decorator keeps the FileAccess contract`() {
 *     FileAccessContract { FileAccessFixture(MyDecorator(createFileAccess()), root = newEmptyDirectory()) }.verifyAll()
 * }
 * ```
 *
 * The checks never assert a modification time or the exact free space: a real clock and a real volume cannot be
 * pinned from outside. They use plain Kotlin only (no test framework), so the module has no test-library dependency.
 *
 * @param createFixture builds a fresh fixture; called once per check.
 * @since 5.0.0
 */
public class FileAccessContract(private val createFixture: () -> FileAccessFixture) {

    /** Runs every check below, each with its own fixture; stops at the first violation. */
    public fun verifyAll() {
        metadataReportsKinds()
        metadataDoesNotFollowLinksByDefault()
        metadataFollowLinksReportsTheTarget()
        metadataOfADanglingLinkIsNullWhenFollowing()
        readBytesFollowsSymbolicLinks()
        listIsSortedWithoutDotEntries()
        listOfAnAbsentDirectoryIsEmpty()
        listOfAFileIsNotADirectory()
        createDirectoriesCreatesParentsAndIsIdempotent()
        createDirectoriesOverAFileIsNotADirectory()
        createDirectoriesByConcurrentCreatorsAllSucceed()
        createDirectoriesDurableGivesTheSameResults()
        readBytesOfAnAbsentFileIsNotFound()
        readBytesOfADirectoryIsADirectory()
        readBytesLargerThanMaxIsTooLargeAndExactMaxIsRead()
        readBytesRejectsAnInvalidMax()
        writeAtomicallyReplacesTheWholeContent()
        writeAtomicallyAcceptsEmptyContent()
        writeAtomicallyWithAMissingParentIsNotFound()
        writeAtomicallyCreatesParentDirectoriesWhenAsked()
        writeAtomicallyOverADirectoryIsADirectory()
        writeAtomicallyWithFailRefusesAnExistingTarget()
        writeAtomicallyWithFailRefusesADanglingLink()
        writeAtomicallyLeavesNoTemporaryFile()
        writeAtomicallyReplacesASymbolicLinkNotItsTarget()
        writeAtomicallyHonoursTheTempDirectoryAndLeavesItClean()
        writeAtomicallyWithACustomTempPrefixLeavesNothing()
        writeAtomicallyRejectsRelativeEmptyAndNulPaths()
        everyFunctionRejectsRelativeEmptyAndNulPaths()
        concurrentWritersOfOnePathAllSucceedAndLeaveOneWholePayload()
        moveReplacesTheTargetWithTheSourceContent()
        moveWithFailNeverReplaces()
        moveWithFailOfADirectoryOrALinkIsUnsupported()
        moveOfAMissingSourceIsNotFound()
        moveOverADirectoryIsADirectory()
        moveCreatesParentDirectoriesWhenAsked()
        moveWithAMissingParentIsNotFound()
        moveMovesASymbolicLinkAsALink()
        moveMovesADirectoryWithinOneVolume()
        deleteRemovesAFileALinkAndAnEmptyDirectory()
        deleteOfASymbolicLinkLeavesItsTarget()
        deleteOfAnAbsentPathIsSuccess()
        deleteOfANonEmptyDirectoryIsDirectoryNotEmpty()
        deleteRecursivelyRemovesAFullTree()
        deleteRecursivelyOfAnAbsentPathIsSuccess()
        deleteRecursivelyDoesNotFollowSymbolicLinks()
        deleteRecursivelyOfALinkToADirectoryRemovesOnlyTheLink()
        aTrailingSeparatorNamesTheEntryItself()
        deleteRecursivelyByConcurrentDeletersAllSucceed()
        deleteRecursivelyStopsWhenCancelled()
        treeSizeSumsTheRegularFiles()
        treeSizeOfAnAbsentPathIsZero()
        treeSizeCountsALinkAsZero()
        treeSizeStopsWhenCancelled()
        sweepKeepsEntriesNewerThanTheAge()
        sweepNeverDeletesWhatDoesNotMatchOrADirectory()
        sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest()
        sweepWithZeroAgeDeletesAnOldEntry()
        sweepDeletesAnOldSymbolicLink()
        sweepStopsWhenCancelled()
        sweepOfAnAbsentDirectoryIsZero()
        sweepRejectsANegativeAge()
        sweepPropagatesExceptionsOfTheMatcher()
        freeSpaceOfAMissingPathFollowsItsAncestor()
        existsFollowsSymbolicLinks()
        withIoCancelledThrowsCancellationException()
        withIoReturnsTheResultOfTheBlock()
    }

    // ---- metadata ---------------------------------------------------------------------------------

    /** `metadata` reports a file with its size, a directory, and `null` for an absent path. */
    public fun metadataReportsKinds(): Unit = scenario("metadataReportsKinds") {
        write("f", "hello")
        mkdir("d")
        val file: FileMetadata = files.metadata(p("f")) ?: violation("a file has metadata")
        expect(file.kind == FileKind.RegularFile, "a file reports RegularFile, was ${file.kind}")
        expect(file.sizeBytes == 5L, "a file reports its length, was ${file.sizeBytes}")
        val directory: FileMetadata = files.metadata(p("d")) ?: violation("a directory has metadata")
        expect(directory.kind == FileKind.Directory, "a directory reports Directory, was ${directory.kind}")
        expect(directory.sizeBytes == 0L, "a directory reports size 0, was ${directory.sizeBytes}")
        expect(files.metadata(p("absent")) == null, "an absent path has no metadata")
    }

    /** A symbolic link reports [FileKind.SymbolicLink] by default and is never followed; its own size is 0, not its target's. */
    public fun metadataDoesNotFollowLinksByDefault(): Unit = scenario("metadataDoesNotFollowLinksByDefault") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "t")
        link(p("link"), p("target"))
        expect(files.metadata(p("link"))?.kind == FileKind.SymbolicLink, "the default does not follow a link")
        expect(files.metadata(p("link"), followLinks = false)?.kind == FileKind.SymbolicLink, "followLinks = false does not follow")
        expect(files.metadata(p("link"))?.sizeBytes == 0L, "a link reports size 0, not its target's, was ${files.metadata(p("link"))?.sizeBytes}")
    }

    /** With `followLinks = true` a link reports its target. */
    public fun metadataFollowLinksReportsTheTarget(): Unit = scenario("metadataFollowLinksReportsTheTarget") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "four")
        mkdir("dir")
        link(p("l1"), p("target"))
        link(p("l2"), p("dir"))
        val toFile: FileMetadata = files.metadata(p("l1"), followLinks = true) ?: violation("a followed link has metadata")
        expect(toFile.kind == FileKind.RegularFile && toFile.sizeBytes == 4L, "a followed link reports its file target, was $toFile")
        expect(files.metadata(p("l2"), followLinks = true)?.kind == FileKind.Directory, "a followed link reports its directory target")
    }

    /** A dangling link is `null` when followed and still a link (of size 0) otherwise. */
    public fun metadataOfADanglingLinkIsNullWhenFollowing(): Unit = scenario("metadataOfADanglingLinkIsNullWhenFollowing") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        link(p("dangling"), p("nothing-here"))
        expect(files.metadata(p("dangling"), followLinks = true) == null, "a dangling link followed has no metadata")
        expect(files.metadata(p("dangling"))?.kind == FileKind.SymbolicLink, "a dangling link itself is a SymbolicLink")
        expect(files.metadata(p("dangling"))?.sizeBytes == 0L, "a dangling link reports size 0, was ${files.metadata(p("dangling"))?.sizeBytes}")
        expect(!files.exists(p("dangling")), "exists follows links, so a dangling link does not exist")
    }

    /**
     * `readBytes` follows symbolic links: a link to a file reads the file's bytes, a link to a directory is
     * [FileError.IsADirectory], a dangling link is [FileError.NotFound].
     *
     * @since 5.0.0
     */
    public fun readBytesFollowsSymbolicLinks(): Unit = scenario("readBytesFollowsSymbolicLinks") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "payload")
        mkdir("dir")
        link(p("to-file"), p("target"))
        link(p("to-dir"), p("dir"))
        link(p("dangling"), p("nothing-here"))
        expect(text("to-file") == "payload", "a link to a file reads the file's bytes, was ${text("to-file")}")
        failure<FileError.IsADirectory>(files.readBytes(p("to-dir"), 100L), "readBytes of a link to a directory")
        failure<FileError.NotFound>(files.readBytes(p("dangling"), 100L), "readBytes of a dangling link")
    }

    // ---- list and createDirectories ---------------------------------------------------------------

    /** `list` returns the names sorted by `String.compareTo`, without `.` and `..`. */
    public fun listIsSortedWithoutDotEntries(): Unit = scenario("listIsSortedWithoutDotEntries") {
        for (name: String in listOf("b", "a", "C", "ab", "D")) write(name, name)
        mkdir("sub")
        val names: List<String> = ok(files.list(root), "list")
        expect(names == names.sorted(), "names must be sorted by String.compareTo, were $names")
        expect(names.toSet() == setOf("a", "ab", "b", "C", "D", "sub"), "every entry and no more, was $names")
        expect("." !in names && ".." !in names, "no dot entries")
    }

    /** An absent directory lists as empty. */
    public fun listOfAnAbsentDirectoryIsEmpty(): Unit = scenario("listOfAnAbsentDirectoryIsEmpty") {
        expect(ok(files.list(p("absent")), "list of an absent directory").isEmpty(), "an absent directory lists as empty")
    }

    /** Listing a file is [FileError.NotADirectory]. */
    public fun listOfAFileIsNotADirectory(): Unit = scenario("listOfAFileIsNotADirectory") {
        write("f", "x")
        failure<FileError.NotADirectory>(files.list(p("f")), "list of a file")
    }

    /** `createDirectories` creates every missing parent, is idempotent, and leaves a directory. */
    public fun createDirectoriesCreatesParentsAndIsIdempotent(): Unit = scenario("createDirectoriesCreatesParentsAndIsIdempotent") {
        val deep: String = p("a", "b", "c")
        ok(files.createDirectories(deep), "createDirectories")
        ok(files.createDirectories(deep), "createDirectories again")
        ok(files.createDirectories(p("a")), "createDirectories of an existing parent")
        expect(files.metadata(deep)?.kind == FileKind.Directory, "the deepest level is a directory")
        expect(files.metadata(p("a"))?.kind == FileKind.Directory, "the first level is a directory")
    }

    /** A file at the path or in the way is [FileError.NotADirectory]. */
    public fun createDirectoriesOverAFileIsNotADirectory(): Unit = scenario("createDirectoriesOverAFileIsNotADirectory") {
        write("f", "x")
        failure<FileError.NotADirectory>(files.createDirectories(p("f")), "createDirectories over a file")
        failure<FileError.NotADirectory>(files.createDirectories(p("f", "x")), "createDirectories below a file")
        expect(text("f") == "x", "the file is untouched")
    }

    /** Concurrent creators of one directory all succeed. */
    public fun createDirectoriesByConcurrentCreatorsAllSucceed(): Unit = scenario("createDirectoriesByConcurrentCreatorsAllSucceed") {
        val deep: String = p("x", "y", "z")
        val problems: Array<String?> = arrayOfNulls(WORKERS)
        runConcurrently(WORKERS) { index ->
            val result: FileResult<Unit> = files.createDirectories(deep)
            if (!result.isSuccess) problems[index] = "worker $index got $result"
        }
        expect(problems.all { it == null }, "every concurrent creator must succeed: ${problems.filterNotNull()}")
        expect(files.metadata(deep)?.kind == FileKind.Directory, "the directory exists afterwards")
    }

    /**
     * `createDirectories(durable = true)` is observably the same as without it: it creates every missing parent, is
     * idempotent, a file at the path or in the way is [FileError.NotADirectory], and concurrent creators all succeed.
     * Durability itself is not observable here.
     */
    public fun createDirectoriesDurableGivesTheSameResults(): Unit = scenario("createDirectoriesDurableGivesTheSameResults") {
        val deep: String = p("da", "db", "dc")
        ok(files.createDirectories(deep, durable = true), "durable createDirectories")
        ok(files.createDirectories(deep, durable = true), "durable createDirectories again")
        ok(files.createDirectories(p("da"), durable = true), "durable createDirectories of an existing parent")
        expect(files.metadata(deep)?.kind == FileKind.Directory, "the deepest level is a directory")
        expect(files.metadata(p("da"))?.kind == FileKind.Directory, "the first level is a directory")
        write("df", "x")
        failure<FileError.NotADirectory>(files.createDirectories(p("df"), durable = true), "durable createDirectories over a file")
        failure<FileError.NotADirectory>(files.createDirectories(p("df", "x"), durable = true), "durable createDirectories below a file")
        expect(text("df") == "x", "the file is untouched")
        val shared: String = p("dx", "dy", "dz")
        val problems: Array<String?> = arrayOfNulls(WORKERS)
        runConcurrently(WORKERS) { index ->
            val result: FileResult<Unit> = files.createDirectories(shared, durable = true)
            if (!result.isSuccess) problems[index] = "worker $index got $result"
        }
        expect(problems.all { it == null }, "every concurrent durable creator must succeed: ${problems.filterNotNull()}")
        expect(files.metadata(shared)?.kind == FileKind.Directory, "the directory exists afterwards")
    }

    // ---- readBytes --------------------------------------------------------------------------------

    /** An absent file is [FileError.NotFound], naming the path. */
    public fun readBytesOfAnAbsentFileIsNotFound(): Unit = scenario("readBytesOfAnAbsentFileIsNotFound") {
        val error: FileError.NotFound = failure(files.readBytes(p("absent"), 10), "readBytes of an absent file")
        expect(error.path == p("absent"), "the error names the path, was ${error.path}")
    }

    /** A directory is [FileError.IsADirectory]. */
    public fun readBytesOfADirectoryIsADirectory(): Unit = scenario("readBytesOfADirectoryIsADirectory") {
        mkdir("d")
        failure<FileError.IsADirectory>(files.readBytes(p("d"), 10), "readBytes of a directory")
    }

    /** A file larger than `maxBytes` is [FileError.TooLarge] and is not returned in part; exactly `maxBytes` is read. */
    public fun readBytesLargerThanMaxIsTooLargeAndExactMaxIsRead(): Unit = scenario("readBytesLargerThanMaxIsTooLargeAndExactMaxIsRead") {
        write("f", "0123456789")
        val tooLarge: FileError.TooLarge = failure(files.readBytes(p("f"), 9), "readBytes with max = size - 1")
        expect(tooLarge.maxBytes == 9L && tooLarge.sizeBytes >= 10L, "TooLarge carries the limit and the size, was $tooLarge")
        expect(ok(files.readBytes(p("f"), 10), "readBytes with max = size").decodeToString() == "0123456789", "exact max reads everything")
        expect(ok(files.readBytes(p("f"), 1000), "readBytes with a large max").size == 10, "a large max reads everything")
        write("empty", "")
        expect(ok(files.readBytes(p("empty"), 0), "readBytes of an empty file with max 0").isEmpty(), "an empty file with max 0 reads empty")
    }

    /** A negative `maxBytes` or one above `MAX_READ_BYTES` throws [IllegalArgumentException]. */
    public fun readBytesRejectsAnInvalidMax(): Unit = scenario("readBytesRejectsAnInvalidMax") {
        write("f", "x")
        expectIllegalArgument("a negative maxBytes") { files.readBytes(p("f"), -1) }
        expectIllegalArgument("a maxBytes above MAX_READ_BYTES") { files.readBytes(p("f"), FileAccess.MAX_READ_BYTES + 1) }
        expect(files.readBytes(p("f"), FileAccess.MAX_READ_BYTES).isSuccess, "MAX_READ_BYTES itself is accepted")
    }

    // ---- writeAtomically --------------------------------------------------------------------------

    /** The new content replaces the old one whole. */
    public fun writeAtomicallyReplacesTheWholeContent(): Unit = scenario("writeAtomicallyReplacesTheWholeContent") {
        write("f", "a long previous content")
        ok(files.writeAtomically(p("f"), "new".encodeToByteArray()), "writeAtomically")
        expect(text("f") == "new", "the file holds exactly the new bytes, was '${text("f")}'")
        expect(names(root) == listOf("f"), "nothing else is left in the directory, was ${names(root)}")
    }

    /** An empty array is valid and creates an empty file. */
    public fun writeAtomicallyAcceptsEmptyContent(): Unit = scenario("writeAtomicallyAcceptsEmptyContent") {
        ok(files.writeAtomically(p("f"), ByteArray(0)), "writeAtomically of nothing")
        expect(files.metadata(p("f"))?.sizeBytes == 0L, "an empty file exists")
        write("g", "content")
        ok(files.writeAtomically(p("g"), ByteArray(0)), "writeAtomically of nothing over content")
        expect(text("g") == "", "the old content is gone")
    }

    /** A missing parent is [FileError.NotFound] unless parents are created. */
    public fun writeAtomicallyWithAMissingParentIsNotFound(): Unit = scenario("writeAtomicallyWithAMissingParentIsNotFound") {
        failure<FileError.NotFound>(files.writeAtomically(p("nope", "f"), ByteArray(1)), "writeAtomically with a missing parent")
        expect(files.metadata(p("nope")) == null, "nothing was created")
        expect(names(root).isEmpty(), "no temporary file is left, was ${names(root)}")
    }

    /** `createParentDirectories` creates the parent first. */
    public fun writeAtomicallyCreatesParentDirectoriesWhenAsked(): Unit = scenario("writeAtomicallyCreatesParentDirectoriesWhenAsked") {
        ok(files.writeAtomically(p("a", "b", "f"), "v".encodeToByteArray(), WriteOptions(createParentDirectories = true)), "writeAtomically")
        expect(text("a", "b", "f") == "v", "the file is there")
        expect(names(p("a", "b")) == listOf("f"), "and nothing else beside it")
    }

    /** A directory at the path is [FileError.IsADirectory] and stays. */
    public fun writeAtomicallyOverADirectoryIsADirectory(): Unit = scenario("writeAtomicallyOverADirectoryIsADirectory") {
        mkdir("d")
        write("d/inner", "x")
        failure<FileError.IsADirectory>(files.writeAtomically(p("d"), ByteArray(1)), "writeAtomically over a directory")
        expect(files.metadata(p("d"))?.kind == FileKind.Directory, "the directory is untouched")
        expect(names(root) == listOf("d"), "no temporary file is left, was ${names(root)}")
    }

    /**
     * [ExistingFile.Fail] over a dangling symbolic link is [FileError.AlreadyExists]: the link stays a link, its target
     * stays absent and no temporary file is left (needs a link maker).
     *
     * @since 5.0.0
     */
    public fun writeAtomicallyWithFailRefusesADanglingLink(): Unit = scenario("writeAtomicallyWithFailRefusesADanglingLink") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        link(p("dangling"), p("nothing-here"))
        failure<FileError.AlreadyExists>(
            files.writeAtomically(p("dangling"), "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)),
            "writeAtomically with Fail over a dangling link",
        )
        expect(files.metadata(p("dangling"))?.kind == FileKind.SymbolicLink, "the path is still a SymbolicLink")
        expect(files.metadata(p("nothing-here")) == null, "the link's target is still absent")
        expect(names(root) == listOf("dangling"), "no temporary file is left, was ${names(root)}")
    }

    /** [ExistingFile.Fail] refuses an existing target and leaves it and the directory unchanged; on an absent one it writes. */
    public fun writeAtomicallyWithFailRefusesAnExistingTarget(): Unit = scenario("writeAtomicallyWithFailRefusesAnExistingTarget") {
        write("f", "old")
        failure<FileError.AlreadyExists>(
            files.writeAtomically(p("f"), "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)),
            "writeAtomically with Fail over an existing file",
        )
        expect(text("f") == "old", "the target keeps its bytes")
        expect(names(root) == listOf("f"), "no temporary file is left, was ${names(root)}")
        ok(files.writeAtomically(p("g"), "fresh".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)), "writeAtomically with Fail on an absent target")
        expect(text("g") == "fresh", "the new file holds the bytes")
        expect(names(root) == listOf("f", "g"), "still no temporary file, was ${names(root)}")
    }

    /** After success no temporary file remains, and temporary names are recognised by [FileNames.isTempName] if any is seen. */
    public fun writeAtomicallyLeavesNoTemporaryFile(): Unit = scenario("writeAtomicallyLeavesNoTemporaryFile") {
        repeat(25) { ok(files.writeAtomically(p("f"), "v$it".encodeToByteArray()), "writeAtomically $it") }
        expect(names(root) == listOf("f"), "only the target is left after 25 writes, was ${names(root)}")
        expect(names(root).none { FileNames.isTempName(it) }, "no temporary name is left")
    }

    /** A symbolic link at the path is replaced by a regular file; its target is untouched. */
    public fun writeAtomicallyReplacesASymbolicLinkNotItsTarget(): Unit = scenario("writeAtomicallyReplacesASymbolicLinkNotItsTarget") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "old")
        link(p("link"), p("target"))
        ok(files.writeAtomically(p("link"), "new".encodeToByteArray()), "writeAtomically over a link")
        expect(files.metadata(p("link"))?.kind == FileKind.RegularFile, "the link is now a regular file")
        expect(text("link") == "new", "the path holds the new bytes")
        expect(text("target") == "old", "the link's target is untouched")
    }

    /** The temporary file goes to `tempDirectory`, which is left clean. */
    public fun writeAtomicallyHonoursTheTempDirectoryAndLeavesItClean(): Unit = scenario("writeAtomicallyHonoursTheTempDirectoryAndLeavesItClean") {
        mkdir("tmp")
        ok(
            files.writeAtomically(p("out", "f"), "v".encodeToByteArray(), WriteOptions(createParentDirectories = true, tempDirectory = p("tmp"))),
            "writeAtomically through a temp directory",
        )
        expect(text("out", "f") == "v", "the target holds the bytes")
        expect(names(p("tmp")).isEmpty(), "the temp directory is clean, was ${names(p("tmp"))}")
        expect(names(p("out")) == listOf("f"), "the target directory holds only the target, was ${names(p("out"))}")
        ok(files.writeAtomically(p("g"), "w".encodeToByteArray(), WriteOptions(tempDirectory = p("tmp-created"), createParentDirectories = true)), "writeAtomically creating its temp directory")
        expect(names(p("tmp-created")).isEmpty(), "a created temp directory is clean too")
    }

    /** A custom temp prefix works and leaves nothing behind. */
    public fun writeAtomicallyWithACustomTempPrefixLeavesNothing(): Unit = scenario("writeAtomicallyWithACustomTempPrefixLeavesNothing") {
        ok(files.writeAtomically(p("f"), "v".encodeToByteArray(), WriteOptions(tempPrefix = "my-prefix-")), "writeAtomically with a prefix")
        expect(names(root) == listOf("f"), "nothing but the target is left, was ${names(root)}")
    }

    /** A relative, empty or NUL-containing path is [FileError.InvalidPath]; queries answer null or false. */
    public fun writeAtomicallyRejectsRelativeEmptyAndNulPaths(): Unit = scenario("writeAtomicallyRejectsRelativeEmptyAndNulPaths") {
        for (path: String in listOf("", "relative", "dir${separator}relative", "a\u0000b")) {
            val error: FileError.InvalidPath = failure(files.writeAtomically(path, ByteArray(1)), "writeAtomically of '$path'")
            expect(error.path == path, "the error names the path '$path', was '${error.path}'")
            failure<FileError.InvalidPath>(files.readBytes(path, 1), "readBytes of '$path'")
            expect(files.metadata(path) == null, "metadata of '$path' is null")
            expect(!files.exists(path), "exists of '$path' is false")
        }
    }

    /**
     * Every function that takes a path answers a relative, empty or NUL-containing one with [FileError.InvalidPath]
     * naming it (a query answers `null` or `false`) and changes nothing: `list`, `createDirectories`, `readBytes`,
     * `writeAtomically` (also its `tempDirectory`), `move` (`from` and `to`), `delete`, `deleteRecursively`,
     * `treeSize`, `sweep`, `metadata`, `freeSpaceBytes` and `exists`. A decorator that resolves relative paths
     * against a base directory fails here.
     */
    public fun everyFunctionRejectsRelativeEmptyAndNulPaths(): Unit = scenario("everyFunctionRejectsRelativeEmptyAndNulPaths") {
        write("keep", "x")
        val good: String = p("keep")
        for (bad: String in listOf("", "relative", "dir${separator}relative", "a\u0000b")) {
            fun invalid(what: String, result: FileResult<*>) {
                val error: FileError.InvalidPath = failure(result, "$what of '$bad'")
                expect(error.path == bad, "$what must name the invalid path '$bad', was '${error.path}'")
            }
            invalid("list", files.list(bad))
            invalid("createDirectories", files.createDirectories(bad))
            invalid("readBytes", files.readBytes(bad, 1))
            invalid("writeAtomically", files.writeAtomically(bad, ByteArray(1)))
            invalid("writeAtomically with an invalid tempDirectory", files.writeAtomically(p("new"), ByteArray(1), WriteOptions(tempDirectory = bad)))
            invalid("move from", files.move(bad, p("moved")))
            invalid("move to", files.move(good, bad))
            invalid("delete", files.delete(bad))
            invalid("deleteRecursively", files.deleteRecursively(bad))
            invalid("treeSize", files.treeSize(bad))
            invalid("sweep", files.sweep(bad, 0L) { true })
            expect(files.metadata(bad) == null && files.metadata(bad, followLinks = true) == null, "metadata of '$bad' is null")
            expect(files.freeSpaceBytes(bad) == null, "freeSpaceBytes of '$bad' is null")
            expect(!files.exists(bad), "exists of '$bad' is false")
        }
        expect(names(root) == listOf("keep") && text("keep") == "x", "nothing was created or changed, was ${names(root)}")
    }

    /** Concurrent writers of one path never share a temporary file: all succeed and one whole payload is left. */
    public fun concurrentWritersOfOnePathAllSucceedAndLeaveOneWholePayload(): Unit =
        scenario("concurrentWritersOfOnePathAllSucceedAndLeaveOneWholePayload") {
            val payloads: List<ByteArray> = List(WORKERS) { index -> ByteArray(4096) { (index + 1).toByte() } }
            val problems: Array<String?> = arrayOfNulls(WORKERS)
            runConcurrently(WORKERS) { index ->
                repeat(25) {
                    val result: FileResult<Unit> = files.writeAtomically(p("f"), payloads[index])
                    if (!result.isSuccess) problems[index] = "worker $index got $result"
                }
            }
            expect(problems.all { it == null }, "every concurrent writer must succeed: ${problems.filterNotNull()}")
            val content: ByteArray = ok(files.readBytes(p("f"), 1 shl 20), "readBytes")
            expect(payloads.any { it.contentEquals(content) }, "the file holds one whole payload, not a mix (size ${content.size})")
            expect(names(root) == listOf("f"), "no temporary file is left, was ${names(root)}")
        }

    // ---- move -------------------------------------------------------------------------------------

    /** `Replace` moves the content over an existing file and removes the source. */
    public fun moveReplacesTheTargetWithTheSourceContent(): Unit = scenario("moveReplacesTheTargetWithTheSourceContent") {
        write("a", "A")
        write("b", "B")
        ok(files.move(p("a"), p("b")), "move")
        expect(text("b") == "A", "the target holds the source content")
        expect(files.metadata(p("a")) == null, "the source is gone")
        write("c", "C")
        ok(files.move(p("c"), p("fresh")), "move to an absent target")
        expect(text("fresh") == "C" && files.metadata(p("c")) == null, "a move to a new name works too")
        expect(names(root) == listOf("b", "fresh"), "nothing else is left, was ${names(root)}")
    }

    /** [ExistingFile.Fail] never replaces: [FileError.AlreadyExists], both files unchanged; an absent target works. */
    public fun moveWithFailNeverReplaces(): Unit = scenario("moveWithFailNeverReplaces") {
        write("a", "A")
        write("b", "B")
        failure<FileError.AlreadyExists>(files.move(p("a"), p("b"), MoveOptions(existing = ExistingFile.Fail)), "move with Fail over a file")
        expect(text("a") == "A" && text("b") == "B", "both files keep their bytes")
        ok(files.move(p("a"), p("c"), MoveOptions(existing = ExistingFile.Fail)), "move with Fail to an absent target")
        expect(text("c") == "A" && files.metadata(p("a")) == null, "the source moved")
        expect(names(root) == listOf("b", "c"), "no temporary file is left, was ${names(root)}")
    }

    /** An absent source is [FileError.NotFound], and the error names the source. */
    public fun moveOfAMissingSourceIsNotFound(): Unit = scenario("moveOfAMissingSourceIsNotFound") {
        val error: FileError.NotFound = failure<FileError.NotFound>(files.move(p("absent"), p("target")), "move of an absent source")
        expect(error.path == p("absent"), "the error names the absent source, was ${error.path}")
        expect(files.metadata(p("target")) == null, "nothing was created")
    }

    /** A directory at the target is [FileError.IsADirectory] naming the target, and is never replaced. */
    public fun moveOverADirectoryIsADirectory(): Unit = scenario("moveOverADirectoryIsADirectory") {
        write("a", "A")
        mkdir("d")
        val error: FileError.IsADirectory = failure<FileError.IsADirectory>(files.move(p("a"), p("d")), "move over a directory")
        expect(error.path == p("d"), "the error names the target directory, was ${error.path}")
        expect(text("a") == "A" && files.metadata(p("d"))?.kind == FileKind.Directory, "both are untouched")
    }

    /** `createParentDirectories` creates the parent of the target. */
    public fun moveCreatesParentDirectoriesWhenAsked(): Unit = scenario("moveCreatesParentDirectoriesWhenAsked") {
        write("a", "A")
        ok(files.move(p("a"), p("x", "y", "f"), MoveOptions(createParentDirectories = true)), "move creating parents")
        expect(text("x", "y", "f") == "A" && files.metadata(p("a")) == null, "the file moved into the new directories")
    }

    /** Without it a missing parent is [FileError.NotFound] naming the target and the source stays. */
    public fun moveWithAMissingParentIsNotFound(): Unit = scenario("moveWithAMissingParentIsNotFound") {
        write("a", "A")
        val error: FileError.NotFound = failure<FileError.NotFound>(files.move(p("a"), p("nope", "f")), "move into a missing directory")
        expect(error.path == p("nope", "f"), "the error names the target while the source exists, was ${error.path}")
        expect(text("a") == "A", "the source is untouched")
        expect(files.metadata(p("nope")) == null, "no directory was created")
    }

    /**
     * [ExistingFile.Fail] of a directory or a symbolic link is [FileError.Unsupported] naming the source, and changes
     * nothing (the link check needs a link maker).
     *
     * @since 5.0.0
     */
    public fun moveWithFailOfADirectoryOrALinkIsUnsupported(): Unit = scenario("moveWithFailOfADirectoryOrALinkIsUnsupported") {
        val fail: MoveOptions = MoveOptions(existing = ExistingFile.Fail)
        mkdir("d")
        write("d${separator}inner", "x")
        val directoryError: FileError.Unsupported = failure<FileError.Unsupported>(files.move(p("d"), p("e"), fail), "move with Fail of a directory")
        expect(directoryError.path == p("d"), "the error names the directory, was ${directoryError.path}")
        expect(files.metadata(p("d"))?.kind == FileKind.Directory && text("d", "inner") == "x", "the directory and its content stay")
        expect(files.metadata(p("e")) == null, "the target name stays absent")
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "t")
        link(p("link"), p("target"))
        val linkError: FileError.Unsupported = failure<FileError.Unsupported>(files.move(p("link"), p("moved"), fail), "move with Fail of a link")
        expect(linkError.path == p("link"), "the error names the link, was ${linkError.path}")
        expect(files.metadata(p("link"))?.kind == FileKind.SymbolicLink, "the link is still a link")
        expect(files.metadata(p("moved")) == null, "the target name stays absent")
        expect(text("target") == "t", "the link's target is unchanged")
    }

    /** Links are moved as links: the link moves and its target stays. */
    public fun moveMovesASymbolicLinkAsALink(): Unit = scenario("moveMovesASymbolicLinkAsALink") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("target", "t")
        link(p("link"), p("target"))
        ok(files.move(p("link"), p("moved")), "move of a link")
        expect(files.metadata(p("moved"))?.kind == FileKind.SymbolicLink, "the moved entry is still a link")
        expect(files.metadata(p("link")) == null, "the old name is gone")
        expect(text("target") == "t", "the target is untouched")
    }

    /** A directory moves within a volume with its content. */
    public fun moveMovesADirectoryWithinOneVolume(): Unit = scenario("moveMovesADirectoryWithinOneVolume") {
        mkdir("d")
        write("d${separator}inner", "x")
        ok(files.move(p("d"), p("e")), "move of a directory")
        expect(text("e", "inner") == "x", "the content moved with it")
        expect(files.metadata(p("d")) == null, "the old name is gone")
    }

    // ---- delete -----------------------------------------------------------------------------------

    /** `delete` removes a file, a link and an empty directory. */
    public fun deleteRemovesAFileALinkAndAnEmptyDirectory(): Unit = scenario("deleteRemovesAFileALinkAndAnEmptyDirectory") {
        write("f", "x")
        mkdir("d")
        ok(files.delete(p("f")), "delete of a file")
        ok(files.delete(p("d")), "delete of an empty directory")
        expect(names(root).isEmpty(), "both are gone, was ${names(root)}")
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("t", "x")
        link(p("l"), p("t"))
        ok(files.delete(p("l")), "delete of a link")
        expect(files.metadata(p("l")) == null, "the link is gone")
    }

    /** `delete` of a link never touches its target. */
    public fun deleteOfASymbolicLinkLeavesItsTarget(): Unit = scenario("deleteOfASymbolicLinkLeavesItsTarget") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        write("t", "keep")
        link(p("l"), p("t"))
        ok(files.delete(p("l")), "delete of a link")
        expect(text("t") == "keep", "the target survives")
    }

    /** An absent path is Success. */
    public fun deleteOfAnAbsentPathIsSuccess(): Unit = scenario("deleteOfAnAbsentPathIsSuccess") {
        ok(files.delete(p("absent")), "delete of an absent path")
    }

    /** A non-empty directory is [FileError.DirectoryNotEmpty] and keeps its content. */
    public fun deleteOfANonEmptyDirectoryIsDirectoryNotEmpty(): Unit = scenario("deleteOfANonEmptyDirectoryIsDirectoryNotEmpty") {
        mkdir("d")
        write("d${separator}inner", "x")
        failure<FileError.DirectoryNotEmpty>(files.delete(p("d")), "delete of a non-empty directory")
        expect(text("d", "inner") == "x", "the content is untouched")
    }

    // ---- deleteRecursively and treeSize -----------------------------------------------------------

    private fun Scope.buildTree(base: String, fileCount: Int = 12) {
        mkdir(base)
        for (level: Int in 1..3) mkdir(listOf(base, *Array(level) { "l$it" }).joinToString(separator.toString()))
        for (index: Int in 0 until fileCount) {
            val dir: String = listOf(base, *Array(index % 4) { "l$it" }).joinToString(separator.toString())
            write("$dir${separator}f$index", "x".repeat(index + 1))
        }
    }

    /** `deleteRecursively` removes the path and everything under it. */
    public fun deleteRecursivelyRemovesAFullTree(): Unit = scenario("deleteRecursivelyRemovesAFullTree") {
        buildTree("tree")
        ok(files.deleteRecursively(p("tree")), "deleteRecursively")
        expect(files.metadata(p("tree")) == null, "nothing is left at the path")
        expect(names(root).isEmpty(), "and nothing else was touched or left, was ${names(root)}")
    }

    /** An absent path is Success. */
    public fun deleteRecursivelyOfAnAbsentPathIsSuccess(): Unit = scenario("deleteRecursivelyOfAnAbsentPathIsSuccess") {
        ok(files.deleteRecursively(p("absent")), "deleteRecursively of an absent path")
    }

    /** Symbolic links inside the tree are removed, never followed: what they point at stays. */
    public fun deleteRecursivelyDoesNotFollowSymbolicLinks(): Unit = scenario("deleteRecursivelyDoesNotFollowSymbolicLinks") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        mkdir("outside")
        write("outside${separator}keep", "precious")
        mkdir("tree")
        write("tree${separator}own", "x")
        link(p("tree", "to-dir"), p("outside"))
        link(p("tree", "to-file"), p("outside", "keep"))
        ok(files.deleteRecursively(p("tree")), "deleteRecursively")
        expect(files.metadata(p("tree")) == null, "the tree is gone")
        expect(text("outside", "keep") == "precious", "what the links pointed at is untouched")
    }

    /**
     * `deleteRecursively` of a symbolic link to a directory removes only the link: the directory it points at keeps
     * its content (needs a link maker).
     *
     * @since 5.0.0
     */
    public fun deleteRecursivelyOfALinkToADirectoryRemovesOnlyTheLink(): Unit =
        scenario("deleteRecursivelyOfALinkToADirectoryRemovesOnlyTheLink") {
            val link: (String, String) -> Unit = linkMaker() ?: return@scenario
            mkdir("outside")
            write("outside${separator}keep", "precious")
            link(p("link"), p("outside"))
            ok(files.deleteRecursively(p("link")), "deleteRecursively of a link to a directory")
            expect(files.metadata(p("link")) == null, "the link is gone")
            expect(text("outside", "keep") == "precious", "what the link pointed at is untouched, was ${text("outside", "keep")}")
        }

    /**
     * A trailing separator names the entry itself, on every platform: it neither requires a directory nor makes a
     * symbolic link be followed (`readBytes("f/")` reads `f`, `delete("link/")` removes only the link), and an error
     * names the path without it. The link part runs only when the fixture can create symbolic links.
     *
     * @since 5.0.0
     */
    public fun aTrailingSeparatorNamesTheEntryItself(): Unit = scenario("aTrailingSeparatorNamesTheEntryItself") {
        val sep: String = separator.toString()
        write("f", "x")
        expect(text("f$sep") == "x", "readBytes of a file with a trailing separator reads the file, was ${text("f$sep")}")
        expect(files.metadata(p("f") + sep)?.kind == FileKind.RegularFile, "metadata of a file with a trailing separator is the file")
        ok(files.writeAtomically(p("n") + sep, "y".encodeToByteArray()), "writeAtomically with a trailing separator")
        expect(files.metadata(p("n"))?.kind == FileKind.RegularFile, "the written entry is a regular file")
        ok(files.move(p("n") + sep, p("m") + sep), "move with trailing separators")
        expect(text("m") == "y", "the moved file holds its content, was ${text("m")}")
        expect(files.metadata(p("n")) == null, "the old name is gone")
        ok(files.delete(p("f") + sep), "delete of a file with a trailing separator")
        expect(files.metadata(p("f")) == null, "the file is gone")
        val absent: FileError.NotFound = failure(files.readBytes(p("absent") + sep, 10), "readBytes of an absent file with a trailing separator")
        expect(absent.path == p("absent"), "the error names the path without the separator, was ${absent.path}")

        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        mkdir("d")
        write("d/in", "keep")
        link(p("l"), p("d"))
        expect(files.metadata(p("l") + sep)?.kind == FileKind.SymbolicLink, "metadata of a link with a trailing separator is the link")
        expect(files.treeSize(p("l") + sep).getOrNull() == 0L, "treeSize of a link with a trailing separator is 0")
        ok(files.deleteRecursively(p("l") + sep), "deleteRecursively of a link with a trailing separator")
        expect(files.metadata(p("l")) == null, "the link is gone")
        expect(text("d", "in") == "keep", "what the link pointed at is untouched, was ${text("d", "in")}")
        link(p("l"), p("d"))
        ok(files.delete(p("l") + sep), "delete of a link with a trailing separator")
        expect(files.metadata(p("l")) == null, "the link is gone after delete")
        expect(text("d", "in") == "keep", "what the link pointed at is untouched after delete, was ${text("d", "in")}")
    }

    /** Concurrent deletes of one tree all succeed. */
    public fun deleteRecursivelyByConcurrentDeletersAllSucceed(): Unit = scenario("deleteRecursivelyByConcurrentDeletersAllSucceed") {
        buildTree("tree", fileCount = 40)
        val problems: Array<String?> = arrayOfNulls(WORKERS)
        runConcurrently(WORKERS) { index ->
            val result: FileResult<Unit> = files.deleteRecursively(p("tree"))
            if (!result.isSuccess) problems[index] = "worker $index got $result"
        }
        expect(problems.all { it == null }, "every concurrent deleter must succeed: ${problems.filterNotNull()}")
        expect(files.metadata(p("tree")) == null, "the tree is gone")
    }

    /** A cancelled check stops the walk with [FileError.Cancelled] before it removes anything. */
    public fun deleteRecursivelyStopsWhenCancelled(): Unit = scenario("deleteRecursivelyStopsWhenCancelled") {
        buildTree("tree")
        failure<FileError.Cancelled>(files.deleteRecursively(p("tree"), CancellationCheck { true }), "deleteRecursively when cancelled")
        expect(files.metadata(p("tree")) != null, "the tree is still there")
        ok(files.deleteRecursively(p("tree")), "deleteRecursively again")
        expect(files.metadata(p("tree")) == null, "and now it goes")
    }

    /** `treeSize` sums the regular files at or under the path. */
    public fun treeSizeSumsTheRegularFiles(): Unit = scenario("treeSizeSumsTheRegularFiles") {
        mkdir("tree")
        mkdir("tree${separator}sub")
        write("tree${separator}a", "123")
        write("tree${separator}sub${separator}b", "12345")
        expect(ok(files.treeSize(p("tree")), "treeSize") == 8L, "3 + 5 bytes")
        expect(ok(files.treeSize(p("tree", "a")), "treeSize of a file") == 3L, "a file's own size")
    }

    /** An absent path is `Success(0)`. */
    public fun treeSizeOfAnAbsentPathIsZero(): Unit = scenario("treeSizeOfAnAbsentPathIsZero") {
        expect(ok(files.treeSize(p("absent")), "treeSize of an absent path") == 0L, "an absent path is 0")
    }

    /** A link counts 0 and is not followed. */
    public fun treeSizeCountsALinkAsZero(): Unit = scenario("treeSizeCountsALinkAsZero") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        mkdir("outside")
        write("outside${separator}big", "x".repeat(100))
        mkdir("tree")
        write("tree${separator}a", "123")
        link(p("tree", "to-dir"), p("outside"))
        link(p("tree", "to-file"), p("outside", "big"))
        expect(ok(files.treeSize(p("tree")), "treeSize with links") == 3L, "only the real file counts")
    }

    /** A cancelled check ends the walk with [FileError.Cancelled]. */
    public fun treeSizeStopsWhenCancelled(): Unit = scenario("treeSizeStopsWhenCancelled") {
        buildTree("tree")
        failure<FileError.Cancelled>(files.treeSize(p("tree"), CancellationCheck { true }), "treeSize when cancelled")
    }

    // ---- sweep ------------------------------------------------------------------------------------

    /** Entries newer than the age are kept. */
    public fun sweepKeepsEntriesNewerThanTheAge(): Unit = scenario("sweepKeepsEntriesNewerThanTheAge") {
        write("f", "x")
        write("g", "y")
        expect(ok(files.sweep(root, 3_600_000L) { true }, "sweep") == 0, "nothing an hour old yet")
        expect(names(root) == listOf("f", "g"), "both are still there, was ${names(root)}")
    }

    /** Entries the matcher refuses are never deleted, and a directory never is, whatever its age; the sweep does not recurse. */
    public fun sweepNeverDeletesWhatDoesNotMatchOrADirectory(): Unit = scenario("sweepNeverDeletesWhatDoesNotMatchOrADirectory") {
        write("keep", "x")
        mkdir("sub")
        write("sub${separator}inner", "y")
        expect(ok(files.sweep(root, 0L) { false }, "sweep with a matcher that refuses") == 0, "nothing matched")
        expect(names(root) == listOf("keep", "sub"), "nothing was deleted, was ${names(root)}")
        ok(files.sweep(root, 0L) { true }, "sweep matching everything")
        expect(files.metadata(p("sub"))?.kind == FileKind.Directory, "a directory is never deleted")
        expect(text("sub", "inner") == "y", "the sweep does not recurse")
    }

    /**
     * With an age of one hour an entry dated 10 minutes beyond it is deleted and counted; one dated 10 minutes inside
     * it, one dated in the future, one whose name the matcher refuses and a directory are kept, whatever their date.
     * Skipped when the fixture has no `setLastModified`.
     */
    public fun sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest(): Unit = scenario("sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest") {
        val set: (String, Long) -> Boolean = fixture.setLastModified ?: return@scenario
        val hour: Long = 3_600_000L
        val ten: Long = 600_000L
        val now: Long = fixture.nowMillis()
        write("old", "x")
        write("young", "x")
        write("future", "x")
        write("refused", "x")
        mkdir("dir")
        setTime(set, p("old"), now - hour - ten)
        setTime(set, p("young"), now - hour + ten)
        setTime(set, p("future"), now + hour)
        setTime(set, p("refused"), now - hour - ten)
        setTime(set, p("dir"), now - hour - ten)
        val deleted: Int = ok(files.sweep(root, hour) { it != "refused" }, "sweep")
        expect(deleted == 1, "exactly the old matching file is counted, was $deleted")
        expect(names(root) == listOf("dir", "future", "refused", "young"), "only the old file went, was ${names(root)}")
    }

    /** With an age of 0 an entry dated 10 minutes ago is deleted; a refused name and a directory of that date are kept. Skipped without `setLastModified`. */
    public fun sweepWithZeroAgeDeletesAnOldEntry(): Unit = scenario("sweepWithZeroAgeDeletesAnOldEntry") {
        val set: (String, Long) -> Boolean = fixture.setLastModified ?: return@scenario
        val ago: Long = fixture.nowMillis() - 600_000L
        write("old", "x")
        write("refused", "x")
        mkdir("dir")
        setTime(set, p("old"), ago)
        setTime(set, p("refused"), ago)
        setTime(set, p("dir"), ago)
        expect(ok(files.sweep(root, 0L) { it != "refused" }, "sweep") == 1, "the old matching file is deleted and counted")
        expect(names(root) == listOf("dir", "refused"), "the refused name and the directory are kept, was ${names(root)}")
    }

    /**
     * `sweep` deletes an old symbolic link (not its target), like a regular file. Needs the fixture's `createSymbolicLink`
     * and `setLastModified`; skipped without them, and skipped when dating the link evidently dated its target instead
     * (some hosts cannot set the time of a link itself).
     */
    public fun sweepDeletesAnOldSymbolicLink(): Unit = scenario("sweepDeletesAnOldSymbolicLink") {
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        val set: (String, Long) -> Boolean = fixture.setLastModified ?: return@scenario
        val old: Long = fixture.nowMillis() - 7_200_000L
        write("target", "t")
        link(p("link"), p("target"))
        setTime(set, p("link"), old)
        val dated: Long? = files.metadata(p("link"))?.lastModifiedMillis
        if (dated == null || dated > old + 60_000L) return@scenario
        expect(ok(files.sweep(root, 3_600_000L) { it == "link" }, "sweep") == 1, "the old link is deleted and counted")
        expect(files.metadata(p("link")) == null, "the link is gone")
        expect(text("target") == "t", "its target is untouched")
    }

    /** `sweep` checks its [CancellationCheck] before every entry: a cancelled check ends with [FileError.Cancelled] and deletes nothing. */
    public fun sweepStopsWhenCancelled(): Unit = scenario("sweepStopsWhenCancelled") {
        write("a", "x")
        write("b", "x")
        failure<FileError.Cancelled>(files.sweep(root, 0L, CancellationCheck { true }) { true }, "sweep when cancelled")
        expect(names(root) == listOf("a", "b"), "nothing was deleted, was ${names(root)}")
    }

    /** An absent directory is `Success(0)`. */
    public fun sweepOfAnAbsentDirectoryIsZero(): Unit = scenario("sweepOfAnAbsentDirectoryIsZero") {
        expect(ok(files.sweep(p("absent"), 0L) { true }, "sweep of an absent directory") == 0, "an absent directory sweeps 0")
    }

    /** A negative age throws [IllegalArgumentException]. */
    public fun sweepRejectsANegativeAge(): Unit = scenario("sweepRejectsANegativeAge") {
        expectIllegalArgument("a negative olderThanMillis") { files.sweep(root, -1L) { true } }
    }

    /** An exception thrown by the matcher propagates unchanged. */
    public fun sweepPropagatesExceptionsOfTheMatcher(): Unit = scenario("sweepPropagatesExceptionsOfTheMatcher") {
        write("f", "x")
        val marker: IllegalStateException = IllegalStateException("from the matcher")
        val thrown: Throwable? = try {
            files.sweep(root, 0L) { throw marker }
            null
        } catch (t: Throwable) {
            t
        }
        expect(thrown === marker, "the matcher's exception must propagate unchanged, was $thrown")
    }

    // ---- free space, exists, withIo ---------------------------------------------------------------

    /** The free space of a missing nested path is measured at its nearest existing ancestor. */
    public fun freeSpaceOfAMissingPathFollowsItsAncestor(): Unit = scenario("freeSpaceOfAMissingPathFollowsItsAncestor") {
        val ancestor: Long? = files.freeSpaceBytes(root)
        val nested: Long? = files.freeSpaceBytes(p("missing", "deeper", "still"))
        expect((ancestor == null) == (nested == null), "a missing path answers like its ancestor: $nested vs $ancestor")
        expect(ancestor == null || ancestor >= 0L, "free space is never negative, was $ancestor")
        expect(nested == null || nested >= 0L, "free space is never negative, was $nested")
        expect(files.metadata(p("missing")) == null, "asking created nothing")
    }

    /** `exists` follows symbolic links. */
    public fun existsFollowsSymbolicLinks(): Unit = scenario("existsFollowsSymbolicLinks") {
        write("f", "x")
        expect(files.exists(p("f")), "a file exists")
        expect(!files.exists(p("absent")), "an absent path does not")
        val link: (String, String) -> Unit = linkMaker() ?: return@scenario
        link(p("good"), p("f"))
        link(p("dangling"), p("nothing"))
        expect(files.exists(p("good")), "a link to a file exists")
        expect(!files.exists(p("dangling")), "a dangling link does not")
    }

    /**
     * A caller cancelled during `withIo` gets a [CancellationException], never the block's own failure. `withIo` is an
     * extension no implementation can override, so this checks the extension, not the implementation under test; it is
     * listed because consumers rely on it.
     */
    public fun withIoCancelledThrowsCancellationException(): Unit = scenario("withIoCancelledThrowsCancellationException") {
        val outcome: Throwable? = runBlockingForContract {
            val started: CompletableDeferred<Unit> = CompletableDeferred()
            val result: CompletableDeferred<Throwable?> = CompletableDeferred()
            val job: Job = launch(Dispatchers.Default) {
                try {
                    files.withIo(Dispatchers.Default) { cancellation ->
                        started.complete(Unit)
                        val mark: TimeMark = TimeSource.Monotonic.markNow()
                        // A real block would be doing I/O; this one waits for its cancellation, with a bound.
                        while (!cancellation.isCancelled() && mark.elapsedNow() < 20.seconds) {
                            // spin
                        }
                        files.deleteRecursively(p("absent"), cancellation)
                    }
                    result.complete(null)
                } catch (e: CancellationException) {
                    result.complete(e)
                    throw e
                }
            }
            withTimeout(20.seconds) { started.await() }
            job.cancel()
            withTimeout(20.seconds) { job.join() }
            withTimeout(20.seconds) { result.await() }
        }
        expect(outcome is CancellationException, "a cancelled caller must get CancellationException, was $outcome")
    }

    /**
     * `withIo` hands the block this instance and returns its result. The extension cannot be overridden, so the one
     * part that depends on the implementation is that the block's calls reach it: the write goes through the instance.
     */
    public fun withIoReturnsTheResultOfTheBlock(): Unit = scenario("withIoReturnsTheResultOfTheBlock") {
        val written: FileResult<Unit> = runBlockingForContract {
            files.withIo(Dispatchers.Default) { writeAtomically(p("f"), "io".encodeToByteArray()) }
        }
        ok(written, "writeAtomically through withIo")
        expect(text("f") == "io", "the block ran against the file system")
        val cancelledBefore: Boolean = runBlockingForContract { files.withIo(Dispatchers.Default) { it.isCancelled() } }
        expect(!cancelledBefore, "a live caller is not cancelled")
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private inline fun scenario(rule: String, body: Scope.() -> Unit) {
        val fixture: FileAccessFixture = createFixture()
        try {
            Scope(rule, fixture).body()
        } finally {
            fixture.cleanup()
        }
    }

    private class Scope(private val rule: String, val fixture: FileAccessFixture) {
        val files: FileAccess get() = fixture.files
        val root: String get() = fixture.root
        val separator: Char get() = fixture.separator

        fun violation(message: String): Nothing = throw FileAccessContractViolation("$rule: $message")

        fun expect(condition: Boolean, message: String) {
            if (!condition) violation(message)
        }

        fun setTime(set: (String, Long) -> Boolean, path: String, millis: Long) {
            if (!set(path, millis)) violation("the fixture could not set the modification time of '$path'")
        }

        fun linkMaker(): ((String, String) -> Unit)? = fixture.createSymbolicLink

        /** `root` + each name, joined with the separator. */
        fun p(vararg names: String): String = names.fold(root) { acc: String, name: String -> acc + separator + name }

        fun mkdir(relative: String) {
            ok(files.createDirectories(p(*relative.split(separator).toTypedArray())), "createDirectories('$relative')")
        }

        /** Writes [content] to [relative] (names joined by `/` or the separator) through the implementation under test. */
        fun write(relative: String, content: String) {
            val segments: Array<String> = relative.replace('/', separator).split(separator).toTypedArray()
            ok(files.writeAtomically(p(*segments), content.encodeToByteArray()), "writeAtomically('$relative')")
        }

        fun text(vararg names: String): String? = files.readBytes(p(*names), 1L shl 20).getOrNull()?.decodeToString()

        fun names(directory: String): List<String> = ok(files.list(directory), "list('$directory')")

        fun <T> ok(result: FileResult<T>, what: String): T = when (result) {
            is FileResult.Success -> result.value
            is FileResult.Failure -> violation("$what must succeed but failed with ${result.error}")
        }

        inline fun <reified E : FileError> failure(result: FileResult<*>, what: String): E = when (result) {
            is FileResult.Success -> violation("$what must fail with ${E::class.simpleName} but succeeded")
            is FileResult.Failure -> result.error as? E
                ?: violation("$what must fail with ${E::class.simpleName} but failed with ${result.error}")
        }

        fun expectIllegalArgument(what: String, call: () -> Unit) {
            val thrown: Throwable? = try {
                call()
                null
            } catch (t: Throwable) {
                t
            }
            if (thrown !is IllegalArgumentException) violation("$what must throw IllegalArgumentException, was $thrown")
        }
    }

    private companion object {
        const val WORKERS: Int = 8
    }
}
