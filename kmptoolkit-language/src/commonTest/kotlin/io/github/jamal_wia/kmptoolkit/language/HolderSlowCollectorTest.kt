package io.github.jamal_wia.kmptoolkit.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * `languageFlow` is a `StateFlow`: a collector that is busy while the language is switched a thousand times must
 * neither slow the switching down nor see the intermediate values. The collector is held by a deferred (no clock
 * decides the order); the real-time timeouts only bound a hang and fail with their own exception.
 */
class HolderSlowCollectorTest {

    private val initial: AppLanguage = AppLanguage("en")
    private val last: AppLanguage = AppLanguage("ja")
    private val others: List<AppLanguage> = listOf("de", "fr", "ar", "pt-BR", "zh-Hans").map { AppLanguage(it) }

    @Test
    fun `a collector blocked on its first value sees only the first and the latest language`() = runBlocking {
        val holder: AppLanguageHolder = createAppLanguageHolder(initialLanguage = initial, applyGlobally = { })
        val seen: MutableList<AppLanguage> = mutableListOf()
        val firstSeen: CompletableDeferred<Unit> = CompletableDeferred()
        val release: CompletableDeferred<Unit> = CompletableDeferred()
        val sawLast: CompletableDeferred<Unit> = CompletableDeferred()
        val collector = launch(Dispatchers.Default) {
            holder.languageFlow.collect { value ->
                seen += value
                if (seen.size == 1) {
                    firstSeen.complete(Unit)
                    release.await()
                }
                if (value == last) sawLast.complete(Unit)
            }
        }
        withTimeout(20_000) { firstSeen.await() }

        // The writer is not held up by the blocked collector: a thousand switches complete while it is parked.
        repeat(1000) { index -> holder.setLanguage(others[index % others.size]) }
        holder.setLanguage(last)

        release.complete(Unit)
        withTimeout(20_000) { sawLast.await() }
        collector.cancelAndJoin()
        assertEquals(listOf(initial, last), seen.toList(), "the intermediate languages must be conflated away")
    }

    @Test
    fun `a collector that starts after the switches sees exactly the latest language`() = runBlocking {
        val holder: AppLanguageHolder = createAppLanguageHolder(initialLanguage = initial, applyGlobally = { })
        repeat(1000) { index -> holder.setLanguage(others[index % others.size]) }
        holder.setLanguage(last)
        val seen: MutableList<AppLanguage> = mutableListOf()
        val got: CompletableDeferred<Unit> = CompletableDeferred()
        val collector = launch(Dispatchers.Default) {
            holder.languageFlow.collect { value ->
                seen += value
                got.complete(Unit)
            }
        }
        withTimeout(20_000) { got.await() }
        collector.cancelAndJoin()
        assertEquals(listOf(last), seen.toList())
    }
}
