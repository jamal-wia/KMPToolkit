package io.github.jamal_wia.kmptoolkit.platform.url

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.w

/**
 * Creates the Android [UrlOpener], backed by `Intent.ACTION_VIEW`.
 *
 * Only the application context is retained, so passing an `Activity` here is harmless — but note
 * the consequence: launching from a non-activity context requires `FLAG_ACTIVITY_NEW_TASK`, so the
 * opened app starts in its own task rather than on top of yours. That is the right behavior for a
 * handoff, and it is the only behavior available without holding an activity.
 *
 * No permission is required. Android 11+ package visibility does not restrict `ACTION_VIEW` for
 * `http`/`https`, but a custom scheme whose handler is not visible to your app resolves to
 * [UrlOpenResult.NO_HANDLER]; see `docs/kmptoolkit-platform/05-platform-notes.md`.
 *
 * @param context any `Context`; its application context is what gets retained.
 * @param logger where a rejected launch is reported. Defaults to discarding it — the returned
 *   [UrlOpenResult] is the API-level signal.
 */
public fun createUrlOpener(context: Context, logger: Logger = NoopLogger): UrlOpener =
    AndroidUrlOpener(context.applicationContext, logger)

private class AndroidUrlOpener(
    private val context: Context,
    private val logger: Logger,
) : UrlOpener {

    override fun open(url: String): UrlOpenResult {
        if (!isAbsoluteUrl(url)) return UrlOpenResult.INVALID_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            UrlOpenResult.OPENED
        }.getOrElse { cause ->
            logger.w(cause) { "Could not open a URL" }
            if (cause is ActivityNotFoundException) UrlOpenResult.NO_HANDLER else UrlOpenResult.FAILED
        }
    }
}
