package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.Callable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The availability check made on the FX thread of an application that has JavaFX graphics but not
 * `javafx.media`: the answer must still be "not available".
 */
class FxThreadWithoutMediaAdversarialTest {

    @Test
    fun `the availability check on the FX thread is false when javafx media is not on the classpath`() {
        val entries: List<String> = System.getProperty("java.class.path").split(File.pathSeparator)
        val kept: List<String> = entries.filterNot { File(it).name.startsWith("javafx-") }
        assertTrue(kept.size < entries.size, "expected the OpenJFX jars on the test classpath: $entries")
        URLClassLoader(kept.map { File(it).toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { isolated ->
            @Suppress("UNCHECKED_CAST")
            val scenario: Callable<String> = isolated
                .loadClass(FxThreadWithoutMediaScenario::class.java.name)
                .getDeclaredConstructor()
                .newInstance() as Callable<String>
            assertEquals("false,false", scenario.call())
        }
    }
}

/** Runs where `javafx.scene.media.MediaPlayer` cannot be loaded, with a runtime that is told it is on the FX thread. */
internal class FxThreadWithoutMediaScenario : Callable<String> {
    override fun call(): String {
        check(runCatching { Class.forName("javafx.scene.media.MediaPlayer", false, javaClass.classLoader) }.isFailure) {
            "javafx.media is visible to the scenario"
        }
        val runtime = ToolkitJavaFxRuntime(startToolkit = { error("the toolkit must not be started") }, onFxThread = { true })
        // Twice: the second answer comes from the remembered failure.
        return "${runtime.isAvailable()},${runtime.isAvailable()}"
    }
}
