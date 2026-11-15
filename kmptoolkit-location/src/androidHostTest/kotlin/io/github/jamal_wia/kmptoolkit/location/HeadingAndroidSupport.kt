@file:OptIn(ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

/*
 * Support for the Android heading tests (group loc-heading): sensors and events on Robolectric's
 * SensorManager, an independent oracle for the azimuth of the screen's top edge built from first
 * principles (a quaternion, its rotation matrix and the physical meaning of each screen rotation, NOT the
 * platform's remap recipe the production code uses), a scripted HeadingSensors with thread-safe counters
 * for the lifecycle attacks, and a logger that records.
 */

/** Sensors and events on Robolectric's real `SensorManager`. */
@Suppress("DEPRECATION")
internal class SensorRig(application: Application) {

    val manager: SensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val shadow: ShadowSensorManager = shadowOf(manager)
    private val byType: MutableMap<Int, Sensor> = mutableMapOf()

    fun add(type: Int): Sensor {
        val sensor: Sensor = ShadowSensor.newInstance(type)
        shadow.addSensor(sensor)
        byType[type] = sensor
        return sensor
    }

    fun sensor(type: Int): Sensor = byType.getValue(type)

    fun event(type: Int, values: FloatArray, accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH): SensorEvent =
        SensorEventBuilder.newBuilder()
            .setSensor(sensor(type))
            .setValues(values)
            .setAccuracy(accuracy)
            .setTimestamp(0L)
            .build()

    /** The listeners the manager currently holds, each counted once. */
    val registeredListeners: Int get() = shadow.listeners.toSet().size
}

/** A unit quaternion (Hamilton product); the matrix it gives maps device coordinates to world coordinates. */
internal class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {

    operator fun times(o: Quat): Quat = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** Row-major 3x3: `world = R * device`; world X is east, Y north, Z up. */
    fun matrix(): Array<DoubleArray> = arrayOf(
        doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)),
        doubleArrayOf(2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)),
        doubleArrayOf(2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)),
    )

    /** `[x, y, z, w]`, plus the heading accuracy in radians when [accuracyRadians] is given. */
    fun sensorValues(accuracyRadians: Float? = null): FloatArray =
        if (accuracyRadians == null) {
            floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
        } else {
            floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat(), accuracyRadians)
        }

    companion object {
        fun axisAngle(ax: Double, ay: Double, az: Double, degrees: Double): Quat {
            val half: Double = degrees * PI / 360.0
            return Quat(ax * sin(half), ay * sin(half), az * sin(half), cos(half))
        }

        /**
         * The device pointing at compass bearing [yawDegrees] (its Y axis, flat), then pitched up by
         * [pitchDegrees] about its own X axis and rolled by [rollDegrees] about its own Y axis. Yaw is a
         * rotation about world Z by minus the bearing (clockwise from above).
         */
        fun of(yawDegrees: Double, pitchDegrees: Double = 0.0, rollDegrees: Double = 0.0): Quat =
            axisAngle(0.0, 0.0, 1.0, -yawDegrees) * axisAngle(1.0, 0.0, 0.0, pitchDegrees) * axisAngle(0.0, 1.0, 0.0, rollDegrees)
    }
}

/**
 * The compass bearing in `[0, 360)` of the top edge of the screen AS DRAWN, from first principles: with
 * the device turned counter-clockwise by [rotation] from its natural orientation, the drawn top edge lies
 * along the device's +Y axis (natural), +X (90), -Y (180) or -X (270), and its heading is the bearing of
 * that axis' horizontal projection. `null` when that axis is nearly vertical (no horizontal direction).
 */
internal fun expectedAzimuth(q: Quat, rotation: ScreenRotation): Double? {
    val r: Array<DoubleArray> = q.matrix()
    val column: Int = when (rotation) {
        ScreenRotation.NATURAL, ScreenRotation.ROTATION_180 -> 1
        ScreenRotation.ROTATION_90, ScreenRotation.ROTATION_270 -> 0
    }
    val sign: Double = when (rotation) {
        ScreenRotation.NATURAL, ScreenRotation.ROTATION_90 -> 1.0
        ScreenRotation.ROTATION_180, ScreenRotation.ROTATION_270 -> -1.0
    }
    val east: Double = sign * r[0][column]
    val north: Double = sign * r[1][column]
    if (hypot(east, north) < 0.2) return null
    return normalizeDegrees(atan2(east, north) * 180.0 / PI)
}

/** Gravity and geomagnetic vectors as the device would measure them for orientation [q] (world field: 20 uT north, 40 uT down). */
internal fun rawVectors(q: Quat): Pair<FloatArray, FloatArray> {
    val r: Array<DoubleArray> = q.matrix()
    fun toDevice(worldX: Double, worldY: Double, worldZ: Double): FloatArray = FloatArray(3) { axis: Int ->
        (r[0][axis] * worldX + r[1][axis] * worldY + r[2][axis] * worldZ).toFloat()
    }
    return toDevice(0.0, 0.0, 9.81) to toDevice(0.0, 20.0, -40.0)
}

