# Platform setup

One-time platform steps that are shared across more than one module, so they live here instead of
being repeated in every module's `05-platform-notes.md`. A module's own platform-notes file links
back here for the shared parts and covers only what's specific to it.

## Android

### Permissions are the consumer's responsibility

As explained in [`01-architecture.md`](01-architecture.md#android-manifests), no `kmptoolkit-*`
module declares a permission in its own manifest. If a module you use needs one (check its
`docs/<module>/05-platform-notes.md`), add it to your app's own `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

### minSdk

Every module targets `minSdk = 24`. If your app's `minSdk` is lower, raise it — there is no
lower-API fallback path.

## iOS

### `Info.plist` entries

Some modules require usage-description strings before the OS will grant a permission at runtime
(e.g. `NSCameraUsageDescription` for camera access via `kmptoolkit-permission`). These are
consumer-owned for the same reason Android permissions are: the toolkit cannot write your app's
user-facing justification text for you. Check the specific module's `05-platform-notes.md` for
which key it needs.

### Background modes

A module that does background work on iOS (background audio, background URL sessions) documents
the exact `UIBackgroundModes` entry it needs in its own `05-platform-notes.md` — none is declared
automatically.

## App-start initialization

Most modules need no setup beyond constructing them where you need them. Where a module does need
one-time initialization (for example, registering a platform callback at app launch), that is
called out explicitly in the module's `02-getting-started.md` — there is no implicit
auto-initialization via a content provider, app-start library, or similar mechanism, so a missing
initialization step fails loudly rather than silently doing nothing.
