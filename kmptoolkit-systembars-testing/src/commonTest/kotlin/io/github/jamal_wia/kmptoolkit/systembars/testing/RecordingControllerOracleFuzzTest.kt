package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverrideHandle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsVisibility
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The recording controller against a naive oracle written from the documented layering rules: overrides
 * stack, the newest wins a shared axis, an override claims only the axes it names, an update keeps the
 * layer's position, a release restores what is underneath at that moment, a released or torn-down handle
 * is dead for good, `applied` records only real changes, and `release()` moves `config` but never `applied`.
 */
class RecordingControllerOracleFuzzTest {

    private class Layer(val handle: SystemBarsOverrideHandle, var override: SystemBarsOverride)

    private val icons = listOf(null, SystemBarIconStyle.LightIcons, SystemBarIconStyle.DarkIcons)
    private val visibilities = listOf(null, SystemBarsVisibility.Visible, SystemBarsVisibility.Immersive, SystemBarsVisibility.Hidden)

    private fun randomOverride(r: Random) =
        SystemBarsOverride(icons[r.nextInt(3)], icons[r.nextInt(3)], visibilities[r.nextInt(4)])

    private fun fold(base: SystemBarsConfig, layers: List<Layer>): SystemBarsConfig =
        layers.fold(base) { c, l ->
            SystemBarsConfig(
                statusBarIcons = l.override.statusBarIcons ?: c.statusBarIcons,
                navigationBarIcons = l.override.navigationBarIcons ?: c.navigationBarIcons,
                visibility = l.override.visibility ?: c.visibility,
            )
        }

    @Test
    fun `random pushes updates releases and teardown match the oracle`() {
        for (seed in 1..300) {
            val r = Random(seed)
            val controller = RecordingSystemBarsController()
            var base = SystemBarsConfig()
            val layers = mutableListOf<Layer>()
            val dead = mutableListOf<Pair<SystemBarsOverrideHandle, SystemBarsOverride>>()
            val expectedApplied = mutableListOf<SystemBarsConfig>()
            var effective = base
            val log = StringBuilder()
            repeat(60) { step ->
                var teardown = false
                when (r.nextInt(8)) {
                    0, 1 -> { val o = randomOverride(r); layers += Layer(controller.applyOverride(o), o); log.append("push ") }
                    2 -> if (layers.isNotEmpty()) { val l = layers[r.nextInt(layers.size)]; val o = randomOverride(r); l.handle.update(o); l.override = o; log.append("upd ") }
                    3 -> if (layers.isNotEmpty()) { val l = layers.removeAt(r.nextInt(layers.size)); l.handle.release(); dead += l.handle to l.override; log.append("rel ") }
                    4 -> { base = SystemBarsConfig(icons[1 + r.nextInt(2)]!!, icons[1 + r.nextInt(2)]!!, visibilities[1 + r.nextInt(3)]!!); controller.setBaseConfig(base); log.append("base ") }
                    5 -> { controller.updateBaseConfig { it.copy(statusBarIcons = SystemBarIconStyle.LightIcons) }; base = base.copy(statusBarIcons = SystemBarIconStyle.LightIcons); log.append("ubase ") }
                    6 -> if (dead.isNotEmpty()) { val (h, _) = dead[r.nextInt(dead.size)]; h.update(randomOverride(r)); h.release(); log.append("dead ") }
                    else -> if (r.nextInt(6) == 0) {
                        controller.release(); layers.forEach { dead += it.handle to it.override }; layers.clear(); base = SystemBarsConfig(); teardown = true; log.append("TEARDOWN ")
                    }
                }
                val now = fold(base, layers)
                if (!teardown && now != effective) expectedApplied += now
                effective = now
                assertEquals(now, controller.config.value, "seed $seed step $step ($log): config")
                assertEquals(layers.size, controller.activeOverrideCount, "seed $seed step $step ($log): layers")
                assertEquals(expectedApplied, controller.applied, "seed $seed step $step ($log): applied")
            }
        }
    }
}
