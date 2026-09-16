# kmptoolkit-hardware-keys — Testing

This module has no `-testing` companion. Its only seam is a function, `(KeyEvent) -> Boolean`, and
the valuable thing to test in an app is that function — the policy — not the plumbing that installs
it.

## Test the policy as a plain function

Keep the decision in a named function and test it directly. It takes an `android.view.KeyEvent`,
which is constructible in a Robolectric or plain JVM unit test:

```kotlin
fun consumeVolumeKey(event: KeyEvent): Boolean =
    event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

@RunWith(RobolectricTestRunner::class)
class VolumeKeyPolicyTest {

    @Test
    fun `volume keys are consumed in both directions`() {
        assertTrue(consumeVolumeKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(consumeVolumeKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)))
    }

    @Test
    fun `back still reaches the dialog`() {
        assertFalse(consumeVolumeKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
    }
}
```

## Test that the policy is installed

`DialogWindowHardwareKeyPolicy` is process-wide state. A test that installs a policy must uninstall
it afterwards, or it leaks into every later test in the same JVM:

```kotlin
@After
fun clearPolicy() {
    DialogWindowHardwareKeyPolicy.uninstall()
}
```

To assert that your `Application` installs it, construct the application under Robolectric and send
a key through a Compose `Dialog` that calls the effect: inside the dialog content, read the window
from `(LocalView.current.parent as DialogWindowProvider).window` and call
`window.callback.dispatchKeyEvent(...)`. The module's own `DialogWindowHardwareKeyEffectUiTest` is a
worked example of that setup, including the `debugImplementation` Compose test manifest Robolectric
needs.

## Test that every dialog calls the effect

A missed call does not fail at runtime; the policy just does not apply in that window. That is best
caught by the build rather than a UI test: route dialogs through a single wrapper (see
[`03-guide.md`](03-guide.md#one-wrapper-for-every-dialog)) and add a unit test that scans your source
tree for raw `Dialog(`, `ModalBottomSheet(` and `Popup(` calls outside that wrapper.

## iOS and desktop

The effect is a no-op there; there is nothing to assert beyond the fact that shared code calling it
compiles.
