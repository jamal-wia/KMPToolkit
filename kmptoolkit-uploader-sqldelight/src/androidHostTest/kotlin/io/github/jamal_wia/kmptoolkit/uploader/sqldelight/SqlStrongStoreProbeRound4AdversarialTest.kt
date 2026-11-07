package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * The invariants the contract suite misses (see the mutation test in the testing module), asserted against the
 * real SQLite store: exact tag and key matching (no LIKE semantics, no collation), verbatim payloads, untouched
 * columns, absolute counters, live observation.
 */
@RunWith(AndroidJUnit4::class)
class SqlStrongStoreProbeRound4AdversarialTest {

    private val storages: MutableList<UploaderStorage> = mutableListOf()
    private val files: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        storages.forEach { runCatching { it.close() } }
        files.forEach { application().deleteDatabase(uploaderDatabaseFileName(it)) }
    }

    private fun fresh(): io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore {
        val name = "adv.a1r4.${Random.nextLong(0L, Long.MAX_VALUE)}"
        files += name
        return createUploaderStorage(application(), UploaderDatabaseConfig(name)).also { storages += it }.store
    }

    @Test
    fun `tags and unique keys are matched exactly and payloads come back verbatim`() = runTest {
        SqlStrongStoreProbeRound4.tagsAreMatchedExactly(fresh())
        SqlStrongStoreProbeRound4.uniqueKeysAreMatchedExactly(fresh())
        SqlStrongStoreProbeRound4.payloadsComeBackVerbatim(fresh())
    }

    @Test
    fun `guarded writes leave the other columns alone and counters are stored as given`() = runTest {
        SqlStrongStoreProbeRound4.everyGuardedWriteLeavesTheOtherFieldsAlone(fresh())
        SqlStrongStoreProbeRound4.attemptsAndGatesAreStoredAbsolutely(fresh())
    }

    @Test
    fun `observation is live, a parked key is revived at the tail, point reads are exact`() = runTest {
        SqlStrongStoreProbeRound4.observationIsLiveAndNeverCompletes(fresh())
        SqlStrongStoreProbeRound4.insertKeepOverAParkedItemEntersAtTheTail(fresh())
        SqlStrongStoreProbeRound4.pointReadsAreExact(fresh())
        SqlStrongStoreProbeRound4.aReadListIsASnapshot(fresh())
    }
}
