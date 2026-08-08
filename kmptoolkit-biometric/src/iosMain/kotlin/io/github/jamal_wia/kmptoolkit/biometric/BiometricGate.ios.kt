package io.github.jamal_wia.kmptoolkit.biometric

import kotlin.coroutines.resume
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.LocalAuthentication.LABiometryTypeNone
import platform.LocalAuthentication.LAContext

/**
 * Creates the iOS [BiometricGate], backed by `LocalAuthentication`'s `LAContext`.
 *
 * Build it once and pass the resulting [BiometricGate] into shared code. Nothing is retained
 * between calls: each authentication gets a fresh `LAContext`, because a context caches its own
 * successful evaluation and reusing one would let a second `authenticate` succeed without asking
 * the user anything.
 *
 * Your app **must** declare `NSFaceIDUsageDescription` in its `Info.plist` if the device has Face
 * ID. Without it iOS terminates the app the first time you evaluate a policy — this is not
 * something a library can supply on your behalf, since the string is user-facing copy in your
 * language. See `docs/kmptoolkit-biometric/05-platform-notes.md`.
 *
 * @param config which credentials count; see [BiometricGateConfig].
 *   [BiometricGateConfig.requireExplicitConfirmation] is Android-only and ignored here.
 */
public fun createBiometricGate(config: BiometricGateConfig = BiometricGateConfig()): BiometricGate =
    IosBiometricGate(config)

/**
 * `LAContext`-backed gate.
 *
 * The `NSError**` out-parameter of `canEvaluatePolicy` maps to a pointer to an Objective-C object
 * variable in Kotlin/Native — there is no `NSErrorPointer` binding, that being a Swift type — so it
 * is allocated in a `memScoped` block and read back after the call returns.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosBiometricGate(private val config: BiometricGateConfig) : BiometricGate {

    override suspend fun availability(): BiometricAvailability {
        val context = LAContext()
        return memScoped {
            val errorVar: ObjCObjectVar<NSError?> = alloc()
            if (context.canEvaluatePolicy(config.laPolicy(), errorVar.ptr)) {
                BiometricAvailability.Available
            } else {
                // biometryType is only meaningful after canEvaluatePolicy has run — before that it
                // is LABiometryTypeNone on every device, enrolled or not.
                mapAvailabilityError(
                    code = errorVar.value?.code,
                    biometryAbsent = context.biometryType == LABiometryTypeNone,
                )
            }
        }
    }

    override suspend fun authenticate(prompt: BiometricPromptText): BiometricResult =
        suspendCancellableCoroutine { continuation ->
            val context = LAContext()
            context.localizedCancelTitle = prompt.cancelLabel
            if (config.policy == BiometricPolicy.BIOMETRIC_ONLY) {
                // An empty fallback title hides the fallback button. The library has no label from
                // the consumer to put on it, and a button leading nowhere is worse than no button:
                // this gate does not accept the passcode, so tapping it can only end the prompt.
                context.localizedFallbackTitle = ""
            }
            // `title` has no slot in iOS's prompt — the OS puts the app's own name there — so it is
            // deliberately unused, and `subtitle` becomes the localizedReason, the one string iOS
            // renders. Documented on BiometricPromptText.
            context.evaluatePolicy(config.laPolicy(), prompt.subtitle) { success, error ->
                if (!continuation.isActive) return@evaluatePolicy
                continuation.resume(
                    if (success) BiometricResult.Authenticated else mapAuthenticationError(error?.code),
                )
            }
            continuation.invokeOnCancellation {
                // Dismisses the sheet; the evaluatePolicy callback then arrives with
                // LAErrorAppCancel and is dropped by the isActive check above.
                context.invalidate()
            }
        }
}
