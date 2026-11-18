package io.github.jamal_wia.kmptoolkit.location.testing

import io.github.jamal_wia.kmptoolkit.location.CurrentLocationOptions
import io.github.jamal_wia.kmptoolkit.location.LocationProvider
import io.github.jamal_wia.kmptoolkit.location.LocationReading
import io.github.jamal_wia.kmptoolkit.location.LocationServicePrompt
import io.github.jamal_wia.kmptoolkit.location.LocationUnavailability
import io.github.jamal_wia.kmptoolkit.location.LocationUpdatesOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update

/**
 * A [LocationProvider] a test drives by hand: no GPS chip, no location permission, no
 * `CLLocationManager` delegate. Safe to call from any thread.
 *
 * It plays any outcome a real provider can produce and applies **no policy**: it records the
 * options of every call and ignores them (no max age, no timeout, no throttling, no accuracy), so a
 * test states the answer it wants and the code under test is the only thing being tested. Its
 * stream ends exactly like the real providers': a terminal reading
 * ([LocationUnavailability.PERMISSION_MISSING] or [LocationUnavailability.UNSUPPORTED]) completes
 * the collectors, every other reading keeps them open.
 *
 * ```kotlin
 * val location = FakeLocationProvider()
 * val presenter = MapPresenter(location)
 * assertEquals(MapState.Searching, presenter.state)            // the stream seed: Unavailable(NO_FIX)
 *
 * location.emit(LocationReading.Available(fakeFix(21.4225, 39.8262)))
 * assertEquals(MapState.Centered, presenter.state)
 * ```
 *
 * ### The stream
 * [observeLocation] is cold, but every collector reads one shared, conflated source that replays
 * its latest reading: a new collector starts from the latest reading ([streamSeed] until the first
 * [emit], as a real provider also seeds at once); a collector that is busy skips intermediate
 * readings and ends on the newest. An equal reading emitted twice is delivered twice, as a real
 * provider delivers every update. A terminal reading stays the latest one until the next [emit], so
 * collecting again after it completes again, and an [emit] of anything else serves the next
 * collection (re-initialisation after release). A terminal reading is never conflated away: a
 * collection that is live when one is emitted ends on it even if a later [emit] arrives before the
 * collector has read it (the readings in between are skipped, the later ones are never delivered to
 * that collection), as a real provider releases its platform registration at the terminal reading
 * and delivers nothing after it. If several terminal readings are emitted before the collector runs, it
 * ends on the newest of them.
 *
 * ### One-shot calls
 * [getCurrentLocation] answers [currentLocationAnswer], or, between [holdCurrentLocation] and
 * [releaseCurrentLocation], suspends. Every call is recorded in [currentLocationCalls] before it
 * answers or suspends.
 *
 * ### Threads
 * Every counter, recorder and setting is safe to use from any thread, with no lock held across a
 * suspension point. The recorder lists are snapshots (copies), exact once the threads that drove the
 * fake have finished. A fake that stays unreleased holds no thread: a held call is a suspended
 * coroutine.
 *
 * @param locationEnabled what [isLocationEnabled] and [observeLocationEnabled] report until changed.
 * @param streamSeed what every [observeLocation] collector receives first until the first [emit].
 * Defaults to `Unavailable(NO_FIX)`: "still searching".
 */
