package io.github.jamal_wia.kmptoolkit.activity

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Sentences of 04-api-reference written as tests, aimed at what the neighbouring suites do not state outright: no
 * `resolveActivity` pre-check, an `ActivityAccess` of a consumer that predates `forEachResumedActivity` (it must
 * keep compiling and keep its single-window meaning), a consumer's own `SystemScreenKind` and launcher, and what a
 * released access answers.
 */
@RunWith(AndroidJUnit4::class)
class ActivityContractLawyerRound10Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val controllers: MutableList<ActivityController<out Activity>> = mutableListOf()
    private val accesses: MutableList<ActivityAccess> = mutableListOf()

    @AfterTest
    fun tearDown() {
        accesses.forEach { it.release() }
        controllers.forEach { it.close() }
    }

    private object Mine : SystemScreenKind

    private fun window(): Activity = Robolectric.buildActivity(Activity::class.java).also { controllers += it }.setup().get()

    @Test
    fun `startFirstResolvable never asks the package manager and starts a screen the package manager does not know`() {
        val unknown = Intent("kmptoolkit.test.UNKNOWN_SCREEN")
        assertEquals(null, application.packageManager.resolveActivity(unknown, 0), "the premise: nobody resolves it")
        val request = SystemScreenRequest(listOf(unknown), application, Mine)
        val started: MutableList<String?> = mutableListOf()

        val result: Boolean = request.startFirstResolvable { started += it.action }

        assertTrue(result)
        assertEquals(listOf<String?>("kmptoolkit.test.UNKNOWN_SCREEN"), started)
    }

    @Test
    fun `startFirstResolvable moves on after a not-found and a security refusal and stops at the first success`() {
        val request = SystemScreenRequest(listOf(Intent("a"), Intent("b"), Intent("c"), Intent("d")), application, Mine)
        val tried: MutableList<String?> = mutableListOf()

        val result: Boolean = request.startFirstResolvable { intent ->
            tried += intent.action
            when (intent.action) {
                "a" -> throw ActivityNotFoundException("no a")
                "b" -> throw SecurityException("not exported")
                else -> Unit
            }
        }

        assertTrue(result)
        assertEquals(listOf<String?>("a", "b", "c"), tried)
    }

    @Test
    fun `startFirstResolvable lets another failure through and tries nothing after it`() {
        val request = SystemScreenRequest(listOf(Intent("a"), Intent("b")), application, Mine)
        val tried: MutableList<String?> = mutableListOf()

        val failure: IllegalStateException = kotlin.test.assertFailsWith {
            request.startFirstResolvable { tried += it.action; throw IllegalStateException("boom") }
        }

        assertEquals("boom", failure.message)
        assertEquals(listOf<String?>("a"), tried)
    }

    @Test
    fun `a launcher of the consumer is a lambda and a kind of the consumer is a plain object`() {
        val seen: MutableList<SystemScreenKind> = mutableListOf()
        val launcher: SystemScreenLauncher = SystemScreenLauncher { request -> seen += request.kind; request.kind === Mine }

        assertTrue(launcher.launch(SystemScreenRequest(listOf(Intent("a")), application, Mine)))
        assertFalse(launcher.launch(SystemScreenRequest(listOf(Intent("a")), application, object : SystemScreenKind {})))
        assertSame(Mine, seen.first())
    }

    @Test
    fun `an ActivityAccess written before forEachResumedActivity existed keeps compiling and visits its one window`() {
        val shown: Activity = window()
        val mine = object : ActivityAccess {
            var current: Activity? = shown
            override fun <R> withActivity(block: (Activity) -> R): R? = current?.let(block)
            override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
                object : ActivitySubscription { override fun cancel() = Unit }
            override fun release() = Unit
        }

        val visited: MutableList<Activity> = mutableListOf()
        mine.forEachResumedActivity { visited += it }
        assertEquals(listOf(shown), visited)

        mine.current = null
        mine.forEachResumedActivity { visited += it }
        assertEquals(listOf(shown), visited, "no activity: the block never runs")
    }

    @Test
    fun `a released access answers nothing and a window that resumes afterwards is not seen`() {
        val access: ActivityAccess = createActivityAccess(application).also { accesses += it }
        val first: Activity = window()
        assertSame(first, access.withActivity { it })

        access.release()
        access.release()

        assertNull(access.withActivity { it })
        window()
        assertNull(access.withActivity { it })
        val visited: MutableList<Activity> = mutableListOf()
        access.forEachResumedActivity { visited += it }
        assertEquals(emptyList(), visited)
        var calls = 0
        access.addOnActivityResumedListener { calls++ }.cancel()
        window()
        assertEquals(0, calls)
    }
}
