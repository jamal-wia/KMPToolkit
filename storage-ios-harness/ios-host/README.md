# storage-ios-harness: the iOS Keychain host app

Not published, not part of the BOM. A minimal SwiftUI app plus a Kotlin static framework (`:storage-ios-harness`)
that runs **real Keychain round trips** against the `kmptoolkit-storage` public API on the iOS simulator. A
Kotlin/Native test binary has no usable Keychain, so everything only a device decides is a case here, not a unit test
(see `docs/kmptoolkit-storage/06-testing.md` and the I-harness list of the storage plan, section 6.6).

## Build and run (by a person; agents only compile it)

```bash
cd storage-ios-harness/ios-host
xcodebuild -project StorageHarness.xcodeproj -scheme StorageHarness -sdk iphonesimulator \
  -configuration Debug -derivedDataPath /tmp/storage-harness build
```

The first build phase runs `./gradlew :storage-ios-harness:linkDebugFrameworkIosSimulatorArm64` (JDK on the path,
`ANDROID_HOME` defaulting to `~/Library/Android/sdk`). Or open the project in Xcode and run the `StorageHarness` scheme
on an iPhone simulator. Each row of the list runs one case; automatic cases end `PASS` or `FAIL`, manual ones `OBSERVED`.

To get a log without tapping (for example for a release check or to paste into an issue), run the app with the
launch argument `--run-automatic`: it runs every automatic case and prints one `<id> PASS|FAIL` line per case, the
case's checks indented below it, and a final `HARNESS DONE cases=<n> passed=<p> skipped=<s> notPassed=<k>` line.
Every automatic case has a hang bound of 120 seconds: a case that shows a sheet or blocks in a Security call is reported
`FAIL (timed out)` and the run goes on, so the last line always appears. The argument is honoured once per process.
A case whose precondition the simulator lacks is reported `SKIPPED` with the reason (not a pass, not a failure,
counted in `skipped=`, never in `notPassed=`): `ensure-key-user-presence` needs a passcode or an enrolled Face ID
(Features > Face ID > Enrolled), so enroll Face ID first to have it counted as a PASS in a release check.

```bash
xcrun simctl boot "iPhone 16"   # or any booted iPhone simulator
xcrun simctl install booted <derived data>/Build/Products/Debug-iphonesimulator/StorageHarnessHost.app
xcrun simctl launch --console booted io.github.jamal-wia.kmptoolkit.storageharness --run-automatic
```

A `FAIL` line is a failed check of the library (a failed library call fails its case at the step that made it, never
passes silently). Manual cases are not run by the argument.

The entitlements grant two access groups besides the default one (`StorageHarness.entitlements`). The simulator accepts
these literal identifiers; a real device would need a team id prefix, so the harness is simulator-only.

## Cases

| Case | Automatic | What it proves |
|---|---|---|
| `duplicate-retry` | yes | Forty concurrent first writes of one key all succeed (`errSecDuplicateItem` retried as an update). |
| `put-remove-race` | yes | Eight writers, four removers and two clearing stores race on three keys: nothing fails (a put that lost its add and then met a remove retries, bounded) and every key ends absent or whole. |
| `clear-across-group` | yes | `clear()` empties a store across an access group and spares a same-named store in another group. |
| `null-group-clear` | yes | Confirms or refutes I-2b (reproduces the old group-less delete by hand and prints whether it reaches the shared group), then checks the 6.0.0 `clear()` spares the shared-group store and leaves no probe, does not adopt a probe another process left in the shared group, and survives six concurrent clears. |
| `synchronizable-clear` | yes | `clear()` removes synchronizable items (I-2a). |
| `accessibility-on-update` | yes | An overwrite moves an item to the configured accessibility (I-1). |
| `missing-entitlement` | yes | `-34018` on an ungranted group is `Unsupported(MISSING_ENTITLEMENT)` for every operation. |
| `ensure-key-changes-nothing` | yes | `ensureKey()` creates and changes nothing. |
| `ensure-key-user-presence` | yes (skipped without a passcode or Face ID) | `ensureKey()` of a user-presence store that holds an item is `Success` and shows no sheet (a `FAIL` shows the real status and means the mapping needs a fix). |
| `up-user-presence`, `up-biometry-any`, `up-biometry-current-set` | manual | The system sheet on every `get`, matching and non-matching Face ID, cancel is `AccessDenied(-128)`. |
| `bcs-store`, `bcs-read` | manual | The status after a Face ID re-enrollment with `BIOMETRY_CURRENT_SET`. |
| `passcode-store`, `passcode-read` | manual | What a read answers after the passcode is removed with `WHEN_PASSCODE_SET_THIS_DEVICE_ONLY`. |

For a user-presence run enable Face ID in the simulator (Features > Face ID > Enrolled) and use Matching Face /
Non-matching Face / Cancel at the sheet.
