package io.github.jamal_wia.kmptoolkit.systembars

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Wraps [content] with an automatic system-bar icon-style probe for **both** the status bar and the
 * navigation bar.
 *
 * The probe is the [Box] this composable installs at the root of the subtree; the visible UI is
 * identical with or without the wrapper on Android 8.0+ and for everything except platform views on
 * API 24-25; see "Where the pixels come from". It auto-picks [SystemBarIconStyle.DarkIcons] /
 * [SystemBarIconStyle.LightIcons] for each bar from the pixels under it. Two coroutines drive the
 * sampling:
 *
 * 1. **Periodic** — every [intervalMs] (300 ms by default), the strip under the status bar and the
 *    strip under the navigation bar are each sampled on a sparse grid (8 columns, 2 rows per bar)
 *    and averaged to a Rec. 709 luma, thresholded at the midpoint. Each bar's style is decided
 *    independently from its own strip.
 * 2. **On demand** — [StatusBarLuminanceProbe.triggerRecalculation] flushes a re-sample on the next
 *    composed frame. A screen that changes its background under either bar should call this instead
 *    of pushing its own icon-style override.
 *
 * The derived styles are published as a single [SystemBarsOverride] this composable pushes once and
 * updates in place (the same pattern [SystemBarsEffect] uses) — never as a write to the base. That
 * means a screen with a genuine reason to override one axis by hand can still do so with its own
 * [SystemBarsEffect]: composed after this one, it sits on a later layer and wins that axis, exactly
 * as two screen-level effects would.
 *
 * Both bars are sampled symmetrically: each transparent strip overlays the page content drawn under
 * it, so pixel sampling keeps the icons legible regardless of which screen is showing or what
 * background it paints.
 *
 * > **Caveat — the navigation-bar strip can be fooled.** Unlike the status bar, the bottom edge of a
 * > screen frequently carries floating UI (a FAB, a bottom bar, media controls) or image
 * > letterboxing rather than the page background. When such an element sits under the nav bar, the
 * > strip reads *its* brightness, not the page's, and the nav-bar icons can pick the wrong colour.
 * > Keep that area as page background where it matters, or accept the occasional mismatch.
 *
 * **Where the pixels come from.**
 * - On Android 8.0 (API 26) and later the probe copies the window's last composited frame with
 *   `PixelCopy`; it never draws your content a second time. It therefore sees everything this window
 *   draws under each bar, including views outside [content], the window background and the bar
 *   background colours the window's own `DecorView` draws, but not a `SurfaceView` (composited
 *   outside the window), not other windows (dialogs, popups) and nothing the system composites
 *   above the window.
 * - On API 24 and 25, which have no window copy, it replays its own recorded content offscreen, and
 *   skips the sample while a `WebView` (this includes most ad banners) is attached anywhere in the
 *   same Compose view, also outside [content]: replaying a `WebView` offscreen blanks it.
 * - On iOS it replays its own recorded content; a non-overlay `UIKitView` (a `WKWebView`, a map) is
 *   not part of it and reads as dark.
 * - On desktop it never samples.
 * - A sample that cannot read anything is skipped; after three consecutive skipped samples the
 *   probe withdraws its claim and the bars follow the base configuration until a read succeeds.
 *
 * Sampling runs only while the host's lifecycle is at least `STARTED` — a backgrounded screen is
 * neither read nor written to — and every return to the foreground starts a fresh cycle whose first
 * tick follows the first frame.
 *
 * Place this once near the root of the composition, and not under a layer (`graphicsLayer`, `clip`,
 * `shadow`, an elevated surface, `AnimatedContent`): the idle skip counts this root's draws, and a
 * non-dirty ancestor layer replays its display list without running them. Do not nest it.
 *
 * @param controller receives the derived styles, as a [SystemBarsOverride] this composable pushes
 *   and owns for as long as it is composed.
 * @param probe the on-demand trigger source — see [createStatusBarLuminanceProbe].
 * @param enabled when `false`, nothing is sampled and no override is ever pushed onto [controller];
 *   [content] still composes.
 * @param intervalMs periodic sample cadence in milliseconds; must be positive, otherwise the call throws
 *   [IllegalArgumentException].
 * @param onSampled diagnostics hook, called after every sample attempt, skipped ones included, with
 *   its wall time in nanoseconds (from the start of the readback to the end of any controller
 *   write). It includes the window copy on Android 8.0+ (a blocking copy on API 26-33, an
 *   asynchronous one on 34+), so it can be longer than before. Called on the composition's thread.
 *   `null` (the default) costs nothing.
 */
