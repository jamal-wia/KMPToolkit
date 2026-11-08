package io.github.jamal_wia.kmptoolkit.language

import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Sixteen threads asking for the application's resources while the language source keeps changing. */
@RunWith(RobolectricTestRunner::class)
class LocalizedApplicationResourcesStormTest {

    @Test
    fun `resources asked for during a language storm always match a language that was current and settle on the last`() {
        val base = RuntimeEnvironment.getApplication().baseContext
        val resources = LocalizedApplicationResources()
        val codes: List<String> = listOf("en", "de", "fr", "ar", "ja")
        val current = AtomicReference(codes[0])
        resources.readLanguageFrom { AppLanguage(current.get(), isLtr = true) }
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val seen = Collections.synchronizedSet(mutableSetOf<String>())
        val start = CountDownLatch(1)
        val readers: List<Thread> = List(16) { index ->
            thread(isDaemon = true, name = "resources-reader-$index") {
                start.await(5, TimeUnit.SECONDS)
                try {
                    repeat(300) { seen += resources.resourcesOf(base).configuration.locales[0].language }
                } catch (t: Throwable) {
                    failures += t
                }
            }
        }
        val switcher = thread(isDaemon = true, name = "resources-switcher") {
            start.await(5, TimeUnit.SECONDS)
            repeat(2_000) { i -> current.set(codes[i % codes.size]) }
        }
        start.countDown()
        (readers + switcher).forEach { it.join(30_000) }
        assertTrue((readers + switcher).none { it.isAlive }, "a thread is stuck")
        assertEquals(emptyList(), failures.toList())
        assertTrue(seen.all { it in codes }, "a locale nobody asked for: $seen")

        current.set("ja")
        assertEquals(Locale.forLanguageTag("ja").language, resources.resourcesOf(base).configuration.locales[0].language)
    }
}
