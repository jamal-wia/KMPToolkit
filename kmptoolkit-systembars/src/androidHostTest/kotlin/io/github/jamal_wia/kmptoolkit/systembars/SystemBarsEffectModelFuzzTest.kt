package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Four SystemBarsEffects entering, leaving and changing their override in random order (one change
 * per frame) while the base changes: the effective configuration is always the base with the live
 * claims folded on top in the order they entered, and nothing is left once all have left.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SystemBarsEffectModelFuzzTest {

    private class Slot(var shown: Boolean, var status: SystemBarIconStyle?, var nav: SystemBarIconStyle?, var entered: Long)

    @Test
    fun `random enter leave and override changes keep the effective configuration equal to the model`() {
        for (seed in 1..40) {
            runComposeUiTest {
                val random = Random(seed)
                val controller = RecordingSystemBarsController(SystemBarsConfig.ForLightBackground)
                val shown = mutableStateListOf(false, false, false, false)
                val status = mutableStateListOf<SystemBarIconStyle?>(null, null, null, null)
                val nav = mutableStateListOf<SystemBarIconStyle?>(null, null, null, null)
                val model: List<Slot> = List(4) { Slot(false, null, null, 0L) }
                var base: SystemBarsConfig = SystemBarsConfig.ForLightBackground
                var clock = 0L
                setContent {
                    for (i in 0 until 4) {
                        if (shown[i]) SystemBarsEffect(controller, statusBarIcons = status[i], navigationBarIcons = nav[i])
                    }
                }
                waitForIdle()
                fun pick(): SystemBarIconStyle? = when (random.nextInt(3)) {
                    0 -> null
                    1 -> SystemBarIconStyle.DarkIcons
                    else -> SystemBarIconStyle.LightIcons
                }
                val log = StringBuilder()
                repeat(60) { step ->
                    val i: Int = random.nextInt(4)
                    when (random.nextInt(4)) {
                        0 -> if (!model[i].shown) { shown[i] = true; model[i].shown = true; model[i].entered = ++clock; log.append("in$i ") }
                        1 -> if (model[i].shown) { shown[i] = false; model[i].shown = false; log.append("out$i ") }
                        2 -> { val s = pick(); val n = pick(); status[i] = s; nav[i] = n; model[i].status = s; model[i].nav = n; log.append("chg$i ") }
                        else -> {
                            base = SystemBarsConfig(
                                if (random.nextBoolean()) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons,
                                if (random.nextBoolean()) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons,
                            )
                            controller.setBaseConfig(base); log.append("base ")
                        }
                    }
                    waitForIdle()
                    var expected: SystemBarsConfig = base
                    model.filter { it.shown }.sortedBy { it.entered }.forEach { slot ->
                        expected = SystemBarsOverride(slot.status, slot.nav).applyTo(expected)
                    }
                    assertEquals(expected, controller.currentConfig, "seed $seed step $step ($log)")
                    assertEquals(model.count { it.shown }, controller.activeOverrideCount, "seed $seed step $step ($log): layers")
                }
                for (i in 0 until 4) shown[i] = false
                waitForIdle()
                assertEquals(0, controller.activeOverrideCount, "seed $seed: layers left after all effects left")
            }
        }
    }
}
