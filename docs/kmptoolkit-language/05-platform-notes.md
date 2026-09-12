# kmptoolkit-language — Platform notes

## Permissions

**None.** Locale APIs on both platforms need no permission, and this module declares nothing in its
`AndroidManifest.xml`.

## Android

### What `applyLanguageGlobally` actually does

```kotlin
Locale.setDefault(locale)
LocaleList.setDefault(LocaleList(locale))
```

Both calls matter. `Locale.setDefault` is what most JVM-level formatting and comparison code reads.
`LocaleList.setDefault` is what Android's own resource-resolution machinery reads on API 24+ —
`stringResource` in Compose ultimately resolves through it. Setting only one leaves the other stale,
which is a real and easy-to-reproduce split: number formatting picks up the new language while
`stringResource` calls do not, or the other way around.

### `getSystemLanguageCode` reads `Resources.getSystem()`, not `LocaleList.getDefault()`

`Resources.getSystem()` is the device's own configuration, populated by the OS and untouched by this
process's calls to `Locale.setDefault` / `LocaleList.setDefault`. Reading `LocaleList.getDefault()`
instead would report whatever `applyLanguageGlobally` last set — the chosen app language, not the
device's actual one — which defeats the entire purpose of resolving `AppLanguage.System` against it.
This module's own tests pin exactly this: `getSystemLanguageCode()`'s answer does not move after an
explicit `applyLanguageGlobally` call.

### `AppLanguage.System` resets to the device's own locale, not to a no-op

Applying `System` calls `Locale.setDefault` / `LocaleList.setDefault` with the device's own locale
(read the same way as `getSystemLanguageCode`), rather than leaving whatever was previously forced in
place. Switching Arabic → System therefore genuinely returns to the device's language, not to
whatever locale happened to be default before the app ever touched it.

### Configuration changes *do* undo the override, and this is the important part of this page

`Locale.setDefault` / `LocaleList.setDefault` are process-global JVM state, so it is tempting to
assume they survive on their own. They do not, because Android rewrites them itself.

On **every** configuration delivery to the process, `ActivityThread` (via
`ConfigurationController.updateLocaleListFromAppContext`) rebuilds the default locale list. It takes
the first locale of the **Application** context's resources and looks it up in the device's own
locale list: found, and that entry is promoted; not found, and it is pushed to the front. Either way
the winner is decided by whatever `Application.getResources()` returns — and Compose Multiplatform
resolves every string resource through `LocaleList.getDefault()`.

With plain application resources, that first locale is the device's. So each delivery quietly flips
the process back to the device language. Deliveries that recreate the activity are repaired by the
`attachBaseContext` override below — but the ones that do **not** recreate it leave nothing to repair
them:

- a 180° rotation (portrait to reverse-portrait)
- a window resize that stays inside the same size bucket
- an external display appearing

The next screen to compose then comes up in the device's language while your settings screen still
shows the chosen one. It was reproduced on real Android 13 and 17 devices, and it is invisible in an
emulator test that only ever rotates 90°.

**The fix is `LocalizedApplicationResources`**, and it is not optional:

```kotlin
class MyApplication : Application() {

    private val localizedResources = LocalizedApplicationResources()

    override fun getResources(): Resources = localizedResources.resourcesOf(baseContext)

    override fun onCreate() {
        super.onCreate()
        val holder: AppLanguageHolder = // …however your app builds it
        localizedResources.readLanguageFrom { holder.language }
    }
}
```

Serving application resources that already carry the chosen locale turns that same rebuild into a
re-assertion of it: the "best locale" the framework reads is now yours. Since an app pins a bare tag
(`ru`) while device entries usually carry a region (`ru-RU`), it is not found in the list and is
pushed to the front — which is exactly where a string resource reads from.

