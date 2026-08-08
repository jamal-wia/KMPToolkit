package io.github.jamal_wia.kmptoolkit.platform.url

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Creates the iOS [UrlOpener], backed by `UIApplication.openURL(_:options:completionHandler:)`.
 *
 * `canOpenURL` is consulted first so that a scheme nothing handles comes back as
 * [UrlOpenResult.NO_HANDLER] rather than as a silent no-op. Note the iOS-specific catch: for a
 * **custom** scheme, `canOpenURL` returns false unless that scheme is listed in your
 * `LSApplicationQueriesSchemes`, so a `myapp://` link fails here until you declare it. `http`,
 * `https`, `mailto` and `tel` need no declaration. See
 * `docs/kmptoolkit-platform/05-platform-notes.md`.
 *
 * The open itself is dispatched to the main queue — `UIApplication` is main-thread-only — so
 * [UrlOpenResult.OPENED] means "handed to UIKit", not "the other app is on screen".
 *
 * @param logger where a rejected URL is reported.
 */
public fun createUrlOpener(logger: Logger = NoopLogger): UrlOpener = IosUrlOpener(logger)

private class IosUrlOpener(private val logger: Logger) : UrlOpener {

    override fun open(url: String): UrlOpenResult {
        if (!isAbsoluteUrl(url)) return UrlOpenResult.INVALID_URL
        val nsUrl: NSURL = NSURL.URLWithString(url) ?: return UrlOpenResult.INVALID_URL
        val application: UIApplication = UIApplication.sharedApplication
        if (!application.canOpenURL(nsUrl)) {
            logger.w { "No installed app handles this URL scheme" }
            return UrlOpenResult.NO_HANDLER
        }
        dispatch_async(dispatch_get_main_queue()) {
            application.openURL(nsUrl, options = emptyMap<Any?, Any?>(), completionHandler = null)
        }
        return UrlOpenResult.OPENED
    }
}
