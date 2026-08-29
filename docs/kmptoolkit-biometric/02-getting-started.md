# kmptoolkit-biometric — Getting started

## 1. Add the dependency

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-biometric:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-biometric-testing:<version>")
        }
    }
}
```

The version is whatever `kmptoolkit.version` says in this repository's `gradle.properties`; with
the BOM, omit it.

On Android the module brings `androidx.biometric` with it. It declares no permission of its own —
`androidx.biometric` contributes three install-time ones, listed in
[`05-platform-notes.md`](05-platform-notes.md).

## 2. Depend on the interface in shared code

```kotlin
class SecretScreenPresenter(private val gate: BiometricGate) {

    suspend fun onOpen(strings: Strings): ScreenState {
        if (gate.availability() != BiometricAvailability.Available) {
            return ScreenState.PasswordFallback
        }
        return when (gate.authenticate(
            BiometricPromptText(
                title = strings.unlockTitle,
                subtitle = strings.unlockSubtitle,
                cancelLabel = strings.cancel,
            ),
        )) {
            BiometricResult.Authenticated -> ScreenState.Secret
            else -> ScreenState.Locked
        }
    }
}
```

Shared code never calls a factory — it takes the interface. Only the platform entry points below
name a `createBiometricGate`.

## 3. Build it on Android

```kotlin
class App : Application() {

    lateinit var biometricGate: BiometricGate
        private set

    override fun onCreate() {
        super.onCreate()
        biometricGate = createBiometricGate(this)
    }
}
```

One thing to get right, and it fails quietly rather than loudly: **the activity that hosts the
prompt must be a `FragmentActivity`.** `ComponentActivity` and `AppCompatActivity` both are; a bare
`Activity` is not, and every `authenticate` from it returns `BiometricResult.NoPromptHost`.

Build the gate once — in `Application.onCreate`, or wherever you assemble dependencies — and hold
the result for the process lifetime; a gate built per screen registers its own activity tracking
each time, which is harmless but pointless.

## 4. Build it on iOS

```kotlin
// iosMain, or from Swift
val biometricGate: BiometricGate = createBiometricGate()
```

Then add the usage description to your app's `Info.plist`, in your own words and language:

```xml
<key>NSFaceIDUsageDescription</key>
<string>Unlock your saved notes with Face ID.</string>
```

Without it, iOS **terminates** the app the first time you evaluate a policy on a Face ID device. It
does not refuse the call, and there is no result to handle — so this is worth checking before you
ship rather than after.

## 5. Accept the device PIN as well, if that is what you want

```kotlin
val gate: BiometricGate = createBiometricGate(
    config = BiometricGateConfig(policy = BiometricPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL),
)
```

This is a real security decision, not a convenience toggle — read
[`03-guide.md`](03-guide.md#the-device-credential-decision) before making it.

## 6. Test the branches a device will not give you

```kotlin
@Test
fun `an unenrolled device offers the password fallback`() = runTest {
    val gate = ScriptedBiometricGate(
        availability = BiometricAvailability.Unavailable(BiometricUnavailability.NOT_ENROLLED),
    )

    assertEquals(ScreenState.PasswordFallback, SecretScreenPresenter(gate).onOpen(strings))
}
```

See [`06-testing.md`](06-testing.md) for the rest of the fixture.

## Where to go next

- [`03-guide.md`](03-guide.md) — prompt copy, the credential decision, grace periods, mistakes
- [`04-api-reference.md`](04-api-reference.md) — every symbol
- [`05-platform-notes.md`](05-platform-notes.md) — manifest, `Info.plist`, API levels