@Composable
public fun AutoSystemBarsIconStyle(
    controller: SystemBarsController,
    probe: StatusBarLuminanceProbe,
    enabled: Boolean = true,
    intervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS,
    onSampled: ((durationNanos: Long) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    AutoSystemBarsIconStyle(
        controller = controller,
        probe = probe,
        enabled = enabled,
        intervalMs = intervalMs,
        onSampled = onSampled,
        reader = rememberBarStripReader(),
        content = content,
    )
}

/**
 * Same contract as the public overload, with the pixel source injected; tests pass a fake [reader].
 * A `null` [reader] is a platform with no pixel source: [content] composes and nothing is sampled.
 */
@Composable
internal fun AutoSystemBarsIconStyle(
    controller: SystemBarsController,
    probe: StatusBarLuminanceProbe,
    enabled: Boolean,
    intervalMs: Long,
    onSampled: ((durationNanos: Long) -> Unit)?,
    reader: BarStripReader?,
    content: @Composable () -> Unit,
) {
    // First, before any early return: the validity of a parameter must not depend on the device's
    // insets. Not coerced: a clamp would turn a hang into a 1 ms CPU hog and hide the bug.
    require(intervalMs > 0L) { "intervalMs must be positive, was $intervalMs" }
    val density: Density = LocalDensity.current
    val statusBarHeightPx: Int = WindowInsets.statusBars.getTop(density)
    val navBarHeightPx: Int = WindowInsets.navigationBars.getBottom(density)
    val frameActivity: FrameActivity = remember { FrameActivity() }
    val isAnimating: (() -> Boolean)? = rememberIsAnimating()
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                // The reader decides what a frame leaves behind: the layer reader records the
                // content into a sampleable layer, the window reader draws it as it is.
                if (reader != null) with(reader) { drawContentAndCapture() } else drawContent()
                frameActivity.onDraw()
            },
    ) {
        content()
    }

    // Sample as long as a reader exists and at least one bar has a strip to read. A bar with zero
    // inset (hidden or immersive) is simply skipped inside the reader.
    if (!enabled || reader == null || (statusBarHeightPx <= 0 && navBarHeightPx <= 0)) return

    // Not an effect key: a new lambda instance (one capturing a changing value) would otherwise
    // restart the sampler and release the override on every recomposition.
    val currentOnSampled: State<((durationNanos: Long) -> Unit)?> = rememberUpdatedState(onSampled)

    LaunchedEffect(
        reader, statusBarHeightPx, navBarHeightPx,
        intervalMs, controller, probe, isAnimating, lifecycleOwner,
    ) {
        val statusPx: Int = statusBarHeightPx.coerceAtLeast(0)
        val navPx: Int = navBarHeightPx.coerceAtLeast(0)

        // A single override this composable owns for as long as it is composed — the same pattern
        // SystemBarsEffect uses for a screen's claim, just held by this coroutine instead of a
        // DisposableEffect. Released in the `finally` below on cancellation (composable leaving
        // composition, or a key in the LaunchedEffect's key list changing).
        val handle: SystemBarsOverrideHandle = controller.applyOverride(SystemBarsOverride.None)
        var lastPublished: SystemBarsOverride = SystemBarsOverride.None

        // What the last sample read: the root draw count at that moment and when it was. A periodic
        // tick with nothing drawn since is skipped — the frame still holds the very pixels already
        // read — see [idleTickIsRedundant]. Reset at every cycle start so a return from the
        // background always re-reads (a trigger fired while stopped was dropped, and the window may
        // have been re-themed meanwhile).
        var drawsAtLastSample: Int = NEVER_SAMPLED
        var ticksSinceLastSample = 0

        // Consecutive attempts that read nothing (null, thrown, guard-skipped), reset by any success
        // and at every cycle start — see [FAILED_READS_BEFORE_RELEASE].
        var failedAttempts = 0

        // One read at a time: a read may suspend (the Android window copy always does, the API
        // 24-27 layer readback does), so the trigger collector and the periodic tick could
        // otherwise both be inside a sample, and a reader that records into scratch state would be
        // re-recorded under the first one's pending read.
        val sampling = SerialSampler()

        suspend fun sampleOnce(): Unit = sampling.run {
            val started: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
            // Captured before the read (it may suspend, and a root draw landing meanwhile must
            // count as unread) and booked only once the read succeeded — a failed one is retried
            // on the next tick, not after several redundant ones.
            val drawsBeforeReadback: Int = frameActivity.draws
            // A read that fails (mid-resize, trimmed display list) is one missed sample, not a
            // dead probe — but a cancellation must still stop the loop.
            val styles: BarStripStyles? = runCatching { reader.read(statusPx, navPx) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (styles != null) {
                lastPublished = publishStyles(handle, lastPublished, status = styles.status, nav = styles.nav)
                failedAttempts = 0
                drawsAtLastSample = drawsBeforeReadback
                ticksSinceLastSample = 0
            } else {
                failedAttempts++
                if (failedAttempts >= FAILED_READS_BEFORE_RELEASE) {
                    // The held override was derived from content that is no longer being read: hand
                    // the bars back to the base configuration (a no-op if already published) and
                    // keep attempting at the normal cadence.
                    lastPublished = publishStyles(handle, lastPublished, status = null, nav = null)
                }
            }
            currentOnSampled.value?.invoke(started.elapsedNow().inWholeNanoseconds)
        }

        try {
            // Only while the screen is showing. In the background there is no frame to read and
            // nobody to see the icons, yet `delay` keeps ticking — without this gate that would
            // rasterise a scratch bitmap every [intervalMs] of a stopped host. Returning to the
            // foreground starts a fresh cycle, first tick included.
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                drawsAtLastSample = NEVER_SAMPLED
                failedAttempts = 0
                coroutineScope {
                    // Trigger-driven samples — fired immediately by screens that change their
                    // backdrop. Two withFrameMillis passes wait until the *next* composed frame's
                    // draw has completed and the frame has been re-recorded with the new state, so
                    // this samples post-state-change content rather than the stale frame.
                    // Subscribed before anything else in the cycle so no trigger fired from here on
                    // is lost; one fired earlier is dropped by the replay-0 flow and covered by the
                    // first tick below.
                    launch {
                        probe.triggers.collect {
                            withFrameMillis { /* wait — start of next frame */ }
                            withFrameMillis { /* wait — start of frame after */ }
                            sampleOnce()
                        }
                    }

                    // Two frames before the first tick: the frame clock fires ahead of that vsync's
                    // draw, so after one frame the captured frame is still empty (a new composition)
                    // or stale (a return from the background); after two it holds this cycle's own
                    // first frame.
                    withFrameMillis { /* wait — first frame of this cycle */ }
                    withFrameMillis { /* wait — its draw has landed in the captured frame */ }

                    // Periodic samples — steady-state safety net for content that changes without
                    // an explicit trigger. Deferred while the UI is animating, up to a jittered
                    // cap, so an endless animation still gets a sample now and then at no fixed
                    // phase — see [PeriodicDeferral] and [randomDeferralCapMs]. The first tick runs
                    // right after those frames, so a settled screen derives its icons at once. A
                    // due tick on an already-settled screen is still skipped while nothing new has
                    // drawn since the last sample, up to [IDLE_RESAMPLE_MS] — see
                    // [idleTickIsRedundant].
                    val deferral = PeriodicDeferral(intervalMs)
                    while (isActive) {
                        val animating: Boolean = isAnimating?.invoke() ?: false
                        val due: Boolean = deferral.onTick(
                            sinceLastDrawMs = frameActivity.sinceLastDrawMs(),
                            animating = animating,
                        )
                        ticksSinceLastSample++
                        val redundant: Boolean = drawsAtLastSample != NEVER_SAMPLED && idleTickIsRedundant(
                            drawsSinceLastSample = frameActivity.draws - drawsAtLastSample,
                            ticksSinceLastSample = ticksSinceLastSample,
                            intervalMs = intervalMs,
                            animating = animating,
                        )
                        if (due && !redundant) sampleOnce()
                        delay(intervalMs)
                    }
                }
            }
        } finally {
            handle.release()
        }
    }
}

