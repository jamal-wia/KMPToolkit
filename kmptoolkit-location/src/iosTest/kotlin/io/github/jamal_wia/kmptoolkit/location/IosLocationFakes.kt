@file:OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.location

import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse

/**
 * A dispatcher over a test dispatcher that knows whether a task of it is running right now: the
 * stand-in for "the main queue", so a fake can record whether it was called on it. It forwards
 * [Delay], so `withTimeoutOrNull` inside it runs on the test scheduler's virtual time.
 */
internal class TrackedDispatcher(private val inner: TestDispatcher) : CoroutineDispatcher(), Delay by inner {
    var depth: Int = 0
        private set

    val isRunning: Boolean get() = depth > 0

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = inner.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        inner.dispatch(
            context,
            Runnable {
                depth++
                try {
                    block.run()
                } finally {
                    depth--
                }
            },
        )
    }
}

internal class FakeIosClock : IosClock {
    var wallMillis: Long = 1_000_000_000_000L
    var uptimeValue: Duration = 100_000.seconds

    override fun epochMillis(): Long = wallMillis

    override fun uptime(): Duration = uptimeValue

    /** Time passes: both clocks advance. */
    fun advance(by: Duration) {
        wallMillis += by.inWholeMilliseconds
        uptimeValue += by
    }

    /** The wall clock is corrected by [by]; the uptime does not move. */
    fun jumpWall(by: Duration) {
        wallMillis += by.inWholeMilliseconds
    }
}

/** A raw fix [age] old on [clock], with sane values; every field can be overridden. */
internal fun rawFix(
    clock: FakeIosClock,
    age: Duration = 0.milliseconds,
    latitude: Double = 21.4225,
    longitude: Double = 39.8262,
    horizontalAccuracy: Double = 5.0,
    verticalAccuracy: Double = 3.0,
    altitude: Double = 12.5,
    ellipsoidalAltitude: Double? = 40.0,
    speed: Double = 1.5,
    speedAccuracy: Double = 0.5,
    course: Double = 90.0,
    courseAccuracy: Double? = 4.0,
    floorLevel: Int? = null,
    isSimulated: Boolean? = false,
    isProducedByAccessory: Boolean? = false,
): IosRawFix = IosRawFix(
    latitude = latitude,
    longitude = longitude,
    timestampEpochMillis = clock.wallMillis - age.inWholeMilliseconds,
    horizontalAccuracy = horizontalAccuracy,
    altitude = altitude,
    ellipsoidalAltitude = ellipsoidalAltitude,
    verticalAccuracy = verticalAccuracy,
    speed = speed,
    speedAccuracy = speedAccuracy,
    course = course,
    courseAccuracy = courseAccuracy,
    floorLevel = floorLevel,
    isSimulated = isSimulated,
    isProducedByAccessory = isProducedByAccessory,
)

/**
 * A scripted manager: every call is counted, the delegate is the [events] object the provider passed,
 * and like a real manager whose delegate was set to nil it delivers nothing after [detach] (a test that
 * wants a late callback in flight calls [events] itself).
 */
internal class FakeIosLocationManager(
    private val env: FakeIosLocationEnvironment,
    val events: IosLocationEvents,
) : IosLocationManager {
    val applied: MutableList<IosManagerConfig> = mutableListOf()
    var startCount: Int = 0
    var stopCount: Int = 0
    var requestCount: Int = 0
    var detachCount: Int = 0
    var updating: Boolean = false
    val createdOnMain: Boolean = env.main.isRunning
    var detachedOnMain: Boolean? = null

    val detached: Boolean get() = detachCount > 0

    override val authorizationStatus: Int
        get() {
            env.managerStatusReads++
            if (!env.main.isRunning) env.managerStatusReadsOffMain++
            return env.status
        }
    override val isAccuracyReduced: Boolean get() = env.reduced
    override val lastLocation: IosRawFix? get() = env.cached

    override fun apply(config: IosManagerConfig) {
        applied += config
    }

    override fun startUpdatingLocation() {
        startCount++
        updating = true
    }

    override fun stopUpdatingLocation() {
        stopCount++
        updating = false
    }

    override fun requestLocation() {
        requestCount++
    }

    override fun detach() {
        if (detachCount == 0) detachedOnMain = env.main.isRunning
        detachCount++
        updating = false
    }

    /** CoreLocation delivers [fixes], unless the delegate was already set to nil. */
    fun deliver(vararg fixes: IosRawFix) {
        if (!detached) events.onLocations(fixes.toList())
    }

    fun fail(kind: IosFailureKind, description: String = "scripted") {
        if (!detached) events.onFailure(kind, description)
    }

    fun authorizationChanged() {
        if (!detached) events.onAuthorizationChanged()
    }

    fun pause() {
        if (!detached) events.onPaused()
    }

    fun resume() {
        if (!detached) events.onResumed()
    }
}

/** The scripted platform; see [IosLocationEnvironment]. */
internal class FakeIosLocationEnvironment(scheduler: TestCoroutineScheduler) : IosLocationEnvironment {
    val main: TrackedDispatcher = TrackedDispatcher(StandardTestDispatcher(scheduler, name = "main"))
    override val mainDispatcher: CoroutineDispatcher = main
    override val offMainDispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler, name = "offMain")
    override val clock: FakeIosClock = FakeIosClock()
    override val timeSource: kotlin.time.TimeSource = scheduler.timeSource

    var status: Int = kCLAuthorizationStatusAuthorizedWhenInUse
    var servicesOn: Boolean = true
    var reduced: Boolean = false
    var cached: IosRawFix? = null
    var backgroundMode: Boolean = false
    var servicesReads: Int = 0
    var statusReads: Int = 0

    /** Authorization status reads made while no task of the main dispatcher was running. */
    var statusReadsOffMain: Int = 0

    /**
     * Reads of the `authorizationStatus` property of a manager that already exists (the re-read of a
     * running stream takes this path, not [authorizationStatus]), and those made off the main dispatcher.
     */
    var managerStatusReads: Int = 0
    var managerStatusReadsOffMain: Int = 0
    var settingsOpened: Int = 0
    val managers: MutableList<FakeIosLocationManager> = mutableListOf()
    val activeListeners: MutableList<() -> Unit> = mutableListOf()
    var unregistered: Int = 0

    /** Every did-become-active registration ever made, so a test can balance it against [unregistered]. */
    var registered: Int = 0

    val liveManagers: List<FakeIosLocationManager> get() = managers.filter { !it.detached }

    override fun authorizationStatus(): Int {
        statusReads++
        if (!main.isRunning) statusReadsOffMain++
        return status
    }

    override fun locationServicesEnabled(): Boolean {
        servicesReads++
        return servicesOn
    }

    override fun hasLocationBackgroundMode(): Boolean = backgroundMode

    override fun createManager(events: IosLocationEvents): IosLocationManager {
        val manager = FakeIosLocationManager(this, events)
        managers += manager
        return manager
    }

    override fun addBecameActiveListener(onActive: () -> Unit): () -> Unit {
        registered++
        activeListeners += onActive
        return {
            if (activeListeners.remove(onActive)) unregistered++
        }
    }

    override fun openAppSettings() {
        settingsOpened++
    }

    /** The app returns to the foreground. */
    fun activate() {
        activeListeners.toList().forEach { listener: () -> Unit -> listener() }
    }

    /** Every authorization-change callback of every live manager: the status or the services switch changed. */
    fun announceChange() {
        liveManagers.forEach { it.authorizationChanged() }
    }
}
