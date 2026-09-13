# kmptoolkit-hardware-keys — Getting started

## Install

```kotlin
// build.gradle.kts of the module that composes dialogs
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-hardware-keys:<version>")
        }
    }
}
```

The artifact publishes `android`, `iosArm64`, `iosSimulatorArm64` and `jvm`, so it can go in
`commonMain` of a module shared with desktop. It declares no Android permission.

## 1. Install the policy (Android)

In `Application.onCreate`, before any dialog window can open:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DialogWindowHardwareKeyPolicy.install({ event ->
            // Consume the volume keys everywhere, including inside dialogs and sheets.
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        })
    }
}
```

The interceptor runs on the main thread for every key event delivered to a dialog window that calls
the effect, before the dialog itself sees the event. `true` consumes it; `false` lets it through
untouched.

Pass a `Logger` from `kmptoolkit-logging` as the second argument if you want a warning when a second
policy replaces the first — see [`03-guide.md`](03-guide.md#install-once).

## 2. Call the effect in every dialog window

```kotlin
@Composable
fun ConfirmDeleteDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        DialogWindowHardwareKeyEffect()
        // ... dialog content
    }
}

@Composable
fun FiltersSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        DialogWindowHardwareKeyEffect()
        // ... sheet content
    }
}
```

The call goes **inside** the content lambda, because that is where the composition runs in the
dialog's window. Called outside, it is composed in the Activity's window and protects nothing.

## 3. Handle the Activity window as you already do

The effect does not replace your Activity's key handling. Keys pressed with no dialog open are
delivered to the Activity, and your `onKeyDown` / `dispatchKeyEvent` handles them. Share the decision
between both places by calling the same function from each:

```kotlin
fun consumeVolumeKey(event: KeyEvent): Boolean =
    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

// Application.onCreate
DialogWindowHardwareKeyPolicy.install(::consumeVolumeKey)

// MainActivity
override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    consumeVolumeKey(event) || super.dispatchKeyEvent(event)
```
