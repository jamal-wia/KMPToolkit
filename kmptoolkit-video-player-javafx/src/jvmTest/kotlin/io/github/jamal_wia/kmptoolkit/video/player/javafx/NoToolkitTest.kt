package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What holds whether or not JavaFX can run here — none of these tests is skipped headless.
 */
class NoToolkitTest {

    @Test
    fun startingTheToolkitNeverBlocksTheThreadThatCalledLoad() {
        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val caller: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
        val callerThread: Thread = executor.submit(Callable { Thread.currentThread() }).get()
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var startedOn: Thread? = null
        // Stands in for the first start of the toolkit, which can take seconds.
        val slowRuntime = JavaFxRuntime {
            startedOn = Thread.currentThread()
            entered.countDown()
            proceed.await(10, TimeUnit.SECONDS)
            throw JavaFxVideoPlayerException.RuntimeUnavailable(IllegalStateException("no display"))
        }
        val engine = JavaFxVideoEngine(runtime = slowRuntime)
        try {
            runBlocking {
                val load = async(caller) { runCatching { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "the runtime was never asked to start")

                // The caller's thread (a UI thread, in an app) is free while the toolkit starts.
                withTimeout(2_000L) { withContext(caller) { } }

                proceed.countDown()
                assertIs<JavaFxVideoPlayerException.RuntimeUnavailable>(load.await().exceptionOrNull())
            }
            assertNotSame(callerThread, startedOn)
            assertEquals(0L, engine.durationMs())
        } finally {
            proceed.countDown()
            engine.release()
            caller.close()
        }
    }

    @Test
    fun onAClasspathWithoutOpenJfxThePlayerIsCreatedAndPrepareReportsTheRuntimeUnavailable() {
        val entries: List<String> = System.getProperty("java.class.path").split(File.pathSeparator)
        val kept: List<String> = entries.filterNot { File(it).name.startsWith("javafx-") }
        assertTrue(kept.size < entries.size, "expected the OpenJFX jars on the test classpath: $entries")

        // Child of the platform loader, not of the test's: nothing here can reach an OpenJFX class.
        URLClassLoader(
            kept.map { File(it).toURI().toURL() }.toTypedArray(),
            ClassLoader.getPlatformClassLoader(),
        ).use { isolated ->
            @Suppress("UNCHECKED_CAST")
            val scenario: Callable<String> = isolated
                .loadClass(NoJavaFxScenario::class.java.name)
                .getDeclaredConstructor()
                .newInstance() as Callable<String>
            // kotlin.test finds its asserter, and the engine its assets, through the context loader.
            val thread: Thread = Thread.currentThread()
            val previous: ClassLoader? = thread.contextClassLoader
            thread.contextClassLoader = isolated
            try {
                assertEquals(NoJavaFxScenario.PASSED, scenario.call())
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }
}
