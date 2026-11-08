package io.github.jamal_wia.kmptoolkit.systembars.testing

import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverrideHandle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsVisibility
import io.github.jamal_wia.kmptoolkit.systembars.createHeadlessSystemBarsController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The double is documented as a faithful stand-in for the real controller ("The layering is faithful"). The real
 * layering code, driven through the headless controller, is the oracle here: the same random operations, applied
 * to both, must leave the same `config` after every single step: pushes, in-place updates, releases, stale and
 * foreign handles, base writes and the teardown that kills every earlier handle.
 */
class RecordingVsHeadlessDifferentialTest {

    private val icons: List<SystemBarIconStyle?> = listOf(null, SystemBarIconStyle.LightIcons, SystemBarIconStyle.DarkIcons)
    private val visibilities: List<SystemBarsVisibility?> =
        listOf(null, SystemBarsVisibility.Visible, SystemBarsVisibility.Immersive, SystemBarsVisibility.Hidden)

    private fun randomOverride(r: Random): SystemBarsOverride =
        SystemBarsOverride(icons[r.nextInt(3)], icons[r.nextInt(3)], visibilities[r.nextInt(4)])

    private class Pair2(val fake: SystemBarsOverrideHandle, val real: SystemBarsOverrideHandle)

    @Test
    fun `the double and the real layering agree after every step of random operations`() {
        for (seed in 1..400) {
            val r = Random(seed)
            val initial: SystemBarsConfig = if (r.nextBoolean()) SystemBarsConfig() else SystemBarsConfig.ForDarkBackground
            val fake: SystemBarsController = RecordingSystemBarsController(initial)
            val real: SystemBarsController = createHeadlessSystemBarsController(initial)
            val handles: MutableList<Pair2> = mutableListOf()
            val trail = StringBuilder()
            repeat(80) { step ->
                when (r.nextInt(9)) {
                    0, 1, 2 -> {
                        val o: SystemBarsOverride = randomOverride(r)
                        handles += Pair2(fake.applyOverride(o), real.applyOverride(o)); trail.append("push ")
                    }
                    3 -> if (handles.isNotEmpty()) {
                        val h: Pair2 = handles[r.nextInt(handles.size)]; val o: SystemBarsOverride = randomOverride(r)
                        h.fake.update(o); h.real.update(o); trail.append("upd ")
                    }
                    4 -> if (handles.isNotEmpty()) {
                        val h: Pair2 = handles[r.nextInt(handles.size)]
                        h.fake.release(); h.real.release(); trail.append("rel ")
                    }
                    5 -> {
                        val c = SystemBarsConfig(icons[1 + r.nextInt(2)]!!, icons[1 + r.nextInt(2)]!!, visibilities[1 + r.nextInt(3)]!!)
                        fake.setBaseConfig(c); real.setBaseConfig(c); trail.append("base ")
                    }
                    6 -> {
                        val style: SystemBarIconStyle = icons[1 + r.nextInt(2)]!!
                        fake.updateBaseConfig { it.copy(navigationBarIcons = style) }
                        real.updateBaseConfig { it.copy(navigationBarIcons = style) }
                        trail.append("ubase ")
                    }
                    7 -> if (handles.isNotEmpty()) {
                        // twice: a released handle stays harmless
                        val h: Pair2 = handles[r.nextInt(handles.size)]
                        h.fake.release(); h.fake.release(); h.real.release(); h.real.release(); trail.append("rel2 ")
                    }
                    else -> if (r.nextInt(5) == 0) { fake.release(); real.release(); trail.append("TEARDOWN ") }
                }
                assertEquals(real.config.value, fake.config.value, "seed $seed step $step after: $trail")
            }
        }
    }
}
