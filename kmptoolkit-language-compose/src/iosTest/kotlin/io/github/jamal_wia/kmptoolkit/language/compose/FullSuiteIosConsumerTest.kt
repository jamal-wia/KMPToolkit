package io.github.jamal_wia.kmptoolkit.language.compose

import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import io.github.jamal_wia.kmptoolkit.language.AppLanguageHolder
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageCatalog
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageHolder
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.LoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.createLoggerFactory
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.overlay.LogOverlayState
import io.github.jamal_wia.kmptoolkit.logging.platformLogSink
import io.github.jamal_wia.kmptoolkit.systembars.IosSystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsOverride
import io.github.jamal_wia.kmptoolkit.systembars.createSystemBarsController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.UIKit.UIStatusBarStyleDarkContent
import platform.UIKit.UIStatusBarStyleLightContent

/**
 * The iOS half of the F-unit consumer (no Compose host exists on the simulator test runner, so the UI modules are
 * represented by their state): the logger factory with the platform sink and the overlay state as sinks, a language
 * holder whose callback logs, the iOS system-bars controller, driven together as an app would drive them on a theme
 * flip plus a language switch.
 */
class FullSuiteIosConsumerTest {

    private val en: AppLanguage = AppLanguage("en")
    private val ar: AppLanguage = AppLanguage("ar")

    @Test
    fun `a language switch and a theme flip together land in the holder the overlay and the controller`() {
        val overlay = LogOverlayState(maxRecords = 100)
        val factory: LoggerFactory = createLoggerFactory(LogLevel.VERBOSE, listOf(platformLogSink(), overlay.asLogSink()))
        val log: Logger = factory.logger("App")
        val catalog = createAppLanguageCatalog(listOf(en, ar), systemLanguageCode = { "en" })
        val holder: AppLanguageHolder = createAppLanguageHolder(en, onLanguageChanged = { changed -> log.i { "language is now ${changed.code}" } })
        val controller: IosSystemBarsController = createSystemBarsController(SystemBarsConfig.ForLightBackground)

        assertEquals(UIStatusBarStyleDarkContent, controller.preferredStatusBarStyle, "light theme: dark icons")

        holder.setLanguage(ar)
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        overlay.show()

        assertEquals(ar, holder.language)
        assertEquals(false, catalog.resolve(holder.language).isLtr)
        assertEquals(UIStatusBarStyleLightContent, controller.preferredStatusBarStyle, "dark theme: light icons")
        assertTrue(overlay.isVisible.value)
        assertEquals(listOf("language is now ar"), overlay.records.value.map { it.message })

        // A screen's claim over the base, released: the base the theme set is what is left.
        val handle = controller.applyOverride(SystemBarsOverride(statusBarIcons = SystemBarIconStyle.DarkIcons))
        assertEquals(UIStatusBarStyleDarkContent, controller.preferredStatusBarStyle)
        handle.release()
        assertEquals(UIStatusBarStyleLightContent, controller.preferredStatusBarStyle)

        controller.release()
        holder.setLanguage(AppLanguage.System)
        assertEquals(listOf("language is now ar", "language is now null"), overlay.records.value.map { it.message })
    }
}
