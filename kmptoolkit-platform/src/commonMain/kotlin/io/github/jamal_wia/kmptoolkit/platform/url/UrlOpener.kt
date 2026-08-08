package io.github.jamal_wia.kmptoolkit.platform.url

/**
 * Hands an absolute URL to the platform to open in whatever app claims it — a browser for `https`,
 * the mail composer for `mailto`, the dialer for `tel`.
 *
 * Obtain one from the platform factory (`createUrlOpener(context)` on Android, `createUrlOpener()`
 * on iOS) and pass it into shared code as this interface.
 *
 * This is a one-way handoff, not navigation: control leaves your app, and nothing here reports
 * what the user did next. If you need an in-app browser with a result, this is the wrong seam.
 */
public interface UrlOpener {

    /**
     * Asks the platform to open [url].
     *
     * Never throws — a bad URL from a server response or a device with no browser is a condition
     * to handle, not a crash. Every failure mode comes back as a [UrlOpenResult].
     *
     * @param url an absolute URL with a scheme (`https://…`, `mailto:…`). A relative path or a
     *   bare host is rejected with [UrlOpenResult.INVALID_URL] rather than guessed at, since
     *   guessing a scheme is how a link meant to be `https` silently opens as `http`.
     */
    public fun open(url: String): UrlOpenResult
}

/**
 * What became of an [UrlOpener.open] call.
 *
 * Typed rather than a message, because what to show a user — a toast, an inline error, nothing at
 * all — is the app's decision and the app owns its wording. See `docs/01-architecture.md`.
 */
public enum class UrlOpenResult {

    /**
     * The platform accepted the URL and started launching a handler.
     *
     * A statement about the handoff, not about the user: the browser may still fail to load the
     * page, and the user may come straight back. Neither platform reports that, so neither does
     * this module.
     */
    OPENED,

    /**
     * [UrlOpener.open] was given something that is not an absolute URL — empty, whitespace, a
     * relative path, or a string with no scheme — and it was rejected before the platform saw it.
     *
     * This is a bug in the calling code or bad data from a server, not a device condition.
     */
    INVALID_URL,

    /**
     * The URL parsed, but nothing on the device is registered to open it: a custom scheme whose
     * app is not installed, or (rarely) a device with no browser at all.
     *
     * On Android this is `ActivityNotFoundException`; on iOS it is `canOpenURL` returning false,
     * which for a non-standard scheme also requires the scheme to be listed in the app's
     * `LSApplicationQueriesSchemes` — see `docs/kmptoolkit-platform/05-platform-notes.md`.
     */
    NO_HANDLER,

    /**
     * The platform refused the launch for some other reason — a security restriction, a
     * background-launch block, a dead system service.
     *
     * Unlike [NO_HANDLER] it says nothing about whether the same URL would work later.
     */
    FAILED,
}
