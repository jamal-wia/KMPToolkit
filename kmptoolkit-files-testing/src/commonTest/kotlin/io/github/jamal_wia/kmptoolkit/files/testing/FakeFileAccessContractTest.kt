package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The published contract holds for the fake, on every host and for both volume kinds (spec 5.3 item 1). */
class FakeFileAccessContractTest {

    private fun fixtureOf(fake: FakeFileAccess): FileAccessFixture {
        fake.createDirectory("/root")
        return FileAccessFixture(
            files = fake,
            root = "/root",
            createSymbolicLink = { path: String, target: String -> fake.createSymbolicLink(path, target) },
            setLastModified = { path: String, millis: Long -> fake.setLastModified(path, millis); true },
            nowMillis = { fake.nowMillis },
        )
    }

    @Test
    fun `a case-sensitive fake keeps the whole FileAccess contract`() {
        FileAccessContract { fixtureOf(FakeFileAccess()) }.verifyAll()
    }

    @Test
    fun `a case-insensitive fake keeps the whole FileAccess contract`() {
        FileAccessContract { fixtureOf(FakeFileAccess(caseSensitive = false)) }.verifyAll()
    }

    @Test
    fun `a fake with a custom temp prefix keeps the contract`() {
        FileAccessContract { fixtureOf(FakeFileAccess(config = FileAccessConfig("my-app-"))) }.verifyAll()
    }

    @Test
    fun `the contract fails for a decorator that breaks it and names the rule`() {
        // A decorator that swallows the sort order of list: the contract must notice.
        val unsorted: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(
                files = object : FileAccess by fake {
                    override fun list(directory: String): FileResult<List<String>> =
                        when (val result: FileResult<List<String>> = fake.list(directory)) {
                            is FileResult.Success -> FileResult.Success(result.value.reversed())
                            is FileResult.Failure -> result
                        }
                },
                root = "/root",
            )
        }
        val violation: FileAccessContractViolation = assertFailsWith { unsorted.verifyAll() }
        assertTrue(violation.message.orEmpty().startsWith("listIsSortedWithoutDotEntries"), "was: ${violation.message}")
    }

    @Test
    fun `the contract fails when write replaces a path with a temporary file left behind`() {
        val leaky: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(
                files = object : FileAccess by fake {
                    override fun writeAtomically(path: String, bytes: ByteArray, options: io.github.jamal_wia.kmptoolkit.files.WriteOptions): FileResult<Unit> {
                        fake.createFile("/root/.kmptoolkit-1.tmp")
                        return fake.writeAtomically(path, bytes, options)
                    }
                },
                root = "/root",
            )
        }
        assertFailsWith<FileAccessContractViolation> { leaky.verifyAll() }
    }

    @Test
    fun `a failure of the implementation is reported as a violation naming the failing rule`() {
        val failing: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            fake.failNext(FakeOperation.CreateNew, FileError.NoSpace("/root"), count = 100)
            FileAccessFixture(files = fake, root = "/root")
        }
        val violation: FileAccessContractViolation = assertFailsWith { failing.verifyAll() }
        assertEquals(true, violation.message.orEmpty().contains("NoSpace"), "was: ${violation.message}")
    }

    @Test
    fun `the age checks fail when the fixture cannot set a time and are skipped without the hook`() {
        val refusing: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(files = fake, root = "/root", setLastModified = { _: String, _: Long -> false }, nowMillis = { fake.nowMillis })
        }
        val violation: FileAccessContractViolation = assertFailsWith { refusing.sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest() }
        assertTrue(violation.message.orEmpty().contains("could not set the modification time"), "was: ${violation.message}")
        val skipping: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(files = fake, root = "/root")
        }
        skipping.sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest()
        skipping.sweepWithZeroAgeDeletesAnOldEntry()
    }

    @Test
    fun `the age checks catch a sweep that ignores the age`() {
        val ignoring: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(
                files = object : FileAccess by fake {
                    override fun sweep(directory: String, olderThanMillis: Long, cancellation: io.github.jamal_wia.kmptoolkit.files.CancellationCheck, match: (String) -> Boolean): FileResult<Int> =
                        fake.sweep(directory, 0L, cancellation, match)
                },
                root = "/root",
                setLastModified = { path: String, millis: Long -> fake.setLastModified(path, millis); true },
                nowMillis = { fake.nowMillis },
            )
        }
        val violation: FileAccessContractViolation = assertFailsWith { ignoring.sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest() }
        assertTrue(violation.message.orEmpty().startsWith("sweepDeletesEntriesOlderThanTheAgeAndKeepsTheRest"), "was: ${violation.message}")
    }

    @Test
    fun `a decorator that resolves relative paths against a base fails the invalid path check`() {
        val sandbox: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(
                files = object : FileAccess by fake {
                    override fun list(directory: String): FileResult<List<String>> =
                        fake.list(if (directory.startsWith("/")) directory else "/root/$directory")
                },
                root = "/root",
            )
        }
        val violation: FileAccessContractViolation = assertFailsWith { sandbox.everyFunctionRejectsRelativeEmptyAndNulPaths() }
        assertTrue(violation.message.orEmpty().startsWith("everyFunctionRejectsRelativeEmptyAndNulPaths"), "was: ${violation.message}")
    }

    @Test
    fun `a decorator that drops the cancellation of sweep fails the sweep cancellation check`() {
        val dropping: FileAccessContract = FileAccessContract {
            val fake: FakeFileAccess = FakeFileAccess()
            fake.createDirectory("/root")
            FileAccessFixture(
                files = object : FileAccess by fake {
                    override fun sweep(directory: String, olderThanMillis: Long, cancellation: io.github.jamal_wia.kmptoolkit.files.CancellationCheck, match: (String) -> Boolean): FileResult<Int> =
                        fake.sweep(directory, olderThanMillis, io.github.jamal_wia.kmptoolkit.files.CancellationCheck.Never, match)
                },
                root = "/root",
            )
        }
        assertFailsWith<FileAccessContractViolation> { dropping.sweepStopsWhenCancelled() }
    }

    @Test
    fun `a fake sweeps an old symbolic link and keeps its target`() {
        val fake: FakeFileAccess = FakeFileAccess()
        FileAccessContract { fixtureOf(fake) }.sweepDeletesAnOldSymbolicLink()
        assertTrue("/root/link" !in fake.entries(), "the link went")
        assertTrue("/root/target" in fake.entries(), "its target stayed (so the check ran and did not skip)")
    }
}
