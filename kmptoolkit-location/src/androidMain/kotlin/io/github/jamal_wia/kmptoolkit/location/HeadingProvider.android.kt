package io.github.jamal_wia.kmptoolkit.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.math.PI
import kotlinx.coroutines.flow.Flow

/**
 * Creates the Android [HeadingProvider], over `SensorManager`: the compass of the device as a
 * [Heading] for the top edge of the screen as drawn.
 *
 * The sensor pipeline is chosen by [AndroidHeadingOptions.sensor]; the default
 * ([AndroidHeadingSensor.AUTO]) takes the rotation vector, else the geomagnetic rotation vector,
 * else the accelerometer together with the magnetometer. [HeadingProvider.isAvailable] is `false`
 * when the device has none of the chosen ones, and then [HeadingProvider.observeHeading] stays open
 * and silent.
 *
 * Nothing is registered by the factory or kept between collections: every collection of
 * [HeadingProvider.observeHeading] registers its own sensor listener (and, only when it needs a
 * position for true north, its own passive location listener) and unregisters them when it ends.
 * Concurrent collectors are therefore independent, each with its own filter and screen rotation.
 *
 * ### True north
 *
 * [Heading.trueDegrees] is the magnetic heading plus the magnetic declination of
 * `android.hardware.GeomagneticField`. Pass `declinationAt` to
 * [HeadingProvider.observeHeading] and the declination is computed there, **without any location
 * permission**. Without it the newest position the app is permitted to read is used: the newest
 * cached fix of any provider at the start of the collection, then every fix the passive provider
 * delivers (fixes other apps or this library's `LocationProvider` already asked for, so the compass
 * adds no power draw of its own). While there is no such position, `trueDegrees` is `null`. The
 * library declares no permission; with neither location permission granted the compass works and
 * `trueDegrees` stays `null` unless `declinationAt` is given.
 *
 * ### Screen rotation
 *
 * `screenRotation` is the device's physical rotation from its natural orientation, counter-clockwise
 * (exactly `Surface.ROTATION_*`). It is applied as an axis remap
 * (`SensorManager.remapCoordinateSystem`), which is correct for a tilted device as well as a flat one,
 * and it is fixed for a collection: re-collect on rotation.
 *
 * ### Accuracy and calibration
 *
 * [Heading.accuracyDegrees] is the rotation vector's own heading-accuracy estimate (radians converted
 * to degrees) and is `null` for every other pipeline or while the platform reports none.
 * [Heading.needsCalibration] is `true` while the platform reports the sensor as
 * `SENSOR_STATUS_UNRELIABLE` or `SENSOR_STATUS_ACCURACY_LOW`; the library does not compare the field
 * strength with the expected one.
 *
 * A sensor the platform refuses to enable leaves nothing registered and the stream open and silent,
 * like a device without the sensor; a registration that throws propagates to the collector, again with
 * nothing left registered.
 *
 * @param context any `Context`; only the framework managers are obtained from its application
 *   context, nothing else is retained.
 * @param options Android-only heading settings.
 * @param logger where a dropped sample, a refused sensor or a failed passive registration is reported.
 */
public fun createHeadingProvider(
    context: Context,
    options: AndroidHeadingOptions = AndroidHeadingOptions(),
    logger: Logger = NoopLogger,
): HeadingProvider {
    val application: Context = context.applicationContext
    val sensorManager: SensorManager? = application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val locationManager: LocationManager? = application.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return AndroidHeadingProvider(
        sensors = sensorManager?.let { manager: SensorManager -> ManagerHeadingSensors(manager) },
        positions = locationManager?.let { manager: LocationManager -> ManagerHeadingPositions(manager) },
        choice = options.sensor,
        logger = logger,
        currentTimeMillis = { System.currentTimeMillis() },
        declinationOf = ::geomagneticDeclination,
    )
}

/**
 * The part of `SensorManager` a heading collection uses, as a seam: a test makes one registration
 * fail, or refuse, and counts what is registered.
 */
internal interface HeadingSensors {
    fun defaultSensor(type: Int): Sensor?

    /** `false` when the platform could not enable the sensor. May throw (for example `SecurityException`). */
    fun register(listener: SensorEventListener, sensor: Sensor): Boolean

    /** Removes the listener from every sensor; removing one that is not registered is a no-op. */
    fun unregister(listener: SensorEventListener)
}

/** A position good enough to compute a declination: valid coordinates and an altitude (0 when unknown). */
internal class DeclinationPosition(val coordinates: GeoCoordinates, val altitudeMeters: Double)