/**
 * Runs samples one at a time, in the order they ask. A read may suspend (the Android window copy
 * always does, the API 24-27 layer readback does), so the trigger collector and the periodic tick
 * can both be inside a sample; the second would re-record a layer reader's scratch layer under the
 * first one's pending read. A [Mutex], not a flag: a waiting sample must run afterwards (it is the
 * one that reads the post-trigger frame) and a cancelled waiter must just leave. Kept as a class of
 * its own so a test can drive it with a readback that never finishes.
 */
internal class SerialSampler {

    private val mutex: Mutex = Mutex()

    suspend fun <T> run(sample: suspend () -> T): T = mutex.withLock { sample() }
}

/**
 * Average the luminance of [rows] rows of [pixelMap] starting at [startY] and map it to an icon
 * style — or `null` when the strip is empty. Bounds are clamped so a momentary size mismatch cannot
 * read out of bounds. Reads 8 columns × [rows] rows (the "8 × 2 grid" for a bar with two scratch
 * rows).
 */
internal fun sampleRows(pixelMap: PixelMap, startY: Int, rows: Int): SystemBarIconStyle? {
    val w: Int = pixelMap.width
    val y0: Int = startY.coerceIn(0, pixelMap.height)
    val h: Int = rows.coerceAtMost(pixelMap.height - y0)
    if (w <= 0 || h <= 0) return null

    var sum = 0.0
    val cols: Int = SAMPLE_COLS.coerceAtMost(w)
    val sampleRows: Int = SAMPLE_ROWS.coerceAtMost(h)
    for (yi in 0 until sampleRows) {
        val py: Int = y0 + yi * h / sampleRows
        for (xi in 0 until cols) {
            val px: Int = xi * w / cols
            val c: Color = pixelMap[px, py]
            sum += LUMA_RED * c.red + LUMA_GREEN * c.green + LUMA_BLUE * c.blue
        }
    }
    return luminanceToStyle(sum / (cols * sampleRows))
}

