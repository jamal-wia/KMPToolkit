package io.github.jamal_wia.kmptoolkit.language

import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A language switch storm: 16 real threads against one holder with the real platform side effect.
 * All waits are bounded; the default locale is restored afterwards.
 */
class AppLanguageHolderStormJvmTest {

    private val originalDefault: Locale = Locale.getDefault()

    @AfterTest
    fun restore() {
        Locale.setDefault(originalDefault)
    }

    private val languages: List<AppLanguage> = listOf("en", "de", "fr", "ar", "ja", "ru").map { code ->
        AppLanguage(code = code, isLtr = code != "ar")
    }

    @Test
    fun `sixteen threads switching languages leave platform flow and persisted choice in agreement`() {
        repeat(ROUNDS) { round ->
            val persisted: MutableList<AppLanguage> = Collections.synchronizedList(mutableListOf())
            val holder: AppLanguageHolder = createAppLanguageHolder(
                initialLanguage = languages[0],
                onLanguageChanged = { persisted += it },
            )
            val start = CountDownLatch(1)
            val failures: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
            val threads: List<Thread> = List(16) { index ->
                thread(isDaemon = true, name = "language-storm-$index") {
                    start.await(5, TimeUnit.SECONDS)
                    try {
                        repeat(PER_THREAD) { i -> holder.setLanguage(languages[(index + i) % languages.size]) }
                    } catch (t: Throwable) {
                        failures += t
                    }
                }
            }
            start.countDown()
            threads.forEach { it.join(30_000) }
            assertTrue(threads.none { it.isAlive }, "round $round: a switching thread is stuck")
            assertEquals(emptyList(), failures.toList())

            val final: AppLanguage = holder.languageFlow.value
            assertEquals(final.code, Locale.getDefault().language, "round $round: platform locale differs from the flow")
            assertEquals(final, persisted.last(), "round $round: the last persisted language differs from the flow")
            persisted.zipWithNext().forEach { (a, b) ->
                assertNotEquals(a, b, "round $round: a no-change set was persisted")
            }
        }
    }

    @Test
    fun `a callback that throws leaves the state updated and the lock free for the next caller`() {
        var fail = true
        val holder: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = languages[0],
            onLanguageChanged = { if (fail) throw IllegalStateException("persist failed") },
        )
        assertFailsWith<IllegalStateException> { holder.setLanguage(languages[1]) }
        assertEquals(languages[1], holder.language)

        fail = false
        var done = false
        val other = thread(isDaemon = true) {
            holder.setLanguage(languages[2])
            done = true
        }
        other.join(10_000)
        assertTrue(done, "the lock was left held after a throwing callback")
        assertEquals(languages[2], holder.language)
    }

    @Test
    fun `a platform side effect that throws changes neither the state nor the persisted choice`() {
        val persisted: MutableList<AppLanguage> = mutableListOf()
        val holder: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = languages[0],
            onLanguageChanged = { persisted += it },
            applyGlobally = { language -> if (language == languages[3]) throw IllegalStateException("no locale") },
        )

        assertFailsWith<IllegalStateException> { holder.setLanguage(languages[3]) }
        holder.setLanguage(languages[1])

        assertEquals(languages[1], holder.language)
        assertEquals(listOf(languages[1]), persisted)
    }

    @Test
    fun `a callback that selects another language leaves flow platform and the last callback in agreement`() {
        val persisted: MutableList<AppLanguage> = mutableListOf()
        lateinit var holder: AppLanguageHolder
        holder = createAppLanguageHolder(
            initialLanguage = languages[0],
            onLanguageChanged = { language ->
                persisted += language
                if (language == languages[1]) holder.setLanguage(languages[2])
            },
        )

        holder.setLanguage(languages[1])

        assertEquals(languages[2], holder.language)
        assertEquals(languages[2].code, Locale.getDefault().language)
        assertEquals(languages[2], persisted.last())
    }

    private companion object {
        const val ROUNDS = 15
        const val PER_THREAD = 200
    }
}
