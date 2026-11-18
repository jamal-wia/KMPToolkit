package io.github.jamal_wia.kmptoolkit.systembars

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.children
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android 8.0 (API 26) and later read the window with `PixelCopy`; API 24-25 have no window copy and
 * keep the layer reader behind a `WebView` guard. The branch is on a constant that never changes
 * during a process, so the composable's structure is stable.
 */
@Composable
internal actual fun rememberBarStripReader(): BarStripReader? {
    val view: View = LocalView.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        remember(view) { PixelCopyBarStripReader(view) }
    } else {
        val layerReader: LayerBarStripReader = rememberLayerBarStripReader()
        remember(view, layerReader) { WebViewGuardedBarStripReader(view, layerReader) }
    }
}

/** One window copy: the platform's `PixelCopy.request`, kept behind a seam so a test can hold its listener. */
internal fun interface WindowCopier {

    /** Same contract as `PixelCopy.request(Window, Rect, Bitmap, listener, Handler)`, including its `IllegalArgumentException`. */
    fun copy(
        window: Window,
        source: Rect,
        destination: Bitmap,
        listener: PixelCopy.OnPixelCopyFinishedListener,
        handler: Handler,
    )
}

/** The real thing. */
@RequiresApi(Build.VERSION_CODES.O)
private object PlatformWindowCopier : WindowCopier {
    override fun copy(
        window: Window,
        source: Rect,
        destination: Bitmap,
        listener: PixelCopy.OnPixelCopyFinishedListener,
        handler: Handler,
    ) {
        PixelCopy.request(window, source, destination, listener, handler)
    }
}

/**
 * API 26+: copies the window this composition draws into with `PixelCopy`; draws nothing extra.
 *
 * One request copies the whole window into an `8 × R` bitmap (see [barStripRows]), so both bars come
 * from one buffer and there is one listener, one cancellation and no partial-failure state. A fresh
 * destination and a fresh source rectangle belong to each request: the framework offsets the
 * rectangle in place, and a request that outlives its caller (cancelled mid-copy) may still be
 * writing the bitmap when the caller is gone.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class PixelCopyBarStripReader(
    private val view: View,
    private val copier: WindowCopier = PlatformWindowCopier,
) : BarStripReader {

    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    override fun ContentDrawScope.drawContentAndCapture() {
        drawContent()
    }

    override suspend fun read(statusPx: Int, navPx: Int): BarStripStyles? {
        // Resolved at every read, never cached: the view may move between windows (a recreated
        // Activity), and a stale Window would copy a surface nobody draws into any more.
        val window: Window = view.hostWindow() ?: return null
        val decor: View = window.peekDecorView() ?: return null
        // A Popup, an overlay or another window: this composition does not draw into this window's buffer.
        if (view.rootView !== decor) return null
        val width: Int = decor.width
        val height: Int = decor.height
        if (width <= 0 || height <= 0) return null
        val rows: BarStripRows = barStripRows(height, statusPx, navPx) ?: return null

        val destination: Bitmap = Bitmap.createBitmap(SAMPLE_COLS, rows.destRows, Bitmap.Config.ARGB_8888)
        val source = Rect(0, 0, width, height)
        val result: Int = suspendCancellableCoroutine { continuation ->
            try {
                copier.copy(
                    window,
                    source,
                    destination,
                    // Called once, on the main looper. A call after the caller was cancelled is
                    // ignored: the bitmap belongs to this one request and nobody reads it.
                    { code -> if (continuation.isActive) continuation.resume(code) },
                    mainHandler,
                )
            } catch (rejected: IllegalArgumentException) {
                // No decor, no backing surface yet, an empty rectangle, an immutable bitmap: a skip.
                continuation.resume(REQUEST_REJECTED)
            }
        }
        if (result != PixelCopy.SUCCESS) return null

        val map: PixelMap = destination.asImageBitmap().toPixelMap()
        return BarStripStyles(status = rows.status.styleOrNull(map), nav = rows.nav.styleOrNull(map))
    }

    private fun IntRange.styleOrNull(map: PixelMap): SystemBarIconStyle? =
        if (isEmpty()) null else sampleRows(map, startY = first, rows = count())

    private companion object {
        /** Not a `PixelCopy` result code: the request itself was refused. */
        const val REQUEST_REJECTED: Int = -1
    }
}

/**
 * API 24-25: [inner] (the layer reader), except that a sample is skipped while [view]'s subtree
 * holds an attached `WebView`. The layer reader replays the recorded content in a second hardware
 * renderer, which blanks a `WebView`; this window has no `PixelCopy` to read instead.
 *
 * The walk and the inner read's offscreen draw run in the same main-thread turn: on those levels
 * the inner draw runs synchronously before the first suspension.
 */
internal class WebViewGuardedBarStripReader(
    private val view: View,
    private val inner: BarStripReader,
) : BarStripReader {

    override fun ContentDrawScope.drawContentAndCapture() {
        with(inner) { drawContentAndCapture() }
    }

    override suspend fun read(statusPx: Int, navPx: Int): BarStripStyles? =
        if (view.hostsAttachedWebView()) null else inner.read(statusPx, navPx)
}

/**
 * True if this view or any descendant is an attached `android.webkit.WebView` (subclasses included).
 * Visibility is deliberately not tested: the layer replays the display list recorded at the last
 * frame, which still holds the functor of a `WebView` made invisible since.
 */
internal fun View.hostsAttachedWebView(): Boolean =
    (this is WebView && isAttachedToWindow) || (this is ViewGroup && children.any { it.hostsAttachedWebView() })

/**
 * The window this view draws into: a Compose dialog's window through [DialogWindowProvider] (the
 * rule `applyDialogWindowSystemBars` uses), else the hosting Activity's, found through the
 * `ContextWrapper` chain. `null` when there is neither.
 */
internal fun View.hostWindow(): Window? {
    (parent as? DialogWindowProvider)?.window?.let { return it }
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current.window
        current = current.baseContext
    }
    return null
}
