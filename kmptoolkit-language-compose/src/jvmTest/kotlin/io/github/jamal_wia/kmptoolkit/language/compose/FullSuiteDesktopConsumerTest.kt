package io.github.jamal_wia.kmptoolkit.language.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import io.github.jamal_wia.kmptoolkit.hardwarekeys.DialogWindowHardwareKeyEffect
import io.github.jamal_wia.kmptoolkit.language.AppLanguage
import io.github.jamal_wia.kmptoolkit.language.AppLanguageCatalog
import io.github.jamal_wia.kmptoolkit.language.AppLanguageHolder
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageCatalog
import io.github.jamal_wia.kmptoolkit.language.createAppLanguageHolder
import io.github.jamal_wia.kmptoolkit.systembars.DialogWindowSystemBarsEffect
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarIconStyle
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsConfig
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsController
import io.github.jamal_wia.kmptoolkit.systembars.SystemBarsEffect
import io.github.jamal_wia.kmptoolkit.systembars.createScreenWakeLockController
import io.github.jamal_wia.kmptoolkit.systembars.createSystemBarsController
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The shared half of the F-unit consumer on the desktop JVM: the modules that publish a `jvm` target (language,
 * language-compose, systembars, hardware-keys; logging, overlay and location do not) used together in one Compose
 * tree, every API as 04-api-reference shows it. Desktop has no system bars and no per-window key routing, so the
 * contract is that nothing throws, the layer stack still behaves, and the language still reaches the process locale.
 */
@OptIn(ExperimentalTestApi::class)
class FullSuiteDesktopConsumerTest {

    private val originalDefault: Locale = Locale.getDefault()
    private val en: AppLanguage = AppLanguage("en")
    private val ar: AppLanguage = AppLanguage("ar")

    // A desktop Compose app declares kotlinx-coroutines-swing, which supplies Dispatchers.Main; the effects collect
    // the controller's flow through the lifecycle, which needs it. The test stands in for that dependency.
    @OptIn(ExperimentalCoroutinesApi::class)
    @BeforeTest
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @AfterTest
    fun restoreDefault() {
        Dispatchers.resetMain()
        Locale.setDefault(originalDefault)
    }

    @Test
    fun `no android class is on the desktop consumer classpath`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("android.view.View") }
        assertFailsWith<ClassNotFoundException> { Class.forName("android.content.Context") }
    }

    @Test
    fun `theme flip and language switch together on desktop with every effect composed and a clean teardown`() = runComposeUiTest {
        val catalog: AppLanguageCatalog = createAppLanguageCatalog(listOf(en, ar), systemLanguageCode = { "en" })
        val holder: AppLanguageHolder = createAppLanguageHolder(en)
        val controller: SystemBarsController = createSystemBarsController(SystemBarsConfig.ForLightBackground)
        var darkTheme by mutableStateOf(false)
        var onScreen by mutableStateOf(true)
        var direction: LayoutDirection? = null
        var localeInContent: Locale? = null

        setContent {
            val language: AppLanguage = holder.languageFlow.collectAsState().value
            AppLocale(language, catalog.resolve(language)) {
                direction = LocalLayoutDirection.current
                localeInContent = Locale.getDefault()
                if (onScreen) {
                    SystemBarsEffect(
                        controller,
                        statusBarIcons = if (darkTheme) SystemBarIconStyle.LightIcons else SystemBarIconStyle.DarkIcons,
                    )
                    DialogWindowSystemBarsEffect(controller)
                    DialogWindowHardwareKeyEffect()
                }
            }
        }
        waitForIdle()
        assertEquals(LayoutDirection.Ltr, direction)
        assertEquals(SystemBarIconStyle.DarkIcons, controller.currentConfig.statusBarIcons)

        darkTheme = true
        holder.setLanguage(ar)
        waitForIdle()

        assertEquals(LayoutDirection.Rtl, direction)
        assertEquals("ar", localeInContent?.language)
        assertEquals(SystemBarIconStyle.LightIcons, controller.currentConfig.statusBarIcons)

        // Leaving: the claim of the effect is released, the base the theme set is what is left.
        onScreen = false
        waitForIdle()
        assertEquals(SystemBarsConfig.ForLightBackground, controller.currentConfig)

        // System puts the process locale back to the operating system's, whatever the selection changed it to.
        holder.setLanguage(AppLanguage.System)
        waitForIdle()
        assertEquals(originalDefault.language, Locale.getDefault().language)
        controller.release()
    }

    @Test
    fun `the desktop wake lock accepts every call and a released controller still answers`() {
        val wakeLock = createScreenWakeLockController()
        repeat(100) { index -> wakeLock.setKeepScreenOn(index % 2 == 0) }
        wakeLock.setKeepScreenOn(false)
        val controller: SystemBarsController = createSystemBarsController()
        controller.release()
        controller.setBaseConfig(SystemBarsConfig.ForDarkBackground)
        assertEquals(SystemBarsConfig.ForDarkBackground, controller.currentConfig)
    }
}
