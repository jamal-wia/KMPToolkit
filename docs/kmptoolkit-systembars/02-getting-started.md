# kmptoolkit-systembars — Getting started

Five minutes to bars that follow your theme and a screen that can override them without breaking
the next one.

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-systembars")
        }
    }
}
```

This module is Compose Multiplatform code and is published for `android`, `iosArm64` and
`iosSimulatorArm64` — the same target set as every other module in the suite. There is no `iosX64`
variant.

## 2. Create the controller

One per process, created where your app is already assembling its long-lived objects, and passed
to whoever needs it. There is no global instance and no DI integration — see
[`docs/01-architecture.md`](../01-architecture.md).

### Android

`createSystemBarsController` takes an `ActivityAccess` from
[`kmptoolkit-platform`](../kmptoolkit-platform/01-overview.md), because the window it styles belongs
to whichever activity is resumed right now — an identity that changes on every rotation.

```kotlin
class MyApplication : Application() {

    lateinit var activityAccess: ActivityAccess
        private set
    lateinit var systemBars: SystemBarsController
        private set

    override fun onCreate() {
        super.onCreate()
        activityAccess = createActivityTracker(this)
        systemBars = createSystemBarsController(activityAccess)
    }
}
```

If you want your app drawing behind the bars — you almost certainly do — call `enableEdgeToEdge()`
in your activity as usual. This module does not do it for you; see
[`05-platform-notes.md`](05-platform-notes.md).

### iOS

iOS does not let anything set the status bar directly: a view controller *declares* what it wants
and UIKit asks. So the controller supplies the two answers and your Compose host returns them.

```kotlin
private val systemBars = createSystemBarsController()

fun MainViewController(): UIViewController {
    val host = ComposeUIViewController { App(systemBars) }
    systemBars.hostViewController = host
    return host
}
```

`preferredStatusBarStyle` and `prefersStatusBarHidden` must be returned from the controller that
UIKit actually asks — with a plain `ComposeUIViewController` that is the host above. The exact
wiring, including the `Info.plist` requirement, is in
[`05-platform-notes.md`](05-platform-notes.md).

## 3. Let your theme own the base

The base configuration is what shows wherever no screen has an opinion. It belongs to exactly one
writer: the place your app already decides between light and dark.

```kotlin
@Composable
fun AppTheme(controller: SystemBarsController, darkTheme: Boolean, content: @Composable () -> Unit) {
    LaunchedEffect(darkTheme) {
        controller.setBaseConfig(
            if (darkTheme) SystemBarsConfig.ForDarkBackground else SystemBarsConfig.ForLightBackground,
        )
    }
    MaterialTheme(colorScheme = if (darkTheme) darkColors else lightColors, content = content)
}
```

## 4. Let a screen claim what it needs

```kotlin
@Composable
fun PhotoViewerScreen(controller: SystemBarsController) {
    // Light icons over the photo, for as long as this screen is composed.
    SystemBarsEffect(controller, statusBarIcons = SystemBarIconStyle.LightIcons)

    Image(/* ... */)
}
```

Navigate away and the claim is gone; the status bar goes back to whatever the theme says **at that
moment**. Nothing was snapshotted, so a theme change while the viewer was open is not undone.

Fullscreen is the same shape, on a different axis:

```kotlin
@Composable
fun VideoPlayerScreen(controller: SystemBarsController) {
    SystemBarsEffect(controller, visibility = SystemBarsVisibility.Immersive)
    // ...
}
```

Both screens can be composed at once. The first claims the status bar's icons, the second claims
visibility, and neither touches the other's axis.

## 5. If you use dialogs or bottom sheets on Android

A `Dialog`, `Popup` or `ModalBottomSheet` renders into a window of its own, which the controller
never sees. One line at the top of its content lambda fixes it:

```kotlin
ModalBottomSheet(onDismissRequest = ::dismiss) {
    DialogWindowSystemBarsEffect(controller)
    SheetContent()
}
```

No-op on iOS, where the sheet shares the app's one status bar.

## Next

[`03-guide.md`](03-guide.md) — what happens when two screens want the same axis, when a claim
changes over time, and what to do about rotation.