/**
 * Folds [status] and [nav] into a single [SystemBarsOverride] and, if it differs from
 * [lastPublished], pushes it onto [handle] in one call. `null` on either axis leaves it unclaimed
 * (no decision for that bar) rather than claiming a specific style — a bar that had no rows to
 * decide from is left to whatever is underneath.
 *
 * Combining both axes into one [SystemBarsOverrideHandle.update] call, rather than two separate
 * controller writes, is what keeps a sample's publication atomic: an observer of [SystemBarsController.config]
 * never sees a frame where only one bar's style has caught up.
 *
 * @return the override that is now in effect on [handle] — pass it back in as [lastPublished] on
 *   the next call.
 */
internal fun publishStyles(
    handle: SystemBarsOverrideHandle,
    lastPublished: SystemBarsOverride,
    status: SystemBarIconStyle?,
    nav: SystemBarIconStyle?,
): SystemBarsOverride {
    val next = SystemBarsOverride(statusBarIcons = status, navigationBarIcons = nav)
    if (next != lastPublished) handle.update(next)
    return next
}

/**
 * The periodic tick's deferral counter: one [onTick] per `delay(intervalMs)`. Returns `true` when
 * the tick is due (and starts counting afresh under a new cap from [nextCapMs]) — the loop may still
 * skip a due tick as redundant, see [idleTickIsRedundant]; otherwise the tick is added to the
 * deferred time that [periodicSampleIsDue] caps. At the default cadence an animation that never
 * quiets is sampled on the fifth to eighth tick (1.5–2.4 s) — see [randomDeferralCapMs] for why it
 * varies. The first tick of a cycle is gated on the platform's animating signal alone (see [onTick]),
 * so a settled screen is sampled at once.
 */
