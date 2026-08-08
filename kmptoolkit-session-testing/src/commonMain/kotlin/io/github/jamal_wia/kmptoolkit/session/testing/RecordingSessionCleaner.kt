package io.github.jamal_wia.kmptoolkit.session.testing

import io.github.jamal_wia.kmptoolkit.session.SessionCleaner

/**
 * A [SessionCleaner] that wipes nothing, counts how often it ran, and does whatever the test tells
 * it to while running.
 *
 * Use it to assert that your teardown wiring reaches every cleaner, and to drive the failure paths
 * that are awkward to reproduce with a real cleaner — a cleaner that throws, or one that hangs past
 * the manager's cleaner timeout:
 *
 * ```kotlin
 * val ok = RecordingSessionCleaner(name = "cache")
 * val broken = RecordingSessionCleaner(name = "db", onClean = { throw IllegalStateException("disk full") })
 *
 * val report = createSessionManager(listOf(ok, broken), dispatchers = dispatchers).run {
 *     startSession()
 *     endSession()
 * }
 *
 * assertEquals(1, ok.cleanCalls)                          // a failure next door did not skip it
 * assertEquals(listOf("db"), report.cleanerFailures.map { it.name })
 * ```
 *
 * **Not thread-safe.** [cleanCalls] is a plain counter, so a test that runs several teardowns in
 * genuine parallel against one instance can lose increments. That is the normal shape of a test
 * double and not worth an atomic — assert on one manager at a time, or count in your own
 * synchronized fake.
 */
public class RecordingSessionCleaner(

    /** The [SessionCleaner.name] this fixture reports, and the one that appears in a failure. */
    override val name: String = "recording-cleaner",

    /**
     * Runs inside [clean], after the call has been counted. Assignable mid-test, so one instance
     * can succeed for a first teardown and throw or hang for a second. Defaults to doing nothing.
     */
    public var onClean: suspend () -> Unit = {},
) : SessionCleaner {

    private var cleanCallCount: Int = 0

    /** How many times [clean] has been entered, counting calls that then threw or hung. */
    public val cleanCalls: Int
        get() = cleanCallCount

    override suspend fun clean() {
        cleanCallCount++
        onClean()
    }
}
