package io.github.jamal_wia.kmptoolkit.notification

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The coalescer judges "nothing but the percentage changed" with `==` on the frame and on the options,
 * so equality is the contract's definition of "the same frame". A seeded sweep: two values built
 * field by field from the same random choices through *different* collection implementations (an
 * ArrayList against a listOf, a LinkedHashMap against a HashMap, a view against its source) are equal
 * and hash alike, symmetric and reflexive, and a single changed field makes them unequal, for every
 * field. A frame and its copy with another progress differ only in progress.
 */
class NotificationEqualityFuzzAdversarialTest {

    private val specs: List<NotificationChannelSpec> = listOf(
        NotificationChannelSpec("c0", "C0"),
        NotificationChannelSpec("c1", "C1", description = "d", importance = NotificationImportance.High, sound = NotificationSound.Custom("ding")),
    )

    private fun actionsOf(random: Random): List<NotificationAction> =
        List(random.nextInt(0, 4)) { NotificationAction("id${random.nextInt(3)}", "label${random.nextInt(3)}") }

    private fun extrasOf(random: Random): Map<String, String>? =
        if (random.nextInt(3) == 0) null else (0 until random.nextInt(0, 4)).associate { "k${random.nextInt(4)}" to "v${random.nextInt(3)}" }

    private fun iconsOf(random: Random): Map<String, NotificationIcon> =
        (0 until random.nextInt(0, 3)).associate { "a${random.nextInt(3)}" to NotificationIcon.AndroidDrawable(random.nextInt(3)) }

    @Test
    fun `equal frames built through different collection implementations are equal and hash alike`() {
        repeat(5_000) { i: Int ->
            val seed = i.toLong()
            fun build(variant: Int): LocalNotification {
                val random = Random(seed)
                val actions: List<NotificationAction> = actionsOf(random)
                val extras: Map<String, String>? = extrasOf(random)
                val progress: NotificationProgress? = when (random.nextInt(3)) {
                    0 -> null
                    1 -> NotificationProgress.Indeterminate
                    else -> NotificationProgress.Determinate(random.nextInt(0, 101))
                }
                val channel: NotificationChannelSpec = specs[random.nextInt(specs.size)]
                return LocalNotification(
                    title = "t${random.nextInt(2)}", body = "b${random.nextInt(2)}", channel = channel,
                    progress = progress, ongoing = random.nextBoolean(), autoCancel = random.nextBoolean(),
                    actions = when (variant) {
                        0 -> actions
                        1 -> ArrayList(actions)
                        else -> actions.asReversed().asReversed()
                    },
                    iosCategoryId = if (random.nextBoolean()) "cat" else null,
                    contentExtras = when (variant) {
                        0 -> extras
                        1 -> extras?.let { LinkedHashMap(it) }
                        else -> extras?.entries?.reversed()?.associate { it.key to it.value }
                    },
                )
            }
            val a: LocalNotification = build(0)
            val b: LocalNotification = build(1)
            val c: LocalNotification = build(2)
            assertEquals(a, a, "iteration $i: reflexive")
            assertEquals(a, b, "iteration $i")
            assertEquals(b, a, "iteration $i: symmetric")
            assertEquals(a, c, "iteration $i")
            assertEquals(a.hashCode(), b.hashCode(), "iteration $i: hash")
            assertEquals(a.hashCode(), c.hashCode(), "iteration $i: hash")
        }
    }

    @Test
    fun `options built through different map implementations are equal and hash alike and any one field breaks equality`() {
        repeat(5_000) { i: Int ->
            val random = Random(i)
            val icons: Map<String, NotificationIcon> = iconsOf(random)
            val dismiss: NotificationAction? = if (random.nextBoolean()) NotificationAction("d", "Dismiss") else null
            val alertOnce: Boolean = random.nextBoolean()
            val media: Boolean = random.nextBoolean()
            val a = NotificationOptions(alertOnce, dismiss, media, icons)
            val b = NotificationOptions(alertOnce, dismiss, media, HashMap(icons))
            val c = NotificationOptions(alertOnce, dismiss, media, icons.entries.reversed().associate { it.key to it.value })
            assertEquals(a, a)
            assertEquals(a, b, "iteration $i")
            assertEquals(b, a, "iteration $i: symmetric")
            assertEquals(a, c, "iteration $i")
            assertEquals(a.hashCode(), b.hashCode(), "iteration $i: hash")
            assertEquals(a.hashCode(), c.hashCode(), "iteration $i: hash")
            assertNotEquals(a, NotificationOptions(!alertOnce, dismiss, media, icons), "iteration $i: alertOnce")
            assertNotEquals(a, NotificationOptions(alertOnce, dismiss, !media, icons), "iteration $i: mediaStyle")
            assertNotEquals(
                a, NotificationOptions(alertOnce, if (dismiss == null) NotificationAction("d", "Dismiss") else null, media, icons),
                "iteration $i: dismissAction",
            )
            assertNotEquals(a, NotificationOptions(alertOnce, dismiss, media, icons + ("zz" to NotificationIcon.Default)), "iteration $i: icons")
        }
    }

    @Test
    fun `a frame and its copy with another progress differ in nothing else`() {
        repeat(2_000) { i: Int ->
            val random = Random(i)
            val frame = LocalNotification(
                title = "t", body = "b", channel = specs[random.nextInt(specs.size)],
                progress = NotificationProgress.Determinate(random.nextInt(0, 100)),
                actions = actionsOf(random), contentExtras = extrasOf(random),
            )
            val other: LocalNotification = frame.copy(progress = NotificationProgress.Determinate(random.nextInt(0, 100)))
            assertEquals(frame.copy(progress = null), other.copy(progress = null), "iteration $i")
            assertEquals(frame.copy(progress = null).hashCode(), other.copy(progress = null).hashCode(), "iteration $i")
        }
    }
}
