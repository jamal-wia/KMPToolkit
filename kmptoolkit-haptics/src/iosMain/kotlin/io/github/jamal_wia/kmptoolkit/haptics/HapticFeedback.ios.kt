package io.github.jamal_wia.kmptoolkit.haptics

import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [HapticFeedback], on top of UIKit's feedback generators.
 *
 * No permission, entitlement, or `Info.plist` entry is involved on iOS. There is also nothing to
 * release: the instance is stateless and allocates its generator per call.
 *
 * Every call returns [HapticResult.PERFORMED] — UIKit exposes no way to ask whether the device has
 * a Taptic Engine, whether the user has haptics enabled, or whether a request was honored. On a
 * device without the hardware (or in the simulator) the request is accepted and nothing is felt.
 */
public fun createHapticFeedback(): HapticFeedback = IosHapticFeedback()

@OptIn(ExperimentalForeignApi::class)
internal class IosHapticFeedback : HapticFeedback {

    override fun perform(type: HapticType): HapticResult {
        // UIKit's feedback generators are UIKit objects and must be used on the main thread. The
        // hop is unconditional rather than "only if we are off-main": dispatch_async from the main
        // thread just queues the block for the current runloop turn, which is cheap, and the
        // alternative would mean two code paths for a call that is already asynchronous by nature.
        dispatch_async(dispatch_get_main_queue()) {
            when (type) {
                HapticType.LIGHT -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
                HapticType.MEDIUM -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
                HapticType.HEAVY -> impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy)
                HapticType.SUCCESS ->
                    notify(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
                HapticType.WARNING ->
                    notify(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
                HapticType.ERROR ->
                    notify(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            }
        }
        return HapticResult.PERFORMED
    }

    // Generators are created per call instead of cached. Apple recommends keeping one alive when
    // firing in a tight loop, but these fire on discrete user actions, and a cached generator would
    // be main-thread-confined shared state for no measurable gain.
    private fun impact(style: UIImpactFeedbackStyle) {
        val generator = UIImpactFeedbackGenerator(style = style)
        generator.prepare()
        generator.impactOccurred()
    }

    private fun notify(type: UINotificationFeedbackType) {
        val generator = UINotificationFeedbackGenerator()
        generator.prepare()
        generator.notificationOccurred(type)
    }
}