One residual case this cannot cover: if the device's own list carries your exact locale — bare, with
no region, which normally only happens via `adb` or on some ROMs — the framework finds it at a later
index and moves only `Locale.getDefault()`, leaving `LocaleList.getDefault()[0]` on the device's
language. That is what `kmptoolkit-language-compose`'s `AppLocale` re-pin is for on Android.

Three layers, then, each covering what the others cannot: the Application's resources survive a
configuration delivery, `attachBaseContext` gets the first frame after a recreation right, and
`AppLocale` re-pins synchronously before content composes.

### `localizedContext` and why the very first frame needs it

`applyLanguageGlobally` runs from wherever your app calls `createAppLanguageHolder` /
`setLanguage` — typically `Application.onCreate()`. An `Activity`'s `attachBaseContext(Context)` is
called by the framework *before* your own code has necessarily run for this launch (a cold start
racing `Application.onCreate()` against the first `Activity.attachBaseContext()`, or a process
restarted by the OS after being killed in the background), so the base `Context` an `Activity`
starts with can still reflect the device's language for that first frame.

```kotlin
class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase, currentLanguage()))
    }
}
```

`localizedContext` builds its override `Configuration` from scratch rather than copying the base
context's own configuration, and deliberately leaves `fontScale` at `0` — the sentinel
`Configuration.updateFrom` treats as "do not override this field". The context this returns has its
override merged onto every later configuration delivered to it for as long as it is used; carrying a
real `fontScale` (or copying the whole base configuration into the override) would freeze dark mode,
orientation and font size at whatever they happened to be on that first frame, for the rest of that
context's life. Locale is the only field this function means to carry.

## iOS

### What `applyLanguageGlobally` actually does

```kotlin
NSUserDefaults.standardUserDefaults.setObject(listOf(code), forKey = "AppleLanguages")
```

iOS has no equivalent of `Locale.setDefault` — the `AppleLanguages` array in the standard user
defaults domain is what `NSBundle` and Compose Resources both read to decide which
`.lproj`/resource bundle to serve strings from. Writing a single-element array pins the app to
exactly that language rather than letting it fall through the device's own preference order.

`applyLanguageGlobally(AppLanguage.System)` calls `removeObjectForKey` instead of writing an empty
array — removing the per-app override entirely, so the device's own `AppleLanguages` preference
order takes back over. `synchronize()` is called afterward (deprecated since iOS 12, called anyway)
so `NSBundle` picks up the change before the next scheduled sync point, rather than possibly serving
a stale language for one more `stringResource` call.

### `getSystemLanguageCode` reads the *global* defaults domain, not `NSLocale.preferredLanguages`

`NSLocale.preferredLanguages` reflects this app's own `AppleLanguages` override once
`applyLanguageGlobally` has written one — so once any explicit language has ever been chosen, it
reports the chosen language, not the device's. `getSystemLanguageCode` instead reads `AppleLanguages`
from `NSUserDefaults`' `NSGlobalDomain`, which the per-app override does not touch, and only falls
back to `NSLocale.preferredLanguages` when the global value is unavailable (some simulator
configurations). This mirrors the exact problem `Resources.getSystem()` solves on Android — see
above — and for the same reason: resolving `AppLanguage.System` needs the device's actual preference,
not whatever this app has already forced onto itself.

### No activity-recreation equivalent

iOS hosts one process-stable app lifecycle with no `Activity`-style recreation, so there is nothing
analogous to `localizedContext` / `attachBaseContext` to reach for. A `key(language.code)` in
`kmptoolkit-language-compose`'s `AppLocale` is enough to force Compose Resources to re-resolve
strings after a language change, since `NSBundle.mainBundle.localizations` is read fresh on each
call rather than cached per composition the way Android's `LocaleList` default can be.

> Some iOS system-supplied strings (date formatters, `UIAlertController`'s "Cancel", and other
> strings owned by the OS rather than your bundle) only pick up a new language after the process
> restarts. There is nothing this module — or any in-process code — can do about that; it is a
> platform limitation, not a bug in `applyLanguageGlobally`.
