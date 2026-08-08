package io.github.jamal_wia.kmptoolkit.platform.testing

import io.github.jamal_wia.kmptoolkit.platform.url.UrlOpener
import io.github.jamal_wia.kmptoolkit.platform.url.UrlOpenResult

/**
 * A [UrlOpener] that opens nothing and records every URL it was asked for.
 *
 * The usual assertion is that a screen handed the right URL to the platform, which is otherwise
 * untestable — a real opener leaves the app:
 *
 * ```kotlin
 * val opener = RecordingUrlOpener()
 * TermsScreen(opener).onPrivacyClicked()
 * assertEquals(listOf("https://example.com/privacy"), opener.openedUrls)
 * ```
 *
 * @param result what [open] returns. Set it to [UrlOpenResult.NO_HANDLER] to exercise the path
 *   where the device has nothing that can open the link.
 */
public class RecordingUrlOpener(
    public var result: UrlOpenResult = UrlOpenResult.OPENED,
) : UrlOpener {

    private val mutableOpenedUrls: MutableList<String> = mutableListOf()

    /** Every URL passed to [open], in order — including ones [result] reported as failing. */
    public val openedUrls: List<String> get() = mutableOpenedUrls.toList()

    /** The most recent URL, or `null` if [open] has not been called. */
    public val lastUrl: String? get() = mutableOpenedUrls.lastOrNull()

    override fun open(url: String): UrlOpenResult {
        mutableOpenedUrls.add(url)
        return result
    }
}
