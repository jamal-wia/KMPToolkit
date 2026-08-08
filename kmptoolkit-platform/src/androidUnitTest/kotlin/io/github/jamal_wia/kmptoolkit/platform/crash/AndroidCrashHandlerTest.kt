package io.github.jamal_wia.kmptoolkit.platform.crash

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * The handler is asserted by invoking it directly rather than by crashing a thread: a real
 * uncaught exception would take the test process with it, and what needs proving — that the record
 * is written and that the previous handler still runs — is observable without doing that.
 */
/**
 * A local double rather than the one in `kmptoolkit-platform-testing`: that module depends on this
 * one, and pointing this module's test classpath back at it would make the two build in a loop.
 */
private class RecordingCrashLogStore : CrashLogStore {
    val stored: MutableList<CrashRecord> = mutableListOf()
    override fun write(record: CrashRecord) {
        stored.add(record)
    }

    override fun readAndClear(): List<CrashRecord> = stored.toList().also { stored.clear() }
}

@RunWith(AndroidJUnit4::class)
class AndroidCrashHandlerTest {

    private val store = RecordingCrashLogStore()
    private val original: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()
    private val installations: MutableList<CrashHandlerInstallation> = mutableListOf()

    @AfterTest
    fun tearDown() {
        installations.asReversed().forEach { installation -> installation.uninstall() }
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    private fun install(): CrashHandlerInstallation =
        installCrashHandler(store).also { installations.add(it) }

    @Test
    fun `records the crash it is handed`() {
        install()

        Thread.getDefaultUncaughtExceptionHandler()
            ?.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        assertEquals(1, store.stored.size)
        assertEquals("boom", store.stored.single().message)
        assertEquals(Thread.currentThread().name, store.stored.single().threadName)
    }

    @Test
    fun `stamps a plausible wall-clock time on the record`() {
        val before: Long = System.currentTimeMillis()
        install()

        Thread.getDefaultUncaughtExceptionHandler()
            ?.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

        val recorded: Long = store.stored.single().timestampMs
        assertTrue(
            recorded in before..System.currentTimeMillis(),
            "expected a timestamp inside the test's own window, was $recorded",
        )
    }

    @Test
    fun `delegates to the handler that was already installed`() {
        var delegated: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> delegated = throwable }
        install()
        val crash = IllegalStateException("boom")

        Thread.getDefaultUncaughtExceptionHandler()
            ?.uncaughtException(Thread.currentThread(), crash)

        assertSame(crash, delegated, "the system crash dialog must still get its exception")
    }

    @Test
    fun `records two crashes rather than overwriting the first`() {
        install()
        val handler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

        handler?.uncaughtException(Thread.currentThread(), IllegalStateException("first"))
        handler?.uncaughtException(Thread.currentThread(), IllegalStateException("second"))

        assertEquals(listOf("first", "second"), store.stored.map { it.message })
    }

    @Test
    fun `uninstalling restores the previous handler`() {
        val previous = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(previous)

        install().uninstall()

        assertSame(previous, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `uninstalling twice is harmless`() {
        val installation: CrashHandlerInstallation = install()

        installation.uninstall()
        installation.uninstall()
    }

    @Test
    fun `uninstalling does not unhook a handler installed after ours`() {
        val installation: CrashHandlerInstallation = install()
        val later = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(later)

        installation.uninstall()

        assertSame(
            later,
            Thread.getDefaultUncaughtExceptionHandler(),
            "uninstalling must not win a race it never entered",
        )
    }
}