internal class PeriodicDeferral(
    private val intervalMs: Long,
    private val nextCapMs: () -> Long = { randomDeferralCapMs() },
) {
    private var deferredMs: Long = 0L
    private var capMs: Long = nextCapMs()
    private var firstTick = true

    fun onTick(sinceLastDrawMs: Long, animating: Boolean): Boolean {
        // The first tick of a cycle follows the frame it just awaited, so the draw clock reads
        // "drawn a moment ago" by construction and says nothing about whether the scene has
        // settled; only the platform's animating signal can gate it. Where there is none (iOS) the
        // first tick therefore always samples.
        val quietMs: Long = if (firstTick) Long.MAX_VALUE else sinceLastDrawMs
        firstTick = false
        val due: Boolean = periodicSampleIsDue(quietMs, deferredMs, animating, capMs)
        if (due) {
            deferredMs = 0L
            capMs = nextCapMs()
        } else {
            deferredMs += intervalMs
        }
        return due
    }
}

/** How many rows of a [stripPx]-tall bar the 8 × 2 grid reads — [SAMPLE_ROWS], or fewer for a thinner bar. */
internal fun scratchRowCount(stripPx: Int): Int = SAMPLE_ROWS.coerceAtMost(stripPx.coerceAtLeast(0))

/**
 * The source rows the sampler reads, in scratch-layer order: the status bar's rows (spread over its
 * top `statusPx` rows of the source) followed by the navigation bar's (spread over the source's
 * bottom `navPx` rows). Each entry is the source `y` that scratch row `index` must show, clamped
 * into the source; a bar with no inset contributes nothing, and so does an empty source.
 */
internal fun scratchRowSources(statusPx: Int, navPx: Int, sourceHeight: Int): IntArray {
    if (sourceHeight <= 0) return IntArray(0)
    val statusRows: Int = scratchRowCount(statusPx)
    val navRows: Int = scratchRowCount(navPx)
    val navTop: Int = sourceHeight - navPx
    return IntArray(statusRows + navRows) { row ->
        val sourceY: Int = if (row < statusRows) {
            row * statusPx / statusRows
        } else {
            navTop + (row - statusRows) * navPx / navRows
        }
        // A source momentarily shorter than the bars (mid-resize) reads its nearest real row rather
        // than nothing.
        sourceY.coerceIn(0, sourceHeight - 1)
    }
}

/**
 * The periodic tick's gate. A sample is due when the scene has settled — the platform reports
 * nothing [animating] and the last draw is at least [QUIET_AFTER_MS] old — or the tick has already
 * been deferred for [capMs] (an endless animation must not starve the safety net). Triggered samples
 * never pass through here.
 */
internal fun periodicSampleIsDue(
    sinceLastDrawMs: Long,
    deferredMs: Long,
    animating: Boolean,
    capMs: Long = MAX_PERIODIC_DEFERRAL_MS,
): Boolean = deferredMs >= capMs || (!animating && sinceLastDrawMs >= QUIET_AFTER_MS)

/**
 * Whether a due periodic tick can be skipped: nothing has drawn into the root since the last sample
 * and nothing is [animating], so the captured frame holds exactly the pixels already read — unless the ticks
 * since that sample already span [IDLE_RESAMPLE_MS], the bound that still catches a one-shot
 * layer-property change (it alters pixels without a root draw and without frame awaiters). Counted
 * in ticks rather than wall time so the loop behaves the same under a test clock. Triggered samples
 * never pass through here.
 */
internal fun idleTickIsRedundant(
    drawsSinceLastSample: Int,
    ticksSinceLastSample: Int,
    intervalMs: Long,
    animating: Boolean,
): Boolean = !animating && drawsSinceLastSample == 0 && ticksSinceLastSample * intervalMs < IDLE_RESAMPLE_MS

