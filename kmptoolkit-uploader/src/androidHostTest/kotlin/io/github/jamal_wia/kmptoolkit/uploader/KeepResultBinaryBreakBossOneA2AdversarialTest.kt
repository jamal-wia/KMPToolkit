package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * A2 boss round 1: the `Breaking` note of CHANGELOG 5.0.0 says that on Android and the JVM a custom `UploaderStore`
 * compiled against 4.x still links (the erased signature of the suspend `insertKeep` is `Object insertKeep(UploaderItem,
 * Continuation)` in both versions) and then throws a `ClassCastException` on every enqueue with `ConflictPolicy.KEEP`,
 * and that under `TransactionRunner.Direct` its row may already be written by then.
 *
 * A JVM dynamic proxy is that old binary exactly: it implements the interface by its erased methods, so its
 * `insertKeep` can answer a boxed `Boolean` the way a 4.x class did, with no Kotlin cast on its own side. Every other
 * call goes to a real store.
 */
class KeepResultBinaryBreakBossOneA2AdversarialTest {

    private fun oldBinaryStore(backing: TestUploaderStore, answer: Boolean): UploaderStore {
        val handler = InvocationHandler { _: Any, method: Method, args: Array<out Any?>? ->
            val arguments: Array<out Any?> = args ?: emptyArray()
            if (method.name == "insertKeep") {
                // The 4.x body: insert unless a holder exists, answer whether it inserted, as a boxed Boolean.
                val record = arguments[0] as UploaderItem
                if (backing.items.none { it.uniqueKey != null && it.uniqueKey == record.uniqueKey }) {
                    runBlocking { backing.insertReplace(record) }
                }
                return@InvocationHandler answer
            }
            try {
                method.invoke(backing, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        return Proxy.newProxyInstance(
            UploaderStore::class.java.classLoader,
            arrayOf(UploaderStore::class.java),
            handler,
        ) as UploaderStore
    }

    @Test
    fun `a store compiled to the old Boolean insertKeep links and fails every KEEP enqueue with a ClassCastException`() = runTest {
        for (answer in listOf(true, false)) {
            val backing = TestUploaderStore()
            val handler = TestHandler()
            val engine: UploaderEngine = testEngine(oldBinaryStore(backing, answer), listOf(handler), backgroundScope)

            val thrown: Throwable? = runCatching { engine.enqueue(handler, "p", uniqueKey = "k") }.exceptionOrNull()

            assertTrue(
                thrown is ClassCastException,
                "old insertKeep answering $answer: expected the documented ClassCastException, got $thrown",
            )
            // The note's second sentence: under TransactionRunner.Direct the row may already be written.
            assertEquals(1, backing.items.size, "the old store wrote its row before the engine read the answer")
        }
    }

    @Test
    fun `control a REPLACE enqueue over the same old store is not affected`() = runTest {
        val backing = TestUploaderStore()
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(oldBinaryStore(backing, true), listOf(handler), backgroundScope)

        val id: String? = engine.enqueue(handler, "p", uniqueKey = "k", conflictPolicy = ConflictPolicy.REPLACE)

        assertEquals("item-1", id)
        assertEquals(1, backing.items.size)
    }
}
