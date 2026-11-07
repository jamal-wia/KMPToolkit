package io.github.jamal_wia.kmptoolkit.uploader.testing

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/** The invariants the contract suite misses, asserted against the reference in-memory store. */
class StrongStoreProbeRound4AdversarialTest {

    @Test
    fun `the in-memory store matches tags and keys exactly and keeps payloads verbatim`() = runTest {
        StrongStoreProbeRound4.tagsAreMatchedExactly(InMemoryUploaderStore())
        StrongStoreProbeRound4.uniqueKeysAreMatchedExactly(InMemoryUploaderStore())
        StrongStoreProbeRound4.payloadsComeBackVerbatim(InMemoryUploaderStore())
    }

    @Test
    fun `the in-memory store leaves untouched fields alone and stores attempts and gates as given`() = runTest {
        StrongStoreProbeRound4.everyGuardedWriteLeavesTheOtherFieldsAlone(InMemoryUploaderStore())
        StrongStoreProbeRound4.attemptsAndGatesAreStoredAbsolutely(InMemoryUploaderStore())
    }

    @Test
    fun `the in-memory store observes live, revives at the tail, reads exactly and hands out snapshots`() = runTest {
        StrongStoreProbeRound4.observationIsLiveAndNeverCompletes(InMemoryUploaderStore())
        StrongStoreProbeRound4.insertKeepOverAParkedItemEntersAtTheTail(InMemoryUploaderStore())
        StrongStoreProbeRound4.pointReadsAreExact(InMemoryUploaderStore())
        StrongStoreProbeRound4.aReadListIsASnapshot(InMemoryUploaderStore())
    }
}
