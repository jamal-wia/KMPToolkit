package io.github.jamal_wia.kmptoolkit.language.compose

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.view.KeyEvent
import android.view.WindowInsetsController
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import io.github.jamal_wia.kmptoolkit.hardwarekeys.DialogWindowHardwareKeyEffect
import io.github.jamal_wia.kmptoolkit.hardwarekeys.DialogWindowHardwareKeyPolicy
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import io.github.jamal_wia.kmptoolkit.language.AppLanguageCatalog
import io.github.jamal_wia.kmptoolkit.language.AppLanguageHolder
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageCatalog
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageHolder
import io.github.jamal_wia.kmptoolkit.location.GeoCoordinates
import io.github.jamal_wia.kmptoolkit.location.LocationProvider
import io.github.jamal_wia.kmptoolkit.location.LocationProviderConfig
import io.github.jamal_wia.kmptoolkit.location.createLocationProvider
import io.github.jamal_wia.kmptoolkit.location.createLocationProviderWithLauncher
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenLauncher
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.LogSink
import io.github.jamal_wia.kmptoolkit.logging.LoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.d
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.overlay.LogOverlayHost
import io.github.jamal_wia.kmptoolkit.logging.overlay.LogOverlayState
import io.github.jamal_wia.kmptoolkit.logging.platformLogSink
import io.github.jamal_wia.kmptoolkit.systembars.DialogWindowSystemBarsEffect
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsEffect
import io.github.jamal_wia.kmptoolkit.systembars.createScreenWakeLockController
import io.github.jamal_wia.kmptoolkit.systembars.createSystemBarsController
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * One consumer that uses the Android side of every module of unit F together, the way an app would wire them from
 * the 04-api-reference signatures: a logger factory whose sinks are the platform sink, the overlay and a counting
 * sink; that factory's loggers handed to the hardware-keys policy and to the location provider; a language holder
 * whose callback logs; the system-bars controller; and a Compose tree with AppLocale, SystemBarsEffect, the overlay
 * host and a Dialog carrying both dialog-window effects.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class FullSuiteConsumerTest {

    private val application: Application = RuntimeEnvironment.getApplication()
    private val originalDefault: Locale = Locale.getDefault()

    private val en: AppLanguage = AppLanguage("en")
    private val ar: AppLanguage = AppLanguage("ar")

    /** What one app keeps: built once, in the order an app would build it, torn down in reverse. */
    private inner class App {
        val overlay: LogOverlayState = LogOverlayState(maxRecords = 200)
        val tail: MutableList<String> = CopyOnWriteArrayList()
        val factory: LoggerFactory = createLoggerFactory(
            minLevel = LogLevel.VERBOSE,
            sinks = listOf(platformLogSink(), overlay.asLogSink(), LogSink { level, tag, message, _ -> tail += "$level/$tag/$message" }),
        )
        val log: Logger = factory.logger("App")
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(listOf(en, ar), systemLanguageCode = { "en" })
        val holder: AppLanguageHolder = createAppLanguageHolder(en, onLanguageChanged = { changed -> log.i { "language is now ${changed.code}" } })
        val keysConsumed: MutableList<Int> = CopyOnWriteArrayList()
        val controller: SystemBarsController = createSystemBarsController(application, SystemBarsConfig.ForLightBackground)
        val location: LocationProvider = createLocationProvider(
            application,
            LocationProviderConfig(singleFixTimeoutMillis = 100),
            factory.logger("Location"),
        )

        init {
            DialogWindowHardwareKeyPolicy.install(factory.logger("Keys")) { event ->
                log.d { "key ${event.keyCode}" }
                keysConsumed += event.keyCode
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            }
        }
    }

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    @After
    fun resetGlobals() {
        DialogWindowHardwareKeyPolicy.uninstall()
        Locale.setDefault(originalDefault)
    }

    /** Window-level appearance as the platform reports it (API 30+; the module's Robolectric level is 35). */
    private fun lightStatusBar(window: Window): Boolean =
        window.insetsController!!.systemBarsAppearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0

    @androidx.compose.runtime.Composable
    private fun dialogWindow(): Window = (LocalView.current.parent as DialogWindowProvider).window

    @Test
    fun `theme flip then language switch then overlay open then a key in the dialog all land together`() = runComposeUiTest {
        val app = App()
        var darkTheme by mutableStateOf(false)
        var showDialog by mutableStateOf(true)
        var directionSeen: LayoutDirection? = null
        var dialogWindowSeen: Window? = null
        var localeInContent: Locale? = null

        setContent {
            val language: AppLanguage = app.holder.languageFlow.collectAsState().value
            AppLocale(language, app.catalog.resolve(language)) {
                directionSeen = LocalLayoutDirection.current
                localeInContent = Locale.getDefault()
                SystemBarsEffect(
                    app.controller,
                    statusBarIcons = if (darkTheme) SystemBarIconStyle.LightIcons else SystemBarIconStyle.DarkIcons,
                )
                LogOverlayHost(app.overlay) {
                    if (showDialog) {
                        Dialog(onDismissRequest = {}) {
                            dialogWindowSeen = dialogWindow()
                            DialogWindowSystemBarsEffect(app.controller)
                            DialogWindowHardwareKeyEffect()
                        }
                    }
                }
            }
        }
        waitForIdle()

        // The first frame: light theme, English, overlay closed, the dialog window styled by the controller.
        val window: Window = assertNotNull(dialogWindowSeen)
        assertEquals(LayoutDirection.Ltr, directionSeen)
        assertEquals(SystemBarIconStyle.DarkIcons, app.controller.currentConfig.statusBarIcons)
        assertTrue(lightStatusBar(window), "dark icons: light status bar appearance")
        assertFalse(app.overlay.isVisible.value)

        // All three things at once, between two frames.
        darkTheme = true
        app.holder.setLanguage(ar)
        app.overlay.show()
        app.log.i { "pressed" }
        waitForIdle()

        assertEquals(LayoutDirection.Rtl, directionSeen, "the language switch reached the direction")
        assertEquals("ar", localeInContent?.language, "the language switch reached the process locale")
        assertEquals(SystemBarIconStyle.LightIcons, app.controller.currentConfig.statusBarIcons)
        assertFalse(
            lightStatusBar(window),
            "the dialog window follows the theme flip",
        )
        assertTrue(app.overlay.isVisible.value)
        assertNotNull(dialogWindowSeen)

        // A key in the dialog: the policy consumes volume up, passes everything else on, and logs through the shared factory.
        assertTrue(window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_UP), app.keysConsumed)

        // Everything above went through one logger: the callback of the holder, the key policy and the app itself all
        // reached the platform sink, the overlay and the counting sink, in order.
        val expected: List<String> = listOf(
            "INFO/App/language is now ar",
            "INFO/App/pressed",
            "DEBUG/App/key 24",
        )
        assertEquals(expected, app.tail.toList())
        assertEquals(listOf("language is now ar", "pressed", "key 24"), app.overlay.records.value.map { it.message })
        assertEquals(expected.size, ShadowLog.getLogs().count { it.tag == "App" }, "the platform sink saw each event once")
    }

    @Test
    fun `a dialog that leaves composition gives its window back and the policy installed once logs nothing`() = runComposeUiTest {
        val app = App()
        var showDialog by mutableStateOf(true)
        var window: Window? = null
        var callbackBefore: Window.Callback? = null

        setContent {
            AppLocale(en, en) {
                LogOverlayHost(app.overlay) {
                    if (showDialog) {
                        Dialog(onDismissRequest = {}) {
                            val w: Window = dialogWindow()
                            if (window == null) {
                                window = w
                                callbackBefore = w.callback
                            }
                            DialogWindowSystemBarsEffect(app.controller)
                            DialogWindowHardwareKeyEffect()
                        }
                    }
                }
            }
        }
        waitForIdle()
        val shown: Window = assertNotNull(window)
        assertTrue(shown.callback !== callbackBefore, "the effect decorated the window callback while composed")

        showDialog = false
        waitForIdle()

        assertSame(callbackBefore, shown.callback, "leaving composition restored exactly the callback that was there")
        assertTrue(app.keysConsumed.isEmpty())
        assertTrue(app.tail.none { it.startsWith("ERROR") }, "a single install is not a replacement: no error event")
    }

    @Test
    fun `installing the key policy a second time reaches the overlay as one error event and the second policy wins`() = runComposeUiTest {
        val app = App()
        val second: MutableList<Int> = CopyOnWriteArrayList()

        DialogWindowHardwareKeyPolicy.install(app.factory.logger("Keys")) { event ->
            second += event.keyCode
            true
        }
        var window: Window? = null
        setContent {
            Dialog(onDismissRequest = {}) {
                window = dialogWindow()
                DialogWindowHardwareKeyEffect()
            }
        }
        waitForIdle()

        assertTrue(assertNotNull(window).callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertEquals(listOf(KeyEvent.KEYCODE_VOLUME_DOWN), second)
        assertTrue(app.keysConsumed.isEmpty(), "the replaced policy is never consulted")
        val errors = app.overlay.records.value.filter { it.level == LogLevel.ERROR }
        assertEquals(1, errors.size)
        assertEquals("Keys", errors.single().tag)
    }

    @Test
    fun `a launcher that throws while the location settings screen opens is a warning in every sink and never an exception`() {
        val app = App()
        val failure = IllegalStateException("launcher failed", UnknownHostException("settings.example"))
        val provider: LocationProvider = createLocationProviderWithLauncher(
            application,
            SystemScreenLauncher { throw failure },
            LocationProviderConfig(),
            app.factory.logger("Location"),
        )

        provider.openLocationSettings()

        val warnings = app.overlay.records.value.filter { it.level == LogLevel.WARN }
        assertEquals(1, warnings.size, "one warning, in the overlay")
        assertEquals("Location", warnings.single().tag)
        assertEquals("Could not open the location settings screen", warnings.single().message)
        assertTrue(assertNotNull(warnings.single().throwableText).contains("UnknownHostException"), "the cause chain travelled as text")
        assertEquals(1, app.tail.count { it.startsWith("WARN/Location/") })
        val platform = ShadowLog.getLogs().single { it.tag == "Location" }
        assertTrue(platform.msg.contains("UnknownHostException"), "the platform sink rendered the chain itself")
    }

    @Test
    fun `a collection of observeLocation ends with no listener left after the overlay and the logger saw it`() {
        val app = App()
        val manager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        val seed: GeoCoordinates? = runBlocking { withTimeout(20_000) { app.location.observeLocation().first() } }

        assertNull(seed, "no fix yet: the documented null seed")
        assertEquals(0, shadowOf(manager).getRequestLocationUpdateListeners().size, "first() cancelled the collection and the listeners went")
        assertTrue(app.overlay.records.value.none { it.level >= LogLevel.WARN }, "a granted permission logs no warning")
    }

    @Test
    fun `an UnknownHostException logged through the shared factory is rendered by the platform sink and kept by the overlay`() {
        val app = App()
        val failure = IllegalStateException("sync failed", UnknownHostException("api.example"))

        app.factory.logger("Net").let { net -> net.log(LogLevel.ERROR, failure) { "request failed" } }

        val record = app.overlay.records.value.single()
        assertEquals("request failed", record.message)
        assertTrue(assertNotNull(record.throwableText).contains("UnknownHostException"))
        val platform = ShadowLog.getLogs().single { it.tag == "Net" }
        assertTrue(platform.msg.contains("request failed"))
        assertTrue(platform.msg.contains("UnknownHostException"), "the platform would have rendered an empty string: the sink renders it itself")
    }
}