/** `Sensor.TYPE_*` of the pipelines, by name. */
internal object SensorTypes {
    const val ROTATION_VECTOR: Int = Sensor.TYPE_ROTATION_VECTOR
    const val GEOMAGNETIC_ROTATION_VECTOR: Int = Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
    const val ACCELEROMETER: Int = Sensor.TYPE_ACCELEROMETER
    const val MAGNETIC_FIELD: Int = Sensor.TYPE_MAGNETIC_FIELD
}

/** A [Logger] that records every message of every level. */
internal class RecordingLogger : Logger {
    private val entries: AtomicReference<List<Pair<LogLevel, String>>> = AtomicReference(emptyList())

    override val tag: String = "RecordingLogger"

    val messages: List<Pair<LogLevel, String>> get() = entries.load()

    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        val line: String = message()
        while (true) {
            val before: List<Pair<LogLevel, String>> = entries.load()
            if (entries.compareAndSet(before, before + (level to line))) return
        }
    }
}

/**
 * A scripted [HeadingSensors]: the sensors it was given, a compare-and-set list of what is registered (so
 * the counts are safe to read while threads deliver), counters of every call, and hooks to refuse, throw or
 * block a registration at an exact point.
 */
internal class ScriptedSensors(private val sensors: Map<Int, Sensor>) : HeadingSensors {

    val registerCalls: AtomicInt = AtomicInt(0)
    val unregisterCalls: AtomicInt = AtomicInt(0)
    private val live: AtomicReference<List<Pair<SensorEventListener, Sensor>>> = AtomicReference(emptyList())

    /** Runs at the start of each `register` (1-based call number); may throw, or block to hold a registration open. */
    @Volatile
    var beforeRegister: (call: Int, sensor: Sensor) -> Unit = { _, _ -> }

    /** Runs after a registration was recorded (1-based call number), on the registering thread. */
    @Volatile
    var afterRegister: (call: Int) -> Unit = { }

    /** Answers `false` for the sensor of this type (the platform refusing to enable it). */
    @Volatile
    var refuseType: Int? = null

    val liveListeners: List<SensorEventListener> get() = live.load().map { it.first }.distinct()
    val liveRegistrations: Int get() = live.load().size
    val registeredSensorTypes: List<Int> get() = live.load().map { it.second.type }

    override fun defaultSensor(type: Int): Sensor? = sensors[type]

    override fun register(listener: SensorEventListener, sensor: Sensor): Boolean {
        val call: Int = registerCalls.incrementAndFetch()
        beforeRegister(call, sensor)
        if (sensor.type == refuseType) return false
        while (true) {
            val before: List<Pair<SensorEventListener, Sensor>> = live.load()
            if (live.compareAndSet(before, before + (listener to sensor))) break
        }
        afterRegister(call)
        return true
    }

    override fun unregister(listener: SensorEventListener) {
        unregisterCalls.incrementAndFetch()
        while (true) {
            val before: List<Pair<SensorEventListener, Sensor>> = live.load()
            if (live.compareAndSet(before, before.filter { it.first !== listener })) return
        }
    }

    /** Delivers [event] to every listener registered for its sensor, the way the platform fans out. */
    fun deliver(event: SensorEvent) {
        live.load().filter { it.second.type == event.sensor.type }.forEach { it.first.onSensorChanged(event) }
    }
}

/** A scripted [HeadingPositions]: a settable newest position, an optional registration failure, thread-safe counters. */
internal class ScriptedPositions : HeadingPositions {

    @Volatile
    var newest: DeclinationPosition? = null

    @Volatile
    var registerFailure: Exception? = null

    /** Runs at the start of each `register` (1-based call number); may block to hold a registration open. */
    @Volatile
    var beforeRegister: (call: Int) -> Unit = { }

    val newestCalls: AtomicInt = AtomicInt(0)
    val registerCalls: AtomicInt = AtomicInt(0)
    val unregisterCalls: AtomicInt = AtomicInt(0)
    private val live: AtomicReference<List<android.location.LocationListener>> = AtomicReference(emptyList())

    val liveListeners: List<android.location.LocationListener> get() = live.load()

    override fun newestPosition(): DeclinationPosition? {
        newestCalls.incrementAndFetch()
        return newest
    }

    override fun register(listener: android.location.LocationListener) {
        val call: Int = registerCalls.incrementAndFetch()
        beforeRegister(call)
        registerFailure?.let { failure: Exception -> throw failure }
        while (true) {
            val before: List<android.location.LocationListener> = live.load()
            if (live.compareAndSet(before, before + listener)) return
        }
    }

    override fun unregister(listener: android.location.LocationListener) {
        unregisterCalls.incrementAndFetch()
        while (true) {
            val before: List<android.location.LocationListener> = live.load()
            if (live.compareAndSet(before, before.filter { it !== listener })) return
        }
    }

    fun deliver(location: android.location.Location) {
        live.load().forEach { listener: android.location.LocationListener -> listener.onLocationChanged(location) }
    }
}