/**
 * A fresh deferral cap for the next cycle, uniform in
 * `[MAX_PERIODIC_DEFERRAL_MS - PERIODIC_DEFERRAL_JITTER_MS, MAX_PERIODIC_DEFERRAL_MS]`. A fixed cap
 * would make the sampler strictly periodic inside a long animation, and it could sit in antiphase
 * with periodic content for a long time (a backdrop cycling at a multiple of the cadence never gets
 * seen in its bright phase); the jitter breaks that lock without ever exceeding the maximum
 * staleness.
 */
internal fun randomDeferralCapMs(random: Random = Random.Default): Long =
    MAX_PERIODIC_DEFERRAL_MS - random.nextLong(0L, PERIODIC_DEFERRAL_JITTER_MS + 1)

/**
 * Last-draw clock for the periodic gate. Written from the draw pass and read from the sampling
 * coroutine — both on the composition's thread.
 */
private class FrameActivity {
    // Non-null on purpose: ValueTimeMark is a value class, and a nullable field would box a fresh
    // mark on every frame of the draw pass.
    private var lastDraw: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    private var hasDrawn = false

    /** Root draws so far — the sampler compares it with the count at its last sample. */
    var draws: Int = 0
        private set

    fun onDraw() {
        lastDraw = TimeSource.Monotonic.markNow()
        hasDrawn = true
        draws++
    }

    /** Milliseconds since the last draw, or [Long.MAX_VALUE] before the first one. */
    fun sinceLastDrawMs(): Long = if (hasDrawn) lastDraw.elapsedNow().inWholeMilliseconds else Long.MAX_VALUE
}

/**
 * Maps an average rec.709 luminance (`0.0`..`1.0`) to an icon style: a bright strip needs
 * [SystemBarIconStyle.DarkIcons], a dim strip needs [SystemBarIconStyle.LightIcons]. The boundary is
 * [LUMINANCE_THRESHOLD]; exactly the threshold counts as dim.
 */
internal fun luminanceToStyle(avgLuminance: Double): SystemBarIconStyle =
    if (avgLuminance > LUMINANCE_THRESHOLD) SystemBarIconStyle.DarkIcons else SystemBarIconStyle.LightIcons

/** Default periodic-sample cadence — see [AutoSystemBarsIconStyle]. */
public const val DEFAULT_SAMPLE_INTERVAL_MS: Long = 300L

/** Columns of the sparse sampling grid; also the width of the Android window-copy destination. */
internal const val SAMPLE_COLS: Int = 8
private const val SAMPLE_ROWS = 2
private const val LUMINANCE_THRESHOLD = 0.5

/**
 * Rec. 709 luma coefficients, applied to the gamma-encoded sRGB values the readback returns (luma
 * Y′); the threshold was tuned on these values.
 */
private const val LUMA_RED = 0.2126
private const val LUMA_GREEN = 0.7152
private const val LUMA_BLUE = 0.0722

/** A draw older than this means the scene has settled — see [periodicSampleIsDue]. */
internal const val QUIET_AFTER_MS: Long = 100L

/** Longest the periodic tick may be deferred by continuous drawing — see [periodicSampleIsDue]. */
internal const val MAX_PERIODIC_DEFERRAL_MS: Long = 2_000L

/** How much shorter than the maximum a cycle's cap may randomly be — see [randomDeferralCapMs]. */
internal const val PERIODIC_DEFERRAL_JITTER_MS: Long = 900L

/** On a settled screen with no root draw since the last sample, how long before one is taken anyway. */
internal const val IDLE_RESAMPLE_MS: Long = 2_000L

/**
 * How many consecutive sample attempts may read nothing (a `null` read, a thrown one, a guard skip)
 * before the probe withdraws its claim by publishing [SystemBarsOverride.None]. Idle-skipped ticks
 * are not attempts; any successful read resets the count.
 */
internal const val FAILED_READS_BEFORE_RELEASE: Int = 3

/** [FrameActivity.draws] value that marks "no sample yet in this cycle". */
private const val NEVER_SAMPLED: Int = -1
