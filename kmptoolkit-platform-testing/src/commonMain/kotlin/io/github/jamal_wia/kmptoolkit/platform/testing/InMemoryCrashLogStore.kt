package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.crash.CrashLogStore
import io.github.jamal_wia.kmptoolkit.platform.crash.CrashRecord

/**
 * A [CrashLogStore] that keeps records in a list instead of on disk.
 *
 * Two things it makes testable. First, that your startup code reads and reports a crash from the
 * previous run — seed it with [seed] and assert what your reporter did. Second, that the reading
 * really does clear: call [readAndClear] twice and the second call must come back empty, or every
 * launch will re-report the same crash forever.
 *
 * @param initial records present before the test starts, as if written by a previous run.
 */
public class InMemoryCrashLogStore(initial: List<CrashRecord> = emptyList()) : CrashLogStore {

    private val records: MutableList<CrashRecord> = initial.toMutableList()

    /**
     * The records currently held, without clearing them.
     *
     * This is the inspection hatch a test needs and [readAndClear] deliberately does not provide:
     * asserting on what was written must not itself consume it.
     */
    public val stored: List<CrashRecord> get() = records.toList()

    /** How many times [readAndClear] has been called. */
    public var readCount: Int = 0
        private set

    /** Adds [record] as if a previous run had crashed, without going through [write]. */
    public fun seed(record: CrashRecord) {
        records.add(record)
    }

    override fun write(record: CrashRecord) {
        records.add(record)
    }

    override fun readAndClear(): List<CrashRecord> {
        readCount++
        val snapshot: List<CrashRecord> = records.toList()
        records.clear()
        return snapshot
    }
}
