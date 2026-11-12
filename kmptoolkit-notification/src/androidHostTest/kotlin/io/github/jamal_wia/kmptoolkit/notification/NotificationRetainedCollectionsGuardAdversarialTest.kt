package io.github.jamal_wia.kmptoolkit.notification

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 19 (bounded resources, sibling hunt after R18-S1): every field of the Android notifier, its renderer and the
 * coalescer that can hold a collection is named here with the reason it is bounded. A new retained collection (the
 * R18-S1 pattern: something that grows with the number of distinct inputs) fails this guard until its bound is argued.
 */
class NotificationRetainedCollectionsGuardAdversarialTest {

    private fun collectionFields(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { Modifier.isStatic(it.modifiers) }
        .filter { holdsCollection(it) }
        .map { it.name }
        .toSet()

    private fun holdsCollection(field: Field): Boolean {
        val t: Class<*> = field.type
        return Collection::class.java.isAssignableFrom(t) ||
            Map::class.java.isAssignableFrom(t) ||
            kotlinx.coroutines.flow.StateFlow::class.java.isAssignableFrom(t) ||
            t.isArray
    }

    @Test
    fun `the Android notifier retains only the named bounded collections`() {
        assertEquals(
            setOf(
                "foregroundPlatformIds", // built from the constructor argument once, never grows
                "sweptLegacyIds", // capped at 256 (MAX_SWEPT_LEGACY_IDS), cleared by cancel and cancelAll
                "ensuredChannels", // one entry per channel id, the platform bounds channel ids per package
            ),
            collectionFields(AndroidNotifier::class.java),
        )
    }

    @Test
    fun `the coalescer retains only the five id keyed maps that age out`() {
        assertEquals(
            setOf(
                "posted", // pruned after the stale horizon, cleared by forget and clear, reset by terminal frames
                "latest", // same
                "lastShown", // same
                "inFlight", // same, and settled per ticket
                "claims", // released in a finally, bounded by the posts running at once
            ),
            collectionFields(ProgressCoalescer::class.java),
        )
    }

    @Test
    fun `the renderer holds no collection at all`() {
        assertEquals(emptySet(), collectionFields(NotificationRenderer::class.java))
    }
}
