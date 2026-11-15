package io.github.jamal_wia.kmptoolkit.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

/**
 * A scriptable [LocationBackend] for the provider tests: the framework `LocationManager` cannot be
 * subclassed, and these tests must control the order of a registration against a delivery, a provider
 * flap, the boot clock and every refusal. It is the stand-in for the main looper too: a test calls
 * [deliver] (or flips [setProviderEnabled]) from its own thread, exactly where the platform would call
 * the listener.
 *
 * Every registration is recorded and counted, so a test proves release with a counter: [registeredCount]
 * must read what the test expects (control: more than zero while the call is pending), then zero.
 * Thread-safe: the storm tests drive it from several threads.
 */
internal class FakeLocationBackend(
    providers: Set<String> = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    ),
    /** Whether [requestCurrentLocation] exists (API 30 and later); `false` answers `null` like API 24-29. */
    @Volatile var supportsCurrentLocation: Boolean = false,
) : LocationBackend {

    /** One live `requestUpdates` registration. */
    class Registration(val provider: String, val request: UpdatesRequest, val listener: LocationListener)

    /** One framework one-shot call. */
    class CurrentCall(val provider: String, val accuracy: LocationAccuracy, val onResult: (Location?) -> Unit) {
        @Volatile var cancelled: Boolean = false
    }

    private val lock = Any()
    private val present: MutableSet<String> = providers.toMutableSet()
    private val enabled: MutableMap<String, Boolean> = providers.associateWith { true }.toMutableMap()
    private val cache: MutableMap<String, Location> = mutableMapOf()
    private val registrations: MutableList<Registration> = mutableListOf()
    private val subscribers: MutableList<() -> Unit> = mutableListOf()
    private val refusing: MutableSet<String> = mutableSetOf()
    private val failing: MutableMap<String, Throwable> = mutableMapOf()
    private val calls: MutableList<CurrentCall> = mutableListOf()
    private val registrationAttempts: AtomicInteger = AtomicInteger(0)
    private val removalCalls: AtomicInteger = AtomicInteger(0)

    @Volatile var locationEnabled: Boolean = true
    @Volatile var fine: Boolean = true
    @Volatile var coarse: Boolean = true

    /** The boot clock; tests advance it by hand. */
    @Volatile var nowNanos: Long = 10_000_000_000_000L

    /** Throws from every `removeUpdates` when set (a dead system server). */
    @Volatile var removalFailure: Throwable? = null

    /** Registrations that are live now. */
    val registeredCount: Int get() = synchronized(lock) { registrations.size }

    /** A snapshot of the live registrations. */
    fun registered(): List<Registration> = synchronized(lock) { registrations.toList() }

    /** How many `requestUpdates` calls were made, refused ones included. */
    val registrationAttemptCount: Int get() = registrationAttempts.get()

    /** How many `removeUpdates` calls were made. */
    val removalCount: Int get() = removalCalls.get()

    /** The framework one-shot calls made so far, in order. */
    fun currentCalls(): List<CurrentCall> = synchronized(lock) { calls.toList() }

    /** Service-change subscriptions that are live now (broadcast receivers). */
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    fun setCached(provider: String, location: Location?) = synchronized(lock) {
        if (location == null) cache.remove(provider) else cache[provider] = location
    }

    fun addProvider(provider: String) = synchronized(lock) {
        present += provider
        enabled.getOrPut(provider) { true }
    }

    fun removeProvider(provider: String) = synchronized(lock) { present -= provider }

    /** Makes `requestUpdates` for [provider] throw [SecurityException]. */
    fun refuse(provider: String) = synchronized(lock) { refusing += provider }

    /** Lifts [refuse] for [provider]. */
    fun allow(provider: String) = synchronized(lock) { refusing -= provider }

    /** Makes `requestUpdates` for [provider] throw [failure]. */
    fun failRegistration(provider: String, failure: Throwable) = synchronized(lock) { failing[provider] = failure }

    /** Switches [provider] and fires what the platform fires: the provider callbacks, then the broadcast. */
    fun setProviderEnabled(provider: String, on: Boolean) {
        val affected: List<Registration> = synchronized(lock) {
            enabled[provider] = on
            registrations.filter { registration: Registration -> registration.provider == provider }
        }
        for (registration: Registration in affected) {
            if (on) registration.listener.onProviderEnabled(provider) else registration.listener.onProviderDisabled(provider)
        }
        broadcastServiceChange()
    }

    /** Flips the device-wide switch and fires the broadcast. */
    fun setDeviceLocationEnabled(on: Boolean) {
        locationEnabled = on
        broadcastServiceChange()
    }

    private fun broadcastServiceChange() {
        val targets: List<() -> Unit> = synchronized(lock) { subscribers.toList() }
        for (target: () -> Unit in targets) target()
    }

    /** Delivers [location] to every live registration of [provider], as the platform does on the main looper. */
    fun deliver(provider: String, location: Location) {
        val targets: List<Registration> = synchronized(lock) {
            registrations.filter { registration: Registration -> registration.provider == provider }
        }
        for (registration: Registration in targets) registration.listener.onLocationChanged(location)
    }

    /** Delivers [location] to every live registration whatever its provider; used by tests that hold the listener. */
    fun deliverToAll(location: Location) {
        val targets: List<Registration> = synchronized(lock) { registrations.toList() }
        for (registration: Registration in targets) registration.listener.onLocationChanged(location)
    }

    override fun hasProvider(provider: String): Boolean = synchronized(lock) { provider in present }

    override fun isProviderEnabled(provider: String): Boolean = synchronized(lock) { enabled[provider] == true }

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun hasFinePermission(): Boolean = fine

    override fun hasCoarsePermission(): Boolean = coarse

    override fun lastKnownLocation(provider: String): Location? {
        if (!fine && !coarse) throw SecurityException("no location permission")
        return synchronized(lock) {
            if (provider in refusing) throw SecurityException("$provider refused")
            cache[provider]
        }
    }

    override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
        registrationAttempts.incrementAndGet()
        synchronized(lock) {
            if (provider in refusing) throw SecurityException("$provider refused")
            failing[provider]?.let { failure: Throwable -> throw failure }
            registrations += Registration(provider, request, listener)
        }
    }

    override fun removeUpdates(listener: LocationListener) {
        removalCalls.incrementAndGet()
        synchronized(lock) { registrations.removeAll { registration: Registration -> registration.listener === listener } }
        removalFailure?.let { failure: Throwable -> throw failure }
    }

    override fun requestCurrentLocation(
        provider: String,
        accuracy: LocationAccuracy,
        onResult: (Location?) -> Unit,
    ): (() -> Unit)? {
        if (!supportsCurrentLocation) return null
        val call = CurrentCall(provider, accuracy, onResult)
        synchronized(lock) {
            if (provider in refusing) throw SecurityException("$provider refused")
            calls += call
        }
        return { call.cancelled = true }
    }

    override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
        synchronized(lock) { subscribers += onChange }
        return { synchronized(lock) { subscribers.remove(onChange) } }
    }

    override fun elapsedRealtimeNanos(): Long = nowNanos

    /** Answers the framework one-shot call number [index] (the platform calling back). */
    fun answerCurrentCall(index: Int, location: Location?) {
        val call: CurrentCall = synchronized(lock) { calls[index] }
        if (!call.cancelled) call.onResult(location)
    }
}

/**
 * A platform `Location` for [provider] taken [ageMillis] before the backend's boot clock now, stamped on
 * the boot clock only (the wall-clock `time` is a fixed number: no test reads a wall clock).
 */
internal fun FakeLocationBackend.locationAt(
    provider: String,
    latitude: Double,
    longitude: Double = 2.0,
    ageMillis: Long = 0L,
): Location = Location(provider).apply {
    this.latitude = latitude
    this.longitude = longitude
    time = 1_700_000_000_000L
    elapsedRealtimeNanos = nowNanos - ageMillis * 1_000_000L
    accuracy = 5f
}

/**
 * The provider under test over [backend]. [timeSource] is the clock of the stream throttle: tests with a
 * `minUpdateInterval` pass `testScheduler.timeSource` so the timer and the clock agree.
 */
internal fun newProvider(
    backend: FakeLocationBackend,
    timeSource: TimeSource = TestTimeSource(),
): LocationProvider = AndroidLocationProvider(
    context = ApplicationProvider.getApplicationContext<Context>(),
    logger = NoopLogger,
    systemScreenLauncher = SystemScreenLauncher.SeparateTask,
    backend = backend,
    timeSource = timeSource,
)
