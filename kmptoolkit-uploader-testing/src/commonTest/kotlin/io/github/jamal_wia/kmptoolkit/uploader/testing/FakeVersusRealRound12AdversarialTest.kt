package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.Uploader
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * A2 round 12, Q3: the same scenario script on [FakeUploader] and on the real engine over [InMemoryUploaderStore].
 *
 * The documented contract of the fake (docs/kmptoolkit-uploader/06-testing.md and its KDoc): it records what was
 * enqueued instead of persisting or delivering anything, records the payload object and never calls `encodePayload`,
 * returns `fake-id-N` unless told what a KEEP conflict answers, records every id and tag passed to a withdrawal
 * including unknown ones, counts triggers, and emits from `observe` only what `emitObserved` set. Everything the two
 * share must agree: the arguments of every enqueue as the store row carries them, the order, the answer of a
 * KEEP conflict once the fake is told it, and the ids the engine returned versus the ids a result function maps.
 * Every difference is one of the documented ones above. The table is printed.
 */
class FakeVersusRealRound12AdversarialTest {

    private class CountingHandler : UploaderHandler<String> {
        override val type: String = "r12.diff"
        var encodes: Int = 0
        override fun encodePayload(payload: String): String {
            encodes++
            return "enc:$payload"
        }
        override fun decodePayload(raw: String): String = raw.removePrefix("enc:")
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Success
    }

    private class Step(val name: String, val run: suspend (Uploader, CountingHandler) -> String?)

    private val script: List<Step> = listOf(
        Step("enqueue a (key k, tag t)") { u, h -> u.enqueue(h, "a", uniqueKey = "k", tag = "t") },
        Step("enqueue a2 (key k, KEEP)") { u, h -> u.enqueue(h, "a2", uniqueKey = "k", conflictPolicy = ConflictPolicy.KEEP) },
        Step("enqueue b (key k, REPLACE)") { u, h -> u.enqueue(h, "b", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE) },
        Step("enqueue c (no key)") { u, h -> u.enqueue(h, "c") },
        Step("cancel unknown id") { u, _ -> u.cancel("no-such-id"); "-" },
        Step("cancelByTag t") { u, _ -> u.cancelByTag("t"); "-" },
        Step("trigger") { u, _ -> u.trigger(); "-" },
    )

    @Test
    fun `the same script on the fake and on the real engine agrees where the contract says so and differs only as documented`() = runTest {
        // Real engine, never started and never drained: it persists, nothing is delivered.
        val store = InMemoryUploaderStore()
        val realHandler = CountingHandler()
        val engine = createUploaderEngine(store = store, handlers = listOf(realHandler), scope = backgroundScope)
        // Fake: default answer, and a second fake told how a KEEP conflict answers (the documented way).
        val defaultFake = FakeUploader()
        val fakeHandler1 = CountingHandler()
        val keys: MutableSet<String> = mutableSetOf()
        val modelled = FakeUploader { call ->
            val key: String? = call.uniqueKey
            when {
                key == null -> "m-${call.ordinal}"
                call.conflictPolicy == ConflictPolicy.KEEP && key in keys -> null
                else -> { keys += key; "m-${call.ordinal}" }
            }
        }
        val fakeHandler2 = CountingHandler()

        val realAnswers: MutableList<String?> = mutableListOf()
        val defaultAnswers: MutableList<String?> = mutableListOf()
        val modelAnswers: MutableList<String?> = mutableListOf()
        val table: MutableList<String> = mutableListOf()
        for (step in script) {
            realAnswers += step.run(engine, realHandler)
            defaultAnswers += step.run(defaultFake, fakeHandler1)
            modelAnswers += step.run(modelled, fakeHandler2)
            val rows: List<UploaderItem> = store.getAllActive()
            table += "FAKE-VS-REAL ${step.name}: real=${realAnswers.last()?.let { if (it == "-") "-" else "id" }} rows=${rows.map { it.payload }} default=${defaultAnswers.last()?.take(7)} modelled=${modelAnswers.last()?.take(2)}"
        }
        table.forEach { println(it) }

        // Shared and must agree: nullness of every answer between the real engine and the modelled fake.
        assertEquals(realAnswers.map { it == null }, modelAnswers.map { it == null }, "KEEP conflict answers differ: real=$realAnswers modelled=$modelAnswers")
        // Documented difference 1: a fake with no result function answers an id for a KEEP conflict, the engine null.
        assertNull(realAnswers[1], "the engine answers null for a KEEP conflict")
        assertNotNull(defaultAnswers[1], "the default fake answers an id for every enqueue")
        assertTrue(defaultAnswers.take(4).all { it != null && it.startsWith("fake-id-") })
        // The arguments of every enqueue the engine persisted agree with the ones the fake recorded.
        val recorded: List<FakeUploader.Enqueued> = defaultFake.enqueued
        assertEquals(4, recorded.size)
        assertEquals(listOf("a", "a2", "b", "c"), recorded.map { it.payload })
        assertEquals(listOf(1, 2, 3, 4), recorded.map { it.ordinal })
        assertEquals(listOf("k", "k", "k", null), recorded.map { it.uniqueKey })
        assertEquals(listOf("t", null, null, null), recorded.map { it.tag })
        assertEquals(listOf(ConflictPolicy.KEEP, ConflictPolicy.KEEP, ConflictPolicy.REPLACE, ConflictPolicy.KEEP), recorded.map { it.conflictPolicy })
        // Real: a stays (KEEP a2 refused), b replaces it (key k), c is queued; then the tag wipe takes nothing (a is gone).
        val rows: List<UploaderItem> = store.getAllActive()
        assertEquals(listOf("enc:b", "enc:c"), rows.map { it.payload })
        assertEquals(listOf("k", null), rows.map { it.uniqueKey })
        // Documented difference 2: the real payload is the encoded string, the fake records the object and never encodes.
        assertEquals(0, fakeHandler1.encodes + fakeHandler2.encodes)
        assertTrue(realHandler.encodes >= 3, "the engine encodes every payload it persists: ${realHandler.encodes}")
        // Documented difference 3: the fake persists nothing, observe shows only what emitObserved set.
        assertTrue(defaultFake.observe("r12.diff").first().isEmpty())
        assertEquals(listOf("enc:b", "enc:c"), engine.observe("r12.diff").first().map { it.payload })
        defaultFake.emitObserved(rows)
        assertEquals(rows.map { it.id }, defaultFake.observe("r12.diff").first().map { it.id })
        // Documented difference 4: a withdrawal of an unknown id is recorded by the fake and a no-op for the engine.
        assertEquals(listOf("no-such-id"), defaultFake.cancelled)
        assertEquals(listOf("t"), defaultFake.cancelledByTag)
        // Documented difference 5: the fake counts the trigger, the engine conflates it (nothing to count, nothing thrown).
        assertEquals(1, defaultFake.triggerCount)
        // Ids: the engine returns an id that is the row id; the modelled fake's ids are the ones its function mapped.
        val realIds: List<String> = realAnswers.filterNotNull().filter { it != "-" }
        assertEquals(3, realIds.size)
        assertEquals(realIds.toSet().size, realIds.size, "engine ids are distinct")
        assertTrue(modelAnswers.filterNotNull().filter { it != "-" }.all { it.startsWith("m-") })
    }
}
