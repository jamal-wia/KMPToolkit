# kmptoolkit-activity — Testing

There is no `-testing` artifact: both seams here are interfaces a test implements directly, and a
`SystemScreenRequest` is built with its public constructor.

## Faking `ActivityAccess`

A four-line fake that always answers with one activity, or with none:

```kotlin
class FixedActivityAccess(private val activity: Activity?) : ActivityAccess {
    override fun <R> withActivity(block: (Activity) -> R): R? = activity?.let(block)
    override fun addOnActivityResumedListener(listener: (Activity) -> Unit): ActivitySubscription =
        object : ActivitySubscription { override fun cancel() = Unit }
    override fun release() = Unit
}
```

## Testing a `SystemScreenLauncher` of your own

`SystemScreenRequest` holds real `Intent`s and a real `Context`, so a launcher test runs under
Robolectric (`androidUnitTest`), not on the plain JVM, where `Intent` is a stub. Build the request the
way a module would — every candidate, no flags — and assert on what your launcher did. Here the kiosk
launcher from [`03-guide.md`](03-guide.md#your-own-launcher) takes its window as a parameter,
`kioskLauncher(window: AllowlistWindow)`, so a test can count what it opened:

```kotlin
class FakeAllowlistWindow : AllowlistWindow {
    var opened = 0
    var closed = 0
    override fun open() { opened++ }
    override fun close() { closed++ }
}
```

```kotlin
@RunWith(AndroidJUnit4::class)
class KioskLauncherTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `enrolment opens inside the allowlist window, other screens do not`() {
        val window = FakeAllowlistWindow()
        val launcher: SystemScreenLauncher = kioskLauncher(window)

        launcher.launch(SystemScreenRequest(listOf(Intent(Settings.ACTION_BIOMETRIC_ENROLL)), application, BiometricEnrollmentScreen))
        launcher.launch(SystemScreenRequest(listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)), application, LocationSettingsScreen))

        assertEquals(1, window.opened)
    }

    @Test
    fun `the window is closed again when nothing could be opened`() {
        shadowOf(application).checkActivities(true) // unresolvable starts now throw, as on a device
        val window = FakeAllowlistWindow()

        val launched = kioskLauncher(window).launch(
            SystemScreenRequest(listOf(Intent("no.such.SCREEN")), application, BiometricEnrollmentScreen),
        )

        assertFalse(launched)
        assertEquals(window.opened, window.closed)
    }
}
```

Useful Robolectric pieces:

- `shadowOf(application).checkActivities(true)` makes a start with no matching activity throw
  `ActivityNotFoundException`, as a device does. To make a screen resolvable, register it:
  `shadowOf(packageManager).addActivityIfNotPresent(component)` plus
  `addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })`.
- `shadowOf(application).nextStartedActivity` returns the next started intent, flags included — the
  way to check which task a preset asked for (`NEW_TASK | NEW_DOCUMENT` for `SeparateTask`, none for a
  caller-task start).
- That queue is shared by the application and every activity, so it cannot tell *which context*
  started a screen. To prove a start came from a particular activity, give the launcher an
  `ActivityAccess` that answers with an activity subclass overriding
  `startActivity(Intent, Bundle?)` to record the intent.
- `callerTask` falls back to a separate task off the main thread; Robolectric tests run on the main
  thread, so call it directly, not from a background dispatcher.
