@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class, NativeRuntimeApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 11, bounded resources on iOS: thousands of controllers over thousands of ports, each observed, requested
 * and cancelled with its prompt up, leave no listener on a port and are collectable once dropped; the warn-once
 * set of one controller stays at the number of distinct purpose strings however often a status is read.
 */
class IosBoundedResourcesRound11Test {

    private val entries: List<Permission> = CommonPermission.entries + IosPermission.entries

    private fun TestScope.churn(): WeakReference<IosPermissionController> {
        val port = ChaosIosAuthorizationPort()
        val controller = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)
        val observers: List<Job> = entries.map { entry -> launch { controller.observe(entry).collect { } } }
        runCurrent()
        port.hold.value = true
        val requests: List<Job> = listOf(CommonPermission.Camera, CommonPermission.Microphone).map { entry ->
            launch { controller.request(entry) }
        }
        runCurrent()
        requests.forEach { it.cancel() }
        observers.forEach { it.cancel() }
        runCurrent()
        assertEquals(0, port.liveListeners, "a listener outlived its observer")
        return WeakReference(controller)
    }

    @Test
    fun `three thousand controllers observed and cancelled with a prompt up leave nothing registered and are collectable`() = runTest {
        val weak: MutableList<WeakReference<IosPermissionController>> = mutableListOf()
        repeat(3000) { round -> churn().also { if (round % 100 == 0) weak += it } }
        GC.collect()
        GC.collect()
        weak.forEachIndexed { index, reference -> assertNull(reference.value, "controller $index of the churn is still reachable") }
    }

    @Test
    fun `one controller reading every entry ten thousand times with every purpose string missing reports each key once`() = runTest {
        val logger = ListLogger()
        val controller = IosPermissionController(logger, IosPermissionOptions(), { false }, ChaosIosAuthorizationPort())
        repeat(10_000) { round -> controller.check(entries[round % entries.size]) }
        val distinct: Int = logger.messages.toSet().size
        assertEquals(distinct, logger.messages.size, "a missing purpose string was reported more than once")
        assertEquals(true, logger.messages.size <= entries.size, "more warnings than entries: ${logger.messages.size}")
    }
}
