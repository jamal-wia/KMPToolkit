package io.github.jamal_wia.kmptoolkit.hardwarekeys

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The module's build file and docs/kmptoolkit-hardware-keys/05-platform-notes.md say the effect is a no-op on
 * iOS (no hardware-key dispatch to intercept). No source set exercised the iOS actual before: composing it with a
 * bare Compose runtime must work, touch nothing, and survive a thousand compose-and-dispose cycles.
 */
class IosNoOpEffectTest {

    private class CountingApplier : AbstractApplier<Unit>(Unit) {
        var operations: Int = 0
        override fun insertTopDown(index: Int, instance: Unit) { operations++ }
        override fun insertBottomUp(index: Int, instance: Unit) { operations++ }
        override fun remove(index: Int, count: Int) { operations++ }
        override fun move(from: Int, to: Int, count: Int) { operations++ }
        override fun onClear() { }
    }

    @Test
    fun `the iOS effect composes without a window and changes no node`() {
        val applier: CountingApplier = CountingApplier()
        val recomposer: Recomposer = Recomposer(EmptyCoroutineContext)
        val composition: Composition = Composition(applier, recomposer)
        composition.setContent { DialogWindowHardwareKeyEffect() }
        composition.dispose()
        recomposer.close()
        assertEquals(0, applier.operations, "the no-op effect emitted a node")
    }

    @Test
    fun `a thousand compose and dispose cycles of the iOS effect do not fail`() {
        repeat(1000) {
            val recomposer: Recomposer = Recomposer(EmptyCoroutineContext)
            val composition: Composition = Composition(CountingApplier(), recomposer)
            composition.setContent {
                DialogWindowHardwareKeyEffect()
                DialogWindowHardwareKeyEffect()
            }
            composition.dispose()
            recomposer.close()
        }
    }
}
