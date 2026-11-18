package io.github.jamal_wia.kmptoolkit.sample

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jamal_wia.kmptoolkit.sample.ui.theme.KMPToolkitTheme
import io.github.jamal_wia.kmptoolkit.systembars.AutoSystemBarsIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.StatusBarLuminanceProbe
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.createStatusBarLuminanceProbe
import io.github.jamal_wia.kmptoolkit.systembars.createSystemBarsController
import java.util.concurrent.atomic.AtomicInteger

/**
 * The manual-check screen of RELEASING.md's "Systembars device check": a full-screen `WebView` under
 * `AutoSystemBarsIconStyle`, the situation of issue 13. Nothing here runs unattended; a person
 * installs the sample, opens this screen from the main screen and follows the rows of that section.
 *
 * Every sample attempt of the probe is logged to logcat under [PROBE_TAG] (attempt number, duration
 * and the icon styles the controller holds right after it). **The screen deliberately shows no state
 * that changes per sample**: writing Compose state from `onSampled` would recompose and redraw the
 * probe's root on every sample, recreating the idle feedback loop and failing the idle row because of
 * this sample app instead of the library. The public API does not say whether a read succeeded, so
 * a release after three skipped samples shows only as the logged styles falling back to the base
 * configuration while the content under the bars has not changed.
 *
 * Three toggles sit over the page: "Theme" flips the page's light and dark sections and calls
 * `triggerRecalculation()`; "Secure" sets or clears `FLAG_SECURE` on this window; "Photo" replaces
 * the `WebView` with a bundled picture drawn by Compose (the `WebView` leaves the composition and is
 * detached, which the API 24-25 guard needs: hiding it is not enough, see `05-platform-notes.md`).
 */
class WebViewProbeActivity : ComponentActivity() {

    private lateinit var systemBars: SystemBarsController

    override fun onCreate(savedInstanceState: Bundle?) {
        // The explicit form that docs/kmptoolkit-systembars/05-platform-notes.md recommends over the
        // no-argument one: transparent scrims, and no navigation-bar contrast enforcement.
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = Color.TRANSPARENT,
                darkScrim = Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)

        // Before setContent, so before this activity resumes: the controller learns its window from
        // the resume callback and cannot reach one that resumed before it existed.
        systemBars = createSystemBarsController(this)
        val probe: StatusBarLuminanceProbe = createStatusBarLuminanceProbe()

        setContent {
            KMPToolkitTheme {
                // Not Compose state: a state write here would recompose the probe on every sample.
                val attempts: AtomicInteger = remember { AtomicInteger() }
                AutoSystemBarsIconStyle(
                    controller = systemBars,
                    probe = probe,
                    onSampled = { durationNanos: Long ->
                        Log.d(
                            PROBE_TAG,
                            "attempt=${attempts.incrementAndGet()} durationNanos=$durationNanos " +
                                "status=${systemBars.currentConfig.statusBarIcons} " +
                                "navigation=${systemBars.currentConfig.navigationBarIcons}",
                        )
                    },
                ) {
                    ProbeScreen(
                        probe = probe,
                        onSecureChange = { secure: Boolean ->
                            if (secure) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        systemBars.release()
        super.onDestroy()
    }
}

private const val PROBE_TAG: String = "ProbeCheck"

/** Holds the page's `WebView` while it is in the composition; plain field, never Compose state. */
private class WebViewHolder {
    var view: WebView? = null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ProbeScreen(probe: StatusBarLuminanceProbe, onSecureChange: (Boolean) -> Unit) {
    var flipped: Boolean by remember { mutableStateOf(false) }
    var secure: Boolean by remember { mutableStateOf(false) }
    var photo: Boolean by remember { mutableStateOf(false) }
    val holder: WebViewHolder = remember { WebViewHolder() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (photo) {
            Image(
                painter = painterResource(R.drawable.probe_photo),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        // Enabled for the theme toggle only (evaluateJavascript). The page is inline and
                        // loads nothing: the sample declares no INTERNET permission.
                        settings.javaScriptEnabled = true
                        loadDataWithBaseURL(null, probeHtml(flipped), "text/html", "utf-8", null)
                        holder.view = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    holder.view = null
                    view.destroy()
                },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    val next: Boolean = !flipped
                    flipped = next
                    holder.view?.evaluateJavascript("document.body.classList.toggle('flip', $next)", null)
                    probe.triggerRecalculation()
                },
            ) {
                Text("Theme")
            }
            FilledTonalButton(
                onClick = {
                    val next: Boolean = !secure
                    secure = next
                    onSecureChange(next)
                },
            ) {
                Text(if (secure) "Secure: on" else "Secure: off")
            }
            FilledTonalButton(onClick = { photo = !photo }) {
                Text(if (photo) "Photo: on" else "Photo: off")
            }
        }
    }
}

/**
 * The page: a sticky header with a translucent, blurred backdrop (the `backdrop-filter` layer of the
 * report), followed by alternating light and dark sections. The header sticks 64 px below the top so
 * it does not sit under the status bar and decide that bar's icons on its own; each section is two
 * viewports tall so that both bars can be under one section while it is scrolled. [flipped] swaps the
 * light and dark sections. No external resource.
 */
private fun probeHtml(flipped: Boolean): String {
    val paragraph: String = "<p>Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod " +
        "tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud " +
        "exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.</p>"
    val body: String = paragraph.repeat(PARAGRAPHS_PER_SECTION)
    val sections: String = (1..SECTION_PAIRS).joinToString(separator = "") { pair ->
        "<section class=\"light\"><h1>Light section $pair</h1>$body</section>" +
            "<section class=\"dark\"><h1>Dark section $pair</h1>$body</section>"
    }
    return """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body { margin: 0; font: 16px/1.5 sans-serif; }
        header { position: sticky; top: 64px; z-index: 1; display: flex; gap: 12px; padding: 12px 16px;
                 backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
                 background: rgba(255, 255, 255, .5); }
        header button { font: inherit; padding: 8px 14px; }
        section { height: 200vh; padding: 16px; box-sizing: border-box; }
        .light { background: #fafafa; color: #111; }
        .dark { background: #111; color: #fafafa; }
        body.flip .light { background: #111; color: #fafafa; }
        body.flip .dark { background: #fafafa; color: #111; }
        </style>
        </head>
        <body${if (flipped) " class=\"flip\"" else ""}>
        <header><button>One</button><button>Two</button></header>
        $sections
        </body>
        </html>
    """.trimIndent()
}

private const val PARAGRAPHS_PER_SECTION: Int = 12
private const val SECTION_PAIRS: Int = 3
