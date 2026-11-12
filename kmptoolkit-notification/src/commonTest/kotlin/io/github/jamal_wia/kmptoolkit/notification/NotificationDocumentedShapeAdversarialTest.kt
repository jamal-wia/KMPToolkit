package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/**
 * Round 15 (contract lawyer): the declarations printed in docs/kmptoolkit-notification/04-api-reference.md,
 * constructed positionally in the printed order with the printed defaults. A reordered parameter or a
 * changed default compiles for named callers and silently changes every positional one; the docs are
 * the contract. (Runs on the JVM and on iOS.)
 */
class NotificationDocumentedShapeAdversarialTest {

    private val channel = NotificationChannelSpec("id", "name")

    @Test
    fun `LocalNotification positional order and defaults are the printed ones`() {
        val actions: List<NotificationAction> = listOf(NotificationAction("a", "A"))
        val extras: Map<String, String> = mapOf("k" to "v")
        val full = LocalNotification(
            "title", "body", channel, NotificationIcon.AndroidDrawable(5), NotificationProgress.Determinate(7),
            true, false, actions, "cat", extras,
        )
        assertEquals("title", full.title)
        assertEquals("body", full.body)
        assertSame(channel, full.channel)
        assertEquals(NotificationIcon.AndroidDrawable(5), full.icon)
        assertEquals(NotificationProgress.Determinate(7), full.progress)
        assertEquals(true, full.ongoing)
        assertEquals(false, full.autoCancel)
        assertEquals(actions, full.actions)
        assertEquals("cat", full.iosCategoryId)
        assertEquals(extras, full.contentExtras)

        val minimal = LocalNotification("t", "b", channel)
        assertEquals(NotificationIcon.Default, minimal.icon)
        assertNull(minimal.progress)
        assertEquals(false, minimal.ongoing)
        assertEquals(true, minimal.autoCancel)
        assertEquals(emptyList(), minimal.actions)
        assertNull(minimal.iosCategoryId)
        assertNull(minimal.contentExtras, "null (not an empty map) is what means: does nothing when tapped")
    }

    @Test
    fun `NotificationChannelSpec NotificationOptions and NotificationConfig defaults are the printed ones`() {
        val spec = NotificationChannelSpec("i", "n")
        assertEquals("", spec.description)
        assertEquals(NotificationImportance.Default, spec.importance)
        assertEquals(NotificationSound.Default, spec.sound)
        val positional = NotificationChannelSpec("i", "n", "d", NotificationImportance.High, NotificationSound.Custom("beep"))
        assertEquals("d", positional.description)
        assertEquals(NotificationImportance.High, positional.importance)
        assertEquals(NotificationSound.Custom("beep"), positional.sound)

        val stop = NotificationAction("stop", "Stop")
        val options = NotificationOptions(false, stop, true, mapOf("stop" to NotificationIcon.Default))
        assertEquals(false, options.alertOnce)
        assertEquals(stop, options.dismissAction)
        assertEquals(true, options.mediaStyle)
        assertEquals(mapOf("stop" to NotificationIcon.Default), options.actionIcons)
        val defaults = NotificationOptions()
        assertEquals(true, defaults.alertOnce, "documented: alertOnce = true")
        assertNull(defaults.dismissAction)
        assertEquals(false, defaults.mediaStyle)
        assertEquals(emptyMap(), defaults.actionIcons)
        assertEquals(NotificationOptions.DEFAULT, defaults, "the two-argument post is the same as passing DEFAULT")

        val config = NotificationConfig()
        assertNull(config.actionBroadcastAction)
        assertEquals(10, config.progressBucketPercent)
        assertEquals(500.milliseconds, config.minProgressInterval)
        assertEquals(10, NotificationConfig.DEFAULT_PROGRESS_BUCKET_PERCENT)
        assertEquals(500.milliseconds, NotificationConfig.DEFAULT_MIN_PROGRESS_INTERVAL)
        val positionalConfig = NotificationConfig("my.ACTION", 25, 1.milliseconds)
        assertEquals("my.ACTION", positionalConfig.actionBroadcastAction)
        assertEquals(25, positionalConfig.progressBucketPercent)
        assertEquals(1.milliseconds, positionalConfig.minProgressInterval)
    }

    @Test
    fun `NotificationImportance and the result cases are exactly the printed ones`() {
        assertEquals(listOf("Low", "Default", "High"), NotificationImportance.entries.map { it.name })
        val results: List<NotificationResult> = listOf(
            NotificationResult.Posted, NotificationResult.Coalesced, NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled, NotificationResult.ChannelBlocked("c"), NotificationResult.Failed(null),
        )
        val names: List<String> = results.map { result: NotificationResult ->
            when (result) {
                NotificationResult.Posted -> "Posted"
                NotificationResult.Coalesced -> "Coalesced"
                NotificationResult.PermissionDenied -> "PermissionDenied"
                NotificationResult.NotificationsDisabled -> "NotificationsDisabled"
                is NotificationResult.ChannelBlocked -> "ChannelBlocked"
                is NotificationResult.Failed -> "Failed"
            }
        }
        assertEquals(6, names.toSet().size)
        assertEquals("c", (results[4] as NotificationResult.ChannelBlocked).channelId)
        assertNull((results[5] as NotificationResult.Failed).cause)
    }

    @Test
    fun `the documented validation messages never leak into a blank-tolerant field`() {
        // "Throws IllegalArgumentException if id or name is blank" - and only those: description may be blank.
        assertFailsWith<IllegalArgumentException> { NotificationChannelSpec(" ", "n") }
        assertFailsWith<IllegalArgumentException> { NotificationChannelSpec("i", "\t") }
        assertEquals("", NotificationChannelSpec("i", "n", description = " ").description.trim())
        // "Throws IllegalArgumentException on a blank id" for an action - the label is not validated.
        assertFailsWith<IllegalArgumentException> { NotificationAction("", "x") }
        assertEquals("", NotificationAction("a", "").label)
        // "Custom throws IllegalArgumentException on a blank name."
        assertFailsWith<IllegalArgumentException> { NotificationSound.Custom(" ") }
        // Title and body are not validated by the contract (a decorative empty title is legal).
        assertEquals("", LocalNotification("", "", channel).title)
    }
}
