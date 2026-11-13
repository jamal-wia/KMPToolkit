package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals

class PossiblePromptWaitTest {

    private val delivered: MutableList<String> = mutableListOf()
    private var releases: Int = 0
    private var fallbackCalls: Int = 0

    private val wait: PossiblePromptWait<String> = PossiblePromptWait(
        fallback = {
            fallbackCalls++
            FALLBACK
        },
        release = { releases++ },
        deliver = { value: String -> delivered += value },
    )

    @Test
    fun decided_first_delivers_once_and_later_events_change_nothing() {
        wait.onDecided("first")
        wait.onGraceElapsed()
        wait.onWillResignActive()
        wait.onDidBecomeActive()
        wait.onDecided("second")
        wait.onCancelled()
        assertEquals(listOf("first"), delivered)
        assertEquals(1, releases)
    }

    @Test
    fun grace_elapsed_without_resign_delivers_the_fallback_once() {
        wait.onGraceElapsed()
        wait.onGraceElapsed()
        assertEquals(listOf(FALLBACK), delivered)
        assertEquals(1, releases)
    }

    @Test
    fun resign_then_grace_waits_and_active_delivers_the_fallback_once() {
        wait.onWillResignActive()
        wait.onGraceElapsed()
        assertEquals(emptyList(), delivered)
        assertEquals(0, releases)
        wait.onDidBecomeActive()
        assertEquals(listOf(FALLBACK), delivered)
        assertEquals(1, releases)
    }

    @Test
    fun active_without_a_resign_before_it_does_nothing() {
        wait.onDidBecomeActive()
        assertEquals(emptyList(), delivered)
        assertEquals(0, releases)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun resign_then_decided_delivers_the_decided_value() {
        wait.onWillResignActive()
        wait.onDecided("answered")
        assertEquals(listOf("answered"), delivered)
        assertEquals(1, releases)
    }

    @Test
    fun cancel_before_any_end_releases_once_and_delivers_nothing_later() {
        wait.onCancelled()
        assertEquals(1, releases)
        assertEquals(emptyList(), delivered)
        wait.onDecided("late")
        wait.onGraceElapsed()
        wait.onWillResignActive()
        wait.onDidBecomeActive()
        wait.onCancelled()
        assertEquals(emptyList(), delivered)
        assertEquals(1, releases)
    }

    @Test
    fun cancel_after_the_wait_ended_does_not_release_again() {
        wait.onDecided("done")
        wait.onCancelled()
        assertEquals(1, releases)
        assertEquals(listOf("done"), delivered)
    }

    private companion object {
        const val FALLBACK: String = "fallback"
    }
}
