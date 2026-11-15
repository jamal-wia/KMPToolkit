package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor
import java.util.function.Consumer

/** What one `LocationManager.requestLocationUpdates` registration asks for. */
internal class UpdatesRequest(
    val minTimeMillis: Long,
    val minDistanceMeters: Float,
    /** Batching (API 31 and later); `0` means none. */
    val maxUpdateDelayMillis: Long,
    /** Quality hint for the framework request (API 31 and later); ignored below. */
    val accuracy: LocationAccuracy,
)

/**
 * The part of `LocationManager` (and of the permission check, the boot clock and the service-state
 * broadcasts) that [AndroidLocationProvider] uses, as a seam: the framework class cannot be subclassed
 * in a unit test, and the order of a registration against a delivery or a provider flap is exactly what
 * the tests of this module have to control.
 *
 * Every callback is delivered on the main looper. Registrations throw [SecurityException] when the
 * platform refuses the caller; nothing is registered in that case.
 */
internal interface LocationBackend {

    /** Whether the device has [provider]; `FUSED_PROVIDER` is absent below API 31. */
    fun hasProvider(provider: String): Boolean

    /** Whether [provider] is switched on. */
    fun isProviderEnabled(provider: String): Boolean

    /** The device-wide location switch (`isLocationEnabled` on API 28 and later, else any of gps/network on). */
    fun isLocationEnabled(): Boolean

    fun hasFinePermission(): Boolean

    fun hasCoarsePermission(): Boolean

    /** The platform's cached fix of [provider], or `null`. */
    fun lastKnownLocation(provider: String): Location?

    /** Registers [listener] for [provider]; a registration stays until [removeUpdates]. */
    fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener)

    /** Removes every registration of [listener]; removing one that is not registered is a no-op. */
    fun removeUpdates(listener: LocationListener)

    /**
     * The framework's one-shot `getCurrentLocation` (API 30 and later): [onResult] is called once, with
     * `null` when the platform found no fix. Returns the call's cancel function, or `null` when this API
     * level has no such call (the caller then registers for updates instead).
     */
    fun requestCurrentLocation(provider: String, accuracy: LocationAccuracy, onResult: (Location?) -> Unit): (() -> Unit)?

    /**
     * Calls [onChange] on every `MODE_CHANGED_ACTION` / `PROVIDERS_CHANGED_ACTION` broadcast until the
     * returned function is called. Calling that function twice is harmless.
     */
    fun observeServiceChanges(onChange: () -> Unit): () -> Unit

    /** `SystemClock.elapsedRealtimeNanos()`: the clock fix ages are judged on (PLAN D10). */
    fun elapsedRealtimeNanos(): Long
}

@SuppressLint("MissingPermission")
internal class ManagerLocationBackend(
    private val context: Context,
    private val manager: LocationManager,
) : LocationBackend {

    private val mainHandler: Handler = Handler(Looper.getMainLooper())
    private val mainExecutor: Executor = Executor { command: Runnable -> mainHandler.post(command) }

    override fun hasProvider(provider: String): Boolean =
        if (provider == LocationManager.FUSED_PROVIDER && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            false
        } else {
            provider in manager.allProviders
        }

    override fun isProviderEnabled(provider: String): Boolean = manager.isProviderEnabled(provider)

    override fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

    override fun hasFinePermission(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION)

    override fun hasCoarsePermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun lastKnownLocation(provider: String): Location? = manager.getLastKnownLocation(provider)

    override fun requestUpdates(provider: String, request: UpdatesRequest, listener: LocationListener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val built: LocationRequest = LocationRequest.Builder(request.minTimeMillis)
                .setQuality(request.accuracy.toQuality())
                .setMinUpdateDistanceMeters(request.minDistanceMeters)
                .setMaxUpdateDelayMillis(request.maxUpdateDelayMillis)
                .build()
            manager.requestLocationUpdates(provider, built, mainExecutor, listener)
        } else {
            manager.requestLocationUpdates(
                provider,
                request.minTimeMillis,
                request.minDistanceMeters,
                listener,
                Looper.getMainLooper(),
            )
        }
    }

    override fun removeUpdates(listener: LocationListener) = manager.removeUpdates(listener)

    override fun requestCurrentLocation(
        provider: String,
        accuracy: LocationAccuracy,
        onResult: (Location?) -> Unit,
    ): (() -> Unit)? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val signal = CancellationSignal()
        val consumer: Consumer<Location?> = Consumer { location: Location? -> onResult(location) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val built: LocationRequest = LocationRequest.Builder(0L).setQuality(accuracy.toQuality()).build()
            manager.getCurrentLocation(provider, built, signal, mainExecutor, consumer)
        } else {
            manager.getCurrentLocation(provider, signal, mainExecutor, consumer)
        }
        return { signal.cancel() }
    }

    override fun observeServiceChanges(onChange: () -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onChange()
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return {
            // Unregistering a receiver that is already gone throws IllegalArgumentException: harmless here.
            try {
                context.unregisterReceiver(receiver)
            } catch (alreadyGone: IllegalArgumentException) {
                // nothing to release
            }
        }
    }

    override fun elapsedRealtimeNanos(): Long = android.os.SystemClock.elapsedRealtimeNanos()

    private fun LocationAccuracy.toQuality(): Int = when (this) {
        LocationAccuracy.HIGH_ACCURACY -> LocationRequest.QUALITY_HIGH_ACCURACY
        LocationAccuracy.BALANCED -> LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
        LocationAccuracy.LOW_POWER -> LocationRequest.QUALITY_LOW_POWER
    }
}
