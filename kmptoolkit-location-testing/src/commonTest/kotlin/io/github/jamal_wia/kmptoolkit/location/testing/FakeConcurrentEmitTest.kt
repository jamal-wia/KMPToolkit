package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The fix behind `emit` is documented as safe to publish from anywhere: eight publishers, eight readers of
 * `getCurrentLocation` and eight fresh `observeLocation` collectors at once never fail, and after the last
 * publish every reader sees exactly that fix.
 */
class FakeConcurrentEmitTest {

    @Test
    fun `concurrent publishers readers and fresh collectors never fail and end on the last fix`() = runTest {
        val provider = FakeLocationProvider()
        withContext(Dispatchers.Default) {
            withTimeout(30_000) {
                val publishers = (0 until 8).map { id ->
                    async { repeat(5_000) { i -> provider.emit(if (i % 7 == 0) null else GeoCoordinates(id.toDouble(), i.toDouble())) } }
                }
                val readers = (0 until 8).map { async { repeat(2_000) { provider.getCurrentLocation() } } }
                val collectors = (0 until 8).map { async { repeat(300) { provider.observeLocation().first() } } }
                (publishers + readers + collectors).awaitAll()
            }
        }
        val last = GeoCoordinates(90.0, 180.0)
        provider.emit(last)
        assertEquals(last, provider.getCurrentLocation())
        assertEquals(last, provider.observeLocation().first())
    }
}