/**
 * The part of `LocationManager` a heading collection uses to find a position for true north, as a seam
 * for the same reasons as [HeadingSensors].
 */
internal interface HeadingPositions {

    /** The newest cached fix of any provider this app may read, or `null`; never throws. */
    fun newestPosition(): DeclinationPosition?

    /** Starts passive updates. Throws `SecurityException` when the app may not read positions. */
    fun register(listener: LocationListener)

    /** Stops them; removing a listener that is not registered is a no-op. */
    fun unregister(listener: LocationListener)
}

internal class ManagerHeadingSensors(private val manager: SensorManager) : HeadingSensors {
    override fun defaultSensor(type: Int): Sensor? = manager.getDefaultSensor(type)

    // SENSOR_DELAY_UI (about 16 Hz): a compass needle, and below the rates that need
    // HIGH_SAMPLING_RATE_SENSORS, which this library does not declare.
    override fun register(listener: SensorEventListener, sensor: Sensor): Boolean =
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)

    override fun unregister(listener: SensorEventListener) = manager.unregisterListener(listener)
}

@SuppressLint("MissingPermission")
internal class ManagerHeadingPositions(private val manager: LocationManager) : HeadingPositions {

    override fun newestPosition(): DeclinationPosition? {
        val cached: List<Location> = manager.allProviders.mapNotNull { provider: String ->
            try {
                manager.getLastKnownLocation(provider)
            } catch (refused: SecurityException) {
                // A provider the caller's permission does not cover simply has no cached fix for it.
                null
            } catch (unknown: IllegalArgumentException) {
                null
            }
        }.filter { location: Location -> location.toDeclinationPosition() != null }
        // elapsedRealtimeNanos is the platform's monotonic stamp; a fix without one (0: a test fixture)
        // makes the whole comparison fall back to the wall-clock time, so two clocks are never compared.
        val newest: Location? = if (cached.all { location: Location -> location.elapsedRealtimeNanos > 0L }) {
            cached.maxByOrNull { location: Location -> location.elapsedRealtimeNanos }
        } else {
            cached.maxByOrNull { location: Location -> location.time }
        }
        return newest?.toDeclinationPosition()
    }

    override fun register(listener: LocationListener) {
        manager.requestLocationUpdates(
            LocationManager.PASSIVE_PROVIDER,
            PASSIVE_MIN_TIME_MILLIS,
            PASSIVE_MIN_DISTANCE_METERS,
            listener,
            Looper.getMainLooper(),
        )
    }

    override fun unregister(listener: LocationListener) = manager.removeUpdates(listener)

    private companion object {
        // Declination moves by about one degree over hundreds of kilometres, so a fix a minute or five
        // kilometres apart is far more often than the compass needs.
        const val PASSIVE_MIN_TIME_MILLIS: Long = 60_000L
        const val PASSIVE_MIN_DISTANCE_METERS: Float = 5_000f
    }
}

/** `null` for a fix whose coordinates are not valid: it is dropped, never thrown on (decision D6). */
private fun Location.toDeclinationPosition(): DeclinationPosition? {
    val coordinates: GeoCoordinates = GeoCoordinates.orNull(latitude, longitude) ?: return null
    val altitudeMeters: Double = if (hasAltitude() && altitude.isFinite()) altitude else 0.0
    return DeclinationPosition(coordinates, altitudeMeters)
}

/** The magnetic declination in degrees (east positive) at a position and time, from the framework's model. */
internal fun geomagneticDeclination(coordinates: GeoCoordinates, altitudeMeters: Double, timeMillis: Long): Double =
    GeomagneticField(
        coordinates.latitude.toFloat(),
        coordinates.longitude.toFloat(),
        altitudeMeters.toFloat(),
        timeMillis,
    ).declination.toDouble()

/** The chosen sensors of one provider: either one rotation-vector sensor or the accelerometer and magnetometer. */
internal class HeadingPipeline(val kind: Kind, val sensors: List<Sensor>) {

    enum class Kind { ROTATION_VECTOR, ACCELEROMETER_MAGNETOMETER }

