package io.github.jamal_wia.kmptoolkit.language

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two threads calling [AppLanguageHolder.setLanguage] at once must leave the holder agreeing with
 * itself: the language last pushed onto the platform default, the value of
 * [AppLanguageHolder.languageFlow] and the last value handed to `onLanguageChanged` — the one the
 * app persists — must all be the same language. Each call is a sequence of side effects, and two
 * sequences interleaving is how the platform locale could end up on one language while the
 * persisted choice records the other.
 *
 * The race is landed deterministically: the first call is parked right after its platform side
 * effect has taken hold, and the second is let loose meanwhile.
 */
class AppLanguageHolderConcurrencyJvmTest {

    @Test
    fun concurrentSetLanguageCallsLeaveLocaleStateAndPersistedChoiceInAgreement() {
        val english = AppLanguage(code = "en", isLtr = true)
        val arabic = AppLanguage(code = "ar", isLtr = false)
        val german = AppLanguage(code = "de", isLtr = true)

        val applied: MutableList<AppLanguage> = Collections.synchronizedList(mutableListOf())
        val persisted: MutableList<AppLanguage> = Collections.synchronizedList(mutableListOf())
        val arabicBeingApplied = CountDownLatch(1)
        val releaseArabic = CountDownLatch(1)

        val holder: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = english,
            onLanguageChanged = { language -> persisted += language },
            applyGlobally = { language ->
                applied += language
                // Arabic is now in force on the platform; hold its call right there, before it
                // has updated the holder's state or persisted anything.
                if (language == arabic) {
                    arabicBeingApplied.countDown()
                    releaseArabic.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            },
        )

        val first = Thread { holder.setLanguage(arabic) }
        first.start()
        assertTrue(arabicBeingApplied.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        val second = Thread { holder.setLanguage(german) }
        second.start()
        // The second call either runs to completion while the first is parked (calls interleave)
        // or waits for the first to finish (calls are serialized). Either way, let the first go
        // only once the second has settled into one of those two outcomes.
        awaitFinishedOrWaiting(second)
        releaseArabic.countDown()
        first.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        second.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

        val inEffect: AppLanguage = applied.last()
        assertEquals(inEffect, holder.language, "languageFlow disagrees with the platform locale")
        assertEquals(inEffect, persisted.last(), "persisted choice disagrees with the platform locale")
    }

    private fun awaitFinishedOrWaiting(thread: Thread) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            when (thread.state) {
                Thread.State.TERMINATED, Thread.State.WAITING, Thread.State.BLOCKED -> return
                else -> Thread.sleep(1)
            }
        }
        error("second setLanguage neither finished nor blocked")
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 5
    }
}
