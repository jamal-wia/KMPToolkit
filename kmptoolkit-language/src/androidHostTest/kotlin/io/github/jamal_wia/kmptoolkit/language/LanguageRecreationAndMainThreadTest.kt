package io.github.jamal_wia.kmptoolkit.language

import android.app.Activity
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The documented way to persist from a callback (post to the main looper, do not wait) against a
 * main thread that is itself calling setLanguage, and the localized context across a recreation that
 * hands the activity the system locale.
 */
@RunWith(RobolectricTestRunner::class)
class LanguageRecreationAndMainThreadTest {

    private val originalDefault: Locale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `an asynchronous main-bound callback and a main thread caller do not deadlock`() {
        val main = Handler(Looper.getMainLooper())
        val persisted: MutableList<String?> = java.util.Collections.synchronizedList(mutableListOf())
        val holder: AppLanguageHolder = createAppLanguageHolder(
            initialLanguage = AppLanguage("en", true),
            onLanguageChanged = { language -> main.post { persisted += language.code } },
        )
        val inCallback = CountDownLatch(1)
        val worker = thread(isDaemon = true, name = "language-off-main") {
            inCallback.countDown()
            repeat(200) { i -> holder.setLanguage(AppLanguage(if (i % 2 == 0) "de" else "fr", true)) }
        }
        assertTrue(inCallback.await(5, TimeUnit.SECONDS))
        repeat(200) { i -> holder.setLanguage(AppLanguage(if (i % 2 == 0) "ja" else "ru", true)) }
        worker.join(10_000)
        assertTrue(!worker.isAlive, "the off-main caller is stuck")

        var ran = 0
        while (ran < 10_000 && !shadowOf(Looper.getMainLooper()).isIdle) {
            shadowOf(Looper.getMainLooper()).runOneTask()
            ran++
        }
        assertEquals(holder.language.code, persisted.last())
    }

    @Test
    fun `the localized context of a recreated activity keeps the app language when it is handed the system locale`() {
        val french = AppLanguage("fr", true)
        createAppLanguageHolder(french).setLanguage(french)
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        assertEquals("fr", localizedContext(controller.get(), french).resources.configuration.locales[0].language)

        val system = Configuration(controller.get().resources.configuration).apply { setLocale(Locale.ENGLISH) }
        controller.configurationChange(system)
        val recreated: Activity = controller.get()

        assertEquals("fr", localizedContext(recreated, french).resources.configuration.locales[0].language)
        controller.pause().stop().destroy()
    }
}