    companion object {

        /** The pipeline [choice] names on this device, or `null` when a sensor it needs is absent. */
        fun resolve(hub: HeadingSensors?, choice: AndroidHeadingSensor): HeadingPipeline? {
            if (hub == null) return null
            fun rotationVector(type: Int): HeadingPipeline? =
                hub.defaultSensor(type)?.let { sensor: Sensor -> HeadingPipeline(Kind.ROTATION_VECTOR, listOf(sensor)) }

            fun raw(): HeadingPipeline? {
                val accelerometer: Sensor = hub.defaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return null
                val magnetometer: Sensor = hub.defaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return null
                return HeadingPipeline(Kind.ACCELEROMETER_MAGNETOMETER, listOf(accelerometer, magnetometer))
            }
            return when (choice) {
                AndroidHeadingSensor.AUTO ->
                    rotationVector(Sensor.TYPE_ROTATION_VECTOR)
                        ?: rotationVector(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                        ?: raw()
                AndroidHeadingSensor.ROTATION_VECTOR -> rotationVector(Sensor.TYPE_ROTATION_VECTOR)
                AndroidHeadingSensor.GEOMAGNETIC_ROTATION_VECTOR -> rotationVector(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                AndroidHeadingSensor.ACCELEROMETER_MAGNETOMETER -> raw()
            }
        }
    }
}

/** A three-axis sensor sample, copied out of the event (the framework reuses the event's array). */
internal class Vector3(val x: Float, val y: Float, val z: Float) {
    fun toArray(): FloatArray = floatArrayOf(x, y, z)
}

/**
 * The newest state of one collection, written by the sensor and location callbacks through
 * [HeadingInputs] and composed into a [Heading] on the collecting coroutine. Immutable; every field is
 * the whole truth for its part, so a collector that skips intermediate states loses nothing.
 *
 * @property azimuthDegrees the screen's top edge azimuth from magnetic north, `null` until the first sample.
 * @property declinationDegrees the declination to add for true north, `null` while there is no position.
 */
internal data class AndroidHeadingState(
    val azimuthDegrees: Double? = null,
    val accuracyDegrees: Double? = null,
    val needsCalibration: Boolean = false,
    val declinationDegrees: Double? = null,
    val gravity: Vector3? = null,
    val geomagnetic: Vector3? = null,
)

internal class AndroidHeadingProvider(
    private val sensors: HeadingSensors?,
    private val positions: HeadingPositions?,
    choice: AndroidHeadingSensor,
    private val logger: Logger,
    private val currentTimeMillis: () -> Long,
    private val declinationOf: (GeoCoordinates, Double, Long) -> Double,
) : HeadingProvider {

    private val pipeline: HeadingPipeline? = HeadingPipeline.resolve(sensors, choice)

    override val isAvailable: Boolean get() = pipeline != null

    override fun observeHeading(
        minChangeDegrees: Double,
        declinationAt: GeoCoordinates?,
        screenRotation: ScreenRotation,
    ): Flow<Heading> = headingStream(
        minChangeDegrees = minChangeDegrees,
        initial = { initialState(declinationAt) },
        compose = ::composeHeading,
        register = { inputs: HeadingInputs<AndroidHeadingState> -> registerCollection(inputs, declinationAt, screenRotation) },
    )

    /**
     * The state a collection starts with, built before anything is registered: the declination of the
     * requested position, or of the newest cached fix this app may read, or none.
     */
    private fun initialState(declinationAt: GeoCoordinates?): AndroidHeadingState {
        val declination: Double? = when {
            declinationAt != null -> declinationFor(DeclinationPosition(declinationAt, altitudeMeters = 0.0))
            else -> positions?.newestPosition()?.let { position: DeclinationPosition -> declinationFor(position) }
        }
        return AndroidHeadingState(declinationDegrees = declination)
    }

    /** `null` when the model cannot answer (never thrown to a callback or the collector). */
    private fun declinationFor(position: DeclinationPosition): Double? = try {
        declinationOf(position.coordinates, position.altitudeMeters, currentTimeMillis()).takeIf { value: Double -> value.isFinite() }
    } catch (failure: Exception) {
        logger.w(failure) { "Could not compute the magnetic declination" }
        null
    }

    private fun composeHeading(state: AndroidHeadingState): Heading? {
        val azimuth: Double = state.azimuthDegrees ?: return null
        val magnetic: Double = normalizeDegrees(azimuth)
        val trueDegrees: Double? = state.declinationDegrees?.let { declination: Double -> normalizeDegrees(magnetic + declination) }
        return Heading(magnetic, trueDegrees, state.accuracyDegrees, state.needsCalibration)
    }

    /**
     * One collection's registrations. Either everything this call registered is released before it
     * returns or throws, or the returned handle releases it; `null` means nothing is registered.
     */
    private fun registerCollection(
        inputs: HeadingInputs<AndroidHeadingState>,
        declinationAt: GeoCoordinates?,
        screenRotation: ScreenRotation,
    ): AutoCloseable? {
        val hub: HeadingSensors = sensors ?: return null
        val chosen: HeadingPipeline = pipeline ?: return null
        val sensorListener: SensorEventListener = SensorListener(chosen, inputs, screenRotation)

        // The primary registration: whatever goes wrong, nothing stays registered.
        try {
            for (sensor: Sensor in chosen.sensors) {
                if (!hub.register(sensorListener, sensor)) {
                    runCatching { hub.unregister(sensorListener) }
                    logger.w { "The platform refused to enable heading sensor ${sensor.name}: the heading stays silent" }
                    return null
                }
            }
        } catch (failure: Throwable) {
            // A later sensor's registration may have failed after an earlier one succeeded: awaitClose
            // style cleanup never runs on this path, so undo here. The undo's own failure rides along as
            // suppressed instead of replacing the original.
            runCatching { hub.unregister(sensorListener) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }

        // The optional registration: a position for true north, only when the caller named none. Its
        // failure costs trueDegrees, never the compass.
        var positionListener: LocationListener? = null
        val source: HeadingPositions? = positions
        if (declinationAt == null && source != null) {
            val candidate: LocationListener = PositionListener(inputs)
            try {
                source.register(candidate)
                positionListener = candidate
            } catch (failure: Exception) {
                runCatching { source.unregister(candidate) }
                logger.d { "No passive position for true north (${failure::class.simpleName}): trueDegrees stays null until declinationAt is given" }
            }
        }

        val registered: LocationListener? = positionListener
        return AutoCloseable {
            // Both removals are idempotent in the framework; one failing must not strand the other.
            if (registered != null && source != null) {
                runCatching { source.unregister(registered) }.exceptionOrNull()?.let { failure: Throwable ->
                    logger.w(failure) { "Could not remove the heading position listener" }
                }
            }
            runCatching { hub.unregister(sensorListener) }.exceptionOrNull()?.let { failure: Throwable ->
                logger.w(failure) { "Could not remove the heading sensor listener" }
            }
        }
    }

    /**
     * The platform sensor callback of one collection. Runs on the platform's thread, never blocks, never
     * throws into the platform: a sample it cannot use is dropped.
     */
    private inner class SensorListener(
        private val chosen: HeadingPipeline,
        private val inputs: HeadingInputs<AndroidHeadingState>,
        private val screenRotation: ScreenRotation,
    ) : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent) {
            try {
                when (chosen.kind) {
                    HeadingPipeline.Kind.ROTATION_VECTOR -> onRotationVector(event.values ?: return)
                    HeadingPipeline.Kind.ACCELEROMETER_MAGNETOMETER -> onRawSample(event)
                }
            } catch (failure: Exception) {
                logger.w(failure) { "Dropped a heading sample" }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Only the sensor the heading comes from: the accelerometer of the raw pipeline says nothing
            // about the compass.
            val relevant: Boolean = when (chosen.kind) {
                HeadingPipeline.Kind.ROTATION_VECTOR -> true
                HeadingPipeline.Kind.ACCELEROMETER_MAGNETOMETER -> sensor?.type == Sensor.TYPE_MAGNETIC_FIELD
            }
            if (!relevant) return
            val needsCalibration: Boolean = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            inputs.update { state: AndroidHeadingState ->
                if (state.needsCalibration == needsCalibration) state else state.copy(needsCalibration = needsCalibration)
            }
        }

        private fun onRotationVector(values: FloatArray) {
            val azimuth: Double = azimuthFromRotationVector(values, screenRotation) ?: return
            val accuracy: Double? = rotationVectorAccuracyDegrees(values)
            inputs.update { state: AndroidHeadingState -> state.copy(azimuthDegrees = azimuth, accuracyDegrees = accuracy) }
        }

        private fun onRawSample(event: SensorEvent) {
            val values: FloatArray = event.values ?: return
            if (values.size < 3) return
            val sample = Vector3(values[0], values[1], values[2])
            when (event.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER -> inputs.update { state: AndroidHeadingState ->
                    state.withVectors(sample, state.geomagnetic, screenRotation)
                }
                Sensor.TYPE_MAGNETIC_FIELD -> inputs.update { state: AndroidHeadingState ->
                    state.withVectors(state.gravity, sample, screenRotation)
                }
            }
        }
    }

    /**
     * The passive-position callback of one collection: every valid fix updates the declination. Every
     * method of `LocationListener` is implemented: on API 24-29 the three legacy callbacks are abstract
     * (defaults only from API 30) and the platform calls them, so a missing one is an
     * `AbstractMethodError` on the main thread.
     */
    private inner class PositionListener(
        private val inputs: HeadingInputs<AndroidHeadingState>,
    ) : LocationListener {

        override fun onLocationChanged(location: Location) {
            try {
                val position: DeclinationPosition = location.toDeclinationPosition() ?: return
                val declination: Double = declinationFor(position) ?: return
                inputs.update { state: AndroidHeadingState ->
                    if (state.declinationDegrees == declination) state else state.copy(declinationDegrees = declination)
                }
            } catch (failure: Exception) {
                logger.w(failure) { "Dropped a position for the heading declination" }
            }
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Deprecated in Java")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
}

/** A new state with the vectors replaced and the azimuth recomputed once both are known. */
private fun AndroidHeadingState.withVectors(
    gravity: Vector3?,
    geomagnetic: Vector3?,
    screenRotation: ScreenRotation,
): AndroidHeadingState {
    val azimuth: Double? = if (gravity != null && geomagnetic != null) {
        azimuthFromRawVectors(gravity, geomagnetic, screenRotation)
    } else {
        null
    }
    return copy(gravity = gravity, geomagnetic = geomagnetic, azimuthDegrees = azimuth ?: azimuthDegrees)
}

/**
 * The azimuth in degrees `[0, 360)` of the screen's top edge from a rotation-vector sample, or `null`
 * when the sample is unusable. Some devices report five values and older framework code rejected more
 * than four, so at most four are handed on.
 */
internal fun azimuthFromRotationVector(values: FloatArray, screenRotation: ScreenRotation): Double? {
    if (values.size < 3 || values.any { value: Float -> !value.isFinite() }) return null
    val matrix = FloatArray(MATRIX_SIZE)
    SensorManager.getRotationMatrixFromVector(matrix, if (values.size > 4) values.copyOf(4) else values)
    return azimuthFromMatrix(matrix, screenRotation)
}

/** The azimuth of the screen's top edge from raw gravity and geomagnetic vectors, or `null` when they give no matrix. */
internal fun azimuthFromRawVectors(gravity: Vector3, geomagnetic: Vector3, screenRotation: ScreenRotation): Double? {
    val matrix = FloatArray(MATRIX_SIZE)
    val gravityArray: FloatArray = gravity.toArray()
    val geomagneticArray: FloatArray = geomagnetic.toArray()
    if (gravityArray.any { value: Float -> !value.isFinite() } || geomagneticArray.any { value: Float -> !value.isFinite() }) return null
    if (!SensorManager.getRotationMatrix(matrix, null, gravityArray, geomagneticArray)) return null
    return azimuthFromMatrix(matrix, screenRotation)
}

/**
 * Remaps [matrix] to the screen as drawn and reads the azimuth. The axis pairs are the platform's own
 * recipe for each `Surface.ROTATION_*`: the original device axes land on the new ones as listed (for
 * `ROTATION_90`, the device's X axis becomes the screen's up axis).
 */
private fun azimuthFromMatrix(matrix: FloatArray, screenRotation: ScreenRotation): Double? {
    val remapped: FloatArray = if (screenRotation == ScreenRotation.NATURAL) {
        matrix
    } else {
        val (axisX: Int, axisY: Int) = when (screenRotation) {
            ScreenRotation.NATURAL -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            ScreenRotation.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            ScreenRotation.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            ScreenRotation.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        }
        val out = FloatArray(MATRIX_SIZE)
        if (!SensorManager.remapCoordinateSystem(matrix, axisX, axisY, out)) return null
        out
    }
    val orientation = FloatArray(3)
    SensorManager.getOrientation(remapped, orientation)
    val degrees: Double = orientation[0].toDouble() * RADIANS_TO_DEGREES
    return if (degrees.isFinite()) normalizeDegrees(degrees) else null
}

/** The rotation vector's heading-accuracy estimate (`values[4]`, radians) in degrees; `null` when absent (-1) or invalid. */
internal fun rotationVectorAccuracyDegrees(values: FloatArray): Double? {
    if (values.size <= HEADING_ACCURACY_INDEX) return null
    val radians: Float = values[HEADING_ACCURACY_INDEX]
    return if (radians.isFinite() && radians >= 0f) radians.toDouble() * RADIANS_TO_DEGREES else null
}

private const val MATRIX_SIZE: Int = 9
private const val HEADING_ACCURACY_INDEX: Int = 4
private const val RADIANS_TO_DEGREES: Double = 180.0 / PI
