package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** [MarkerLookupsBoss1Checks] on this platform's SQLite driver. */
class MarkerLookupsBoss1IosAdversarialTest {

    @Test
    fun `a tag wipe of a marker tag removes the raw and the escaped rows in insertion order`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().tagWipeRemovesTheRawRowsAndTheEscapedRowsInInsertionOrder()
    }

    @Test
    fun `a tag wipe of an exact escaped form touches only the row it decodes to`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().tagWipeOfAnExactEscapedFormTouchesOnlyTheRowItDecodesTo()
    }

    @Test
    fun `the type observer merges both spellings in order and re-emits after a write`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().typeObserverMergesBothSpellingsInOrderAndReEmitsAfterAWrite()
    }

    @Test
    fun `KEEP revives a parked legacy holder and respects a live one`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().keepRevivesAParkedLegacyHolderAndRespectsALiveOne()
    }

    @Test
    fun `KEEP sees a live row of the escaped spelling beside a parked legacy row`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().keepSeesALiveRowOfTheEscapedSpellingBesideAParkedLegacyRow()
    }

    @Test
    fun `REPLACE returns the live legacy holder and leaves one row`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().replaceReturnsTheLiveLegacyHolderAndLeavesOneRow()
    }

    @Test
    fun `REPLACE over every combination of spellings returns the earliest and removes all`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().replaceOverEveryCombinationOfSpellingsReturnsTheEarliestAndRemovesAll()
    }

    @Test
    fun `an ordinary text and an exact escaped form cost the queries they always cost`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().anOrdinaryTextAndAnExactEscapedFormCostTheQueriesTheyAlwaysCost()
    }

    @Test
    fun `a marker text that reads back unchanged is looked up under both spellings`() = runTest(timeout = 2.minutes) {
        MarkerLookupsBoss1Checks().aMarkerPrefixedTextThatReadsBackUnchangedIsLookedUpUnderBothSpellings()
    }
}
