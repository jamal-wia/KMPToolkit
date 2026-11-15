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

The entitlements grant two access groups besides the default one (`StorageHarness.entitlements`). The simulator accepts
these literal identifiers; a real device would need a team id prefix, so the harness is simulator-only.

## Cases

| Case | Automatic | What it proves |
|---|---|---|
| `duplicate-retry` | yes | Forty concurrent first writes of one key all succeed (`errSecDuplicateItem` retried as an update). |
| `clear-across-group` | yes | `clear()` empties a store across an access group and spares a same-named store in another group. |
| `null-group-clear` | yes | Confirms or refutes I-2b (reproduces the old group-less delete by hand and prints whether it reaches the shared group), then checks the 6.0.0 `clear()` spares the shared-group store and leaves no probe. |
| `synchronizable-clear` | yes | `clear()` removes synchronizable items (I-2a). |
| `accessibility-on-update` | yes | An overwrite moves an item to the configured accessibility (I-1). |
| `missing-entitlement` | yes | `-34018` on an ungranted group is `Unsupported(MISSING_ENTITLEMENT)` for every operation. |
| `ensure-key-changes-nothing` | yes | `ensureKey()` creates and changes nothing. |
| `up-user-presence`, `up-biometry-any`, `up-biometry-current-set` | manual | The system sheet on every `get`, matching and non-matching Face ID, cancel is `AccessDenied(-128)`. |
| `bcs-store`, `bcs-read` | manual | The status after a Face ID re-enrollment with `BIOMETRY_CURRENT_SET`. |
| `passcode-store`, `passcode-read` | manual | What a read answers after the passcode is removed with `WHEN_PASSCODE_SET_THIS_DEVICE_ONLY`. |

For a user-presence run enable Face ID in the simulator (Features > Face ID > Enrolled) and use Matching Face /
Non-matching Face / Cancel at the sheet.