public class FakeLocationProvider(
    locationEnabled: Boolean = true,
    streamSeed: LocationReading = LocationReading.Unavailable(LocationUnavailability.NO_FIX),
) : LocationProvider {

    // One atomic cell holds the latest reading, its sequence number and the latest terminal reading, so
    // `emit` is a single compare-and-set (`update {}`: thread-safe, no lock, nothing resumed from inside
    // it) and the three can never disagree. Being a StateFlow it replays the latest value to a new
    // collector and conflates for a busy one; a snapshot is a plain object (compared by identity), so an
    // equal reading emitted twice is still a new value, as a real provider delivers every update. The
    // terminal reading rides along in every later snapshot, which is how a collection that conflation
    // skipped past it still finds it and ends on it (see observeLocation).
    private val stream: MutableStateFlow<Snapshot> = MutableStateFlow(seedSnapshot(streamSeed))

    private val enabled: MutableStateFlow<Boolean> = MutableStateFlow(locationEnabled)
    private val answer: MutableStateFlow<LocationReading> =
        MutableStateFlow(LocationReading.Unavailable(LocationUnavailability.NO_FIX))
    private val promptAnswer: MutableStateFlow<LocationServicePrompt?> = MutableStateFlow(null)
    private val settingsOpened: MutableStateFlow<Int> = MutableStateFlow(0)
    private val prompted: MutableStateFlow<Int> = MutableStateFlow(0)

    // The open hold, or null. One deferred per hold: release swaps the field to null first and then
    // completes the deferred it swapped out, so a call that read the deferred just before the swap
    // still gets the released reading, and a call that reads after the swap sees no hold.
    private val hold: MutableStateFlow<CompletableDeferred<LocationReading>?> = MutableStateFlow(null)
    private val pending: MutableStateFlow<Int> = MutableStateFlow(0)

    private val currentCalls: FakeRecorder<CurrentLocationOptions> = FakeRecorder()
    private val observers: FakeRecorder<LocationUpdatesOptions> = FakeRecorder()

    /**
     * Makes [reading] the latest stream value: every live collector receives it (conflated) and
     * every later collector starts from it. [LocationUnavailability.PERMISSION_MISSING] and
     * [LocationUnavailability.UNSUPPORTED] complete the live collectors (even one that has not yet
     * read the reading when the next `emit` arrives), and stay the latest value until the next
     * `emit`, so collecting again after a terminal reading completes again.
     *
     * Does not change what [getCurrentLocation] answers (set [currentLocationAnswer] for that).
     */
    public fun emit(reading: LocationReading) {
        stream.update { previous: Snapshot ->
            val sequence: Long = previous.sequence + 1
            Snapshot(
                sequence = sequence,
                reading = reading,
                terminal = if (reading.completesTheStream()) Terminal(sequence, reading) else previous.terminal,
            )
        }
    }

    /**
     * What a [getCurrentLocation] call that is not held answers. Defaults to
     * `Unavailable(NO_FIX)`, so a test that forgot to script it fails visibly instead of passing on a
     * position it never chose.
     */
    public var currentLocationAnswer: LocationReading
        get() = answer.value
        set(value) {
            answer.value = value
        }

    /**
     * From now on every [getCurrentLocation] call suspends until [releaseCurrentLocation], for
     * spinner and cancellation tests. A timeout is the caller's own `withTimeout`: the fake applies
     * none. Holding while a hold is already open changes nothing (the calls already held stay held).
     */
    public fun holdCurrentLocation() {
        hold.update { open -> open ?: CompletableDeferred() }
    }

    /**
     * Resumes every held call with [reading] and ends the hold: the next call answers
     * [currentLocationAnswer] again. With nothing held it only ends the hold.
     */
    public fun releaseCurrentLocation(reading: LocationReading) {
        hold.getAndUpdate { null }?.complete(reading)
    }

    /**
     * [getCurrentLocation] calls currently suspended by the hold. Drops when a call is released or
     * cancelled: the one-shot release proof (`0` after every call has been released or cancelled).
     */
    public val pendingCurrentLocationCalls: StateFlow<Int> = pending.asStateFlow()

    /** Options of every [getCurrentLocation] call so far, in the order the calls arrived (platform options included). */
    public val currentLocationCalls: List<CurrentLocationOptions>
        get() = currentCalls.snapshot()

    /** Options of every live [observeLocation] collector, oldest first (platform options included). */
    public val activeUpdatesOptions: List<LocationUpdatesOptions>
        get() = observers.snapshot()

    /**
     * Live [observeLocation] collectors: counted from the moment a collection starts until it ends
     * for any reason (cancelled, completed by a terminal reading, failed). `0` proves release.
     */
    public val activeObservers: StateFlow<Int> = observers.count

    /**
     * What [isLocationEnabled] and [observeLocationEnabled] report. Drives only those: it never
     * changes the readings (no hidden coupling), so a test that wants `SERVICE_DISABLED` on the
     * stream emits it.
     */
    public var locationEnabled: Boolean
        get() = enabled.value
        set(value) {
            enabled.value = value
        }

    /**
     * What [promptToEnableService] answers while the location service is off. `null` (the default)
     * keeps the interface's own answer, [LocationServicePrompt.UNSUPPORTED], what both factory-built
     * providers give. Set it to [LocationServicePrompt.PROMPTED] or [LocationServicePrompt.NOT_NOW]
     * to stand in for a provider that can raise a dialog. While [locationEnabled] is `true` the
     * answer is always [LocationServicePrompt.ALREADY_ON], as on a real provider.
     */
    public var servicePromptAnswer: LocationServicePrompt?
        get() = promptAnswer.value
        set(value) {
            promptAnswer.value = value
        }

    /** How many times [openLocationSettings] has been called. */
    public val openSettingsCount: Int
        get() = settingsOpened.value

    /** How many times [promptToEnableService] has been called, including calls that found the service already on. */
    public val promptCount: Int
        get() = prompted.value

    override suspend fun getCurrentLocation(options: CurrentLocationOptions): LocationReading {
        currentCalls.add(options)
        val open: CompletableDeferred<LocationReading> = hold.value ?: return answer.value
        pending.update { it + 1 }
        try {
            return open.await()
        } finally {
            pending.update { it - 1 }
        }
    }

    override fun observeLocation(options: LocationUpdatesOptions): Flow<LocationReading> = flow {
        // Registered when the collection starts, released on every way out of it (the collector is
        // cancelled, a terminal reading completes the stream, the downstream throws). Nothing is held
        // between collections.
        val registration: FakeRecorder.Entry<LocationUpdatesOptions> = observers.add(options)
        try {
            // The sequence number of the last reading this collection has delivered; before the first
            // one, the one that precedes the reading the collection starts from, so a terminal reading
            // that is already the latest one still counts as not yet delivered, and an older one does not.
            var delivered: Long = stream.value.sequence - 1
            emitAll(
                stream.transformWhile { snapshot: Snapshot ->
                    val undelivered: Terminal? = snapshot.terminal?.takeIf { it.sequence > delivered }
                    if (undelivered != null) {
                        // A terminal reading is newer than anything this collection delivered: end on it,
                        // even when conflation made the collector skip past it to a later reading.
                        emit(undelivered.reading)
                        false
                    } else {
                        emit(snapshot.reading)
                        delivered = snapshot.sequence
                        true
                    }
                },
            )
        } finally {
            observers.remove(registration)
        }
    }

    override suspend fun isLocationEnabled(): Boolean = enabled.value

    override fun observeLocationEnabled(): Flow<Boolean> = enabled.asStateFlow()

    override fun openLocationSettings() {
        settingsOpened.update { it + 1 }
    }

    override suspend fun promptToEnableService(): LocationServicePrompt {
        prompted.update { it + 1 }
        if (enabled.value) return LocationServicePrompt.ALREADY_ON
        return promptAnswer.value ?: LocationServicePrompt.UNSUPPORTED
    }
}

// The real providers' rule (PLAN D8): permission and unsupported end the stream, service-off and no-fix keep it
// open. Kept private and in step with the library's own copy by FakeLocationProviderTest (every reason is pinned).
private fun LocationReading.completesTheStream(): Boolean =
    this is LocationReading.Unavailable &&
        (reason == LocationUnavailability.PERMISSION_MISSING || reason == LocationUnavailability.UNSUPPORTED)

/** The latest reading with its sequence number (the n-th `emit`; the seed is 0) and the latest terminal one. */
private class Snapshot(val sequence: Long, val reading: LocationReading, val terminal: Terminal?)

/** A terminal reading and the sequence number of the `emit` that made it. */
private class Terminal(val sequence: Long, val reading: LocationReading)

private fun seedSnapshot(seed: LocationReading): Snapshot =
    Snapshot(sequence = 0, reading = seed, terminal = if (seed.completesTheStream()) Terminal(0, seed) else null)
