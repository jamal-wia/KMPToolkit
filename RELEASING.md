# Releasing

This is the maintainer runbook for publishing a new `kmptoolkit-*` version to Maven Central. It is
not something the assistant runs on its own — publishing always requires the maintainer's explicit
go-ahead (see `.claude/CLAUDE.md` § Publishing).

## One-time setup (per machine)

Add to `~/.gradle/gradle.properties` (never to this repository):

```properties
mavenCentralUsername=<Central Portal token username>
mavenCentralPassword=<Central Portal token password>
signing.keyId=<last 8 chars of your GPG key id>
signing.password=<GPG key passphrase>
signing.secretKeyRingFile=<path to secring.gpg>
```

CI does not read this file — it uses the equivalent `ORG_GRADLE_PROJECT_*` environment secrets set
on the repository (see `.github/workflows/publish.yml`).

The build machine needs:

- **macOS with Xcode**, for the iOS targets. Kotlin/Native 2.4 supports Xcode 26.4, which is what
  CI selects; a newer Xcode may build with a compatibility warning, but a release is built on the
  supported one.
- **JDK 21** for the Gradle daemon (`gradle/gradle-daemon-jvm.properties`); Gradle provisions it
  through the toolchain resolver if it is missing, as it does the JDK 25 that the
  `kmptoolkit-video-player-javafx` tests run on.

## Release steps

1. **Confirm `main` is green.** `./gradlew build checkKotlinAbi` and
   `./gradlew testAndroidHostTest iosSimulatorArm64Test` must both pass on the commit you intend to
   release (see `.claude/CLAUDE.md` § Verification). If the release changes
   `kmptoolkit-video-player-javafx`, also run its real-media soak on a Mac with a display, in the split
   commands of `docs/kmptoolkit-video-player-javafx/06-testing.md` (`jvmRealMediaTest`). The `ResumeAtWrapD2Boss1B6RealMediaTest` class does not fit one 10-minute command (about 12.6 minutes): run it per method with `--tests`.
2. **Bump the version.** Edit `kmptoolkit.version` in `gradle.properties` — this is the single
   source of truth; every `kmptoolkit-*` module and the `kmptoolkit-bom` constraints read it.
   Follow semver: see `.claude/CLAUDE.md` § Public API and compatibility for what qualifies as
   patch/minor/major. Read every `api/` dump diff since the last release before choosing: **any
   added public symbol is at least a minor, never a patch** — and that includes a new entry in a
   sealed hierarchy or enum (a new `CommonPermission` or `PermissionStatus` case, say), which is
   binary-compatible but stops a consumer's exhaustive `when` from compiling.
3. **Update `CHANGELOG.md`.** Move the `[Unreleased]` entries under a new `## [x.y.z] - YYYY-MM-DD`
   heading.
4. **Update the root `README.md` install snippet** if the shown version string is pinned literally
   rather than left as a placeholder.
5. **Commit and push** the version bump on a task branch, per `.claude/CLAUDE.md` § Branches and commits.
   Do not tag or release from this commit yet — get it reviewed and merged to `main` first.
6. **Create a GitHub Release** against the merged commit on `main`, tagged `x.y.z` (no `v` prefix,
   matching `kmptoolkit.version`). This is a manual, human action — the assistant does not create
   GitHub releases (see `.claude/CLAUDE.md` § Publishing).
7. **The `publish.yml` workflow fires automatically** on the release. It runs `checkKotlinAbi`, then
   `publishAndReleaseToMavenCentral` for every module. It does not run the tests: step 1 is the only
   test gate, so do not skip it.
8. **Monitor the deployment** in the [Central Portal](https://central.sonatype.com/publishing) —
   `automaticRelease = true` means a successful build publishes without a manual "release" click,
   but validation failures (missing POM fields, unsigned artifacts) still show up there first.
9. **Verify the release resolves** from a clean project:
   ```kotlin
   dependencies {
       implementation(platform("io.github.jamal-wia:kmptoolkit-bom:x.y.z"))
       implementation("io.github.jamal-wia:kmptoolkit-logging")
   }
   ```
   and with the sample app against Maven Central (`.claude/CLAUDE.md` § Verification):
   `./gradlew :sample:assembleDebug -PusePublishedArtifacts`. An app whose own Kotlin sources were
   not compiled still assembles, so confirm the sample's classes are in the APK —
   `unzip -p sample/build/outputs/apk/debug/sample-debug.apk 'classes*.dex' | grep -a -c sample/MainActivity`
   must print a number above zero.

## Storage checks (a release that touches `kmptoolkit-storage`, `kmptoolkit-storage-jna` or `kmptoolkit-files`)

The secure stores call the operating system, and no unit test can see what a real keychain, a real Keystore or a real
Secret Service answers. These checks are manual, each is run on the release branch, and the result (what ran, on which
OS and JDK or Xcode version, and what was observed) goes into the release notes. A skipped check is not a passed
check: read the skip count of every run.

1. **Fast gates that always run.** `./gradlew :kmptoolkit-storage:checkKotlinAbi :kmptoolkit-storage-testing:checkKotlinAbi
   :kmptoolkit-storage-jna:checkKotlinAbi :kmptoolkit-files:checkKotlinAbi`, then the docs-sample checks of
   `docs/kmptoolkit-storage/06-testing.md` ("Docs samples"): `:docs-samples:compileKotlinJvm`, and the classes
   `DocsSamplesDesktopRunTest`, `DocsSamplesDriftTest` (`:kmptoolkit-storage:jvmTest`), `DocsSamplesTestingTest`
   (`:kmptoolkit-storage-testing:jvmTest`) and `AndroidDocsSamplesRound11Test` (`:kmptoolkit-storage:testAndroidHostTest`).
2. **Real key stores of the desktop FFM binding, one run per operating system the release supports.** The suites do
   nothing unless asked, and with `required=true` a skip is a failure, so a broken key store cannot pass as green. Preparation
   per OS (an unlocked login keychain on macOS, the logged-on user's interactive session on Windows, a session bus and an
   unlocked default collection on Linux) is in `docs/kmptoolkit-storage/06-testing.md` ("Real-store suites"):

   ```bash
   ./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmMacKeychainRealStoreTest' -Pkmptoolkit.secretstore=macos -Pkmptoolkit.secretstore.required=true
   ./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmDpapiRealStoreTest' -Pkmptoolkit.secretstore=windows -Pkmptoolkit.secretstore.required=true
   ./gradlew :kmptoolkit-storage:jvmTestFfm --tests '*FfmSecretServiceRealStoreTest' -Pkmptoolkit.secretstore=linux -Pkmptoolkit.secretstore.required=true
   ```

   On a Linux machine whose libsecret is older than 0.19, also run `--tests '*FfmSecretServiceOldLibsecretRealStoreTest'`
   with `-Pkmptoolkit.secretstore=linux -Pkmptoolkit.secretstore.required=true -Pkmptoolkit.secretstore.oldLibsecret=true`:
   every call must answer the missing-symbol status, which the store reports as `Unsupported(NO_KEY_STORE)`.
3. **The same on JDK 21 through the JNA bridge.** `./gradlew :kmptoolkit-storage-jna:jvmTest --tests '*RealKeychainSuiteTest'`
   (macOS), `'*RealDpapiSuiteTest'` (Windows) or `'*RealSecretServiceSuiteTest'` (Linux), each with the matching
   `-Pkmptoolkit.secretstore=` and `-Pkmptoolkit.secretstore.required=true`. On Linux run the three environments with
   `-Pkmptoolkit.secretstore.scenario=unlocked`, `nobus` and `noservice`.
4. **Windows lock file and DACL.** The one test class that needs a Windows host and a real NTFS or ReFS volume:

   ```bash
   ./gradlew :kmptoolkit-storage:jvmTest --tests '*CrossProcessLockWindowsAclRound5Test' --tests '*CrossProcessLockRound5Test' --tests '*CrossProcessLockTest' --tests '*CrossProcessLockBreakerR3Test'
   ```

   Expected: `CrossProcessLockWindowsAclRound5Test` runs 2 tests and skips 0 (a skip means the default file system had no
   `acl` view or had a `posix` one, that is, the host was not Windows) and both pass; `CrossProcessLockRound5Test` runs 5
   of its 6 tests (the POSIX one is skipped on Windows). If the first Windows test fails, read the DACL line of the
   log: the expected entries are the token user (`DOMAIN\name`) and SYSTEM, both allow, nothing else. Also run the
   Windows classes of `kmptoolkit-files` (`./gradlew :kmptoolkit-files:jvmTest --tests '*OwnerOnlyWindows*'`) and read their skip reasons.
5. **Packaging smoke, once per binding, on a Mac, from the release branch.** `smoke-storage-desktop` is a non-published
   host app (not in the BOM) that packages a Compose Desktop release distribution with ProGuard (obfuscation on) and runs
   one plain and one secure storage round trip from the packaged app:

   ```bash
   ./gradlew :smoke-storage-desktop:smokeFfm    # JDK 25 packaging, the FFM binding, --enable-native-access=ALL-UNNAMED
   ./gradlew :smoke-storage-desktop:smokeJna    # JDK 21 packaging plus kmptoolkit-storage-jna
   ```

   Read the last line of the `---- smoke <variant> ----` block the task prints. `SMOKE-RESULT: PASS`: both round trips
   passed through the real native binding. `SMOKE-RESULT: PASS-WITH-SKIP (...)`: the secure round trip was skipped (no key
   store, a locked keychain, a session without one), which is **not** a pass of the secure path: run again in a logged-in
   session, or pass `-Pkmptoolkit.secretstore.required=true` to make a skip a failure. A failed task means a missing keep
   rule (the output names the binding class that could not be loaded), a missing native-access flag or a packaging error:
   fix the rule in the library's `META-INF/proguard/*.pro`, never in the smoke build. The smoke feeds ProGuard exactly the
   `META-INF/proguard/*.pro` files of the runtime jars, because ProGuard (unlike R8) does not read them itself; a desktop app that
   shrinks with ProGuard must do the same. A secure run leaves one item labelled "KMPToolkit packaging smoke (safe to
   delete)" in the login keychain, which the next run reuses. Options: `-Psmoke.probeOnly=true` (probe only, touches no key),
   `-Psmoke.javaHome25=<dir>` and `-Psmoke.javaHome21=<dir>` (a full JDK with jmods and jpackage). Two negative controls
   must fail: `-Psmoke.control.noLibraryRules=true` and `-Psmoke.control.noNativeAccessFlag=true`.

   **The oldest FFM runtime.** The build's test launcher for the FFM binding is JDK 25 and its sources are checked against the
   JDK 22 API on every build, but only a run proves JDK 22 itself: once per release run
   `./gradlew :smoke-storage-desktop:smokeFfm -Psmoke.javaHome25=<a JDK 22 installation>` and
   read the result line as above.

   Signing and notarisation are not covered. With an Apple Developer ID, additionally build a signed and notarised
   app of a plain-store-only host and record the result in the release notes; a notarised app that adds
   `kmptoolkit-storage-jna` must sign the nested `libjnidispatch` as well, which has not been verified.
6. **macOS key-store behaviour that only a real keychain can show.** The three open items listed at the end of the
   desktop section of `docs/kmptoolkit-storage/05-platform-notes.md` ("Open verification items"): whether
   `kSecUseAuthenticationUIFail` applies to `SecItemAdd` and `SecItemDelete` on the file keychain, whether a locked keychain
   and an item of another code identity both answer -25308 (never -25300), and whether deleting the previous unsigned build's
   item prompts. Observe them with `allowUserInteraction = false` and then `true` on a locked and an unlocked login
   keychain, and write the result into that section.
7. **iOS Keychain harness (before releasing a change to the iOS secure store).** Enrol Face ID in the simulator first
   (Features > Face ID > Enrolled), so that `ensure-key-user-presence` is counted as a pass. Build and run
   `storage-ios-harness/ios-host` on an iPhone simulator
   (`xcodebuild -project storage-ios-harness/ios-host/StorageHarness.xcodeproj -scheme StorageHarness -sdk iphonesimulator build`,
   then install and run the app, or run the `StorageHarness` scheme in Xcode), and launch it once with the launch argument
   `--run-automatic`:

   ```bash
   xcrun simctl launch --console booted io.github.jamal-wia.kmptoolkit.storageharness --run-automatic
   ```

   Keep the log. Every case line must end `PASS`, and the last line must read `notPassed=0` and `skipped=0`
   (`HARNESS DONE cases=<n> passed=<p> skipped=<s> notPassed=<k>`). A `SKIPPED` line names the missing precondition, and
   `FAIL (timed out)` means a case hung for 120 s and counts as not passed. The automatic cases are `duplicate-retry`,
   `put-remove-race`, `clear-across-group`, `null-group-clear`, `synchronizable-clear`, `accessibility-on-update`,
   `missing-entitlement`, `ensure-key-changes-nothing` and `ensure-key-user-presence`; a `FAIL` of the last shows the real status
   of `ensureKey` on a user-presence store. Then run the manual rows. For `up-user-presence`, `up-biometry-any` and
   `up-biometry-current-set`, run once with Matching Face, once with Non-matching Face and once cancelling the sheet: the `get`
   must show the sheet every time, a cancel must read `AccessDenied(-128)`, a failed authentication `AccessDenied(-25293)`, and
   `ensureKey` and `clear` must never show a sheet. For `bcs-store` / `bcs-read`, switch Enrolled off and on between the two steps
   and copy the observed status into `docs/kmptoolkit-storage/05-platform-notes.md`; the same for `passcode-store` /
   `passcode-read`. If `null-group-clear` reports that the wide delete does not reach the shared group, the default-group probe
   of `clear()` rests on a wrong assumption on that iOS version: record it and raise it before releasing. The host app's own
   `README` documents the verdicts and the cases table.
8. **Android emulator checklist (the paths no host test can force).** Use an emulator or device per API level named in
   each row, with `AndroidSecureStorageOptions` as stated, and record the exact exception class, message and cause chain
   where a row says so; a difference from the expectation means a recognition constant in the library is wrong on that API
   level.
   - Lock-screen change with a DEFAULT store (no `userAuthentication`, no `unlockedDeviceRequired`; API 28, 30 and 34 or
     newer): `put` a value, then add a lock screen, change it and remove it, cold-starting between the steps and reading the
     value each time. Record whether any step makes it `Undecryptable`. If none does, no document may name a lock-screen
     change as the cause of a lost value for a default store; if one does, the sites that today name only a store opened with
     `userAuthentication` (`02-getting-started.md`, `03-guide.md`, `05-platform-notes.md`, the `StorageError` and
     `AndroidSecureStorageOptions` KDoc) need the cause added.
   - Lock-screen removal, with a store opened with `AndroidUserAuthentication(...)` (API 30 and API 34 or newer): `ensureKey()`
     and one `put`, remove the lock screen (Settings, Security: None), force-stop, cold start, call `ensureKey()`. Expected
     `Success`, the old value `Undecryptable`, a new `put` and `get` work. Record what `KeyStore.getKey` throws at that
     lookup: expected `UnrecoverableKeyException("User changed or deleted their auth credentials")` with no cause, or `null` on
     API 31 or newer if the keystore already deleted the key.
   - The same without the force-stop: in the same process the store must answer `Unavailable`; after the next cold start it recovers
     as above. Record the exception at first use (`Cipher.init`): expected `KeyPermanentlyInvalidatedException`.
   - Locked device with `unlockedDeviceRequired = true`, on API 28 to 30, on API 31 or 32, and on API 33 or newer: store a value, lock the
     device, then in the live process (key cached) and after a cold start while locked call `get`, `put` and `ensureKey()`.
     Expected `Unavailable` each time, one key lookup, the old value readable after unlock. Record the class, message and cause
     chain of the `Cipher.init` failure (API 31 or newer: `InvalidKeyException("Keystore operation failed")` around a
     `KeyStoreException` with code -72; API 28 to 30: `UserNotAuthenticatedException`); on API 33 or newer also
     `requiresUserAuthentication()` of the cause.
   - Biometric enrolment with the default key (`allowDeviceCredential = true`): enrol or delete a fingerprint, cold start,
     `ensureKey()`: no invalidation, old values readable. With a biometric-only key (`allowDeviceCredential = false`, API 30 or newer): on a device
     with no enrolled biometric `ensureKey()` must answer `Unavailable` and generate nothing, and succeed after an enrolment;
     enrolling another fingerprint then takes the invalidation path of the first row.
   - `getKey` through the real `AndroidKeyStore` provider answers the key for an alias that exists and `null`, without an
     exception, for one that does not (API 31 or newer); and an alias `<name>.kmptoolkit.securestorage.key` holding an EC key that
     is not a secret key must make the store answer `Unavailable` and leave the entry alone.
   - An API 24 to 30 device on which the keystore daemon can be killed at the lookup: record whether a new key is
     generated (a documented residual, not forceable on an emulator).

## Manual publish (fallback)

If the CI workflow is unavailable, publish locally from a machine with the one-time setup above:

```bash
./gradlew checkKotlinAbi testAndroidHostTest
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

`--no-configuration-cache` matches the CI workflow. The vanniktech publishing plugin has supported
the configuration cache since version `0.34`, but the release run keeps the flag so a publish never
reuses a cached configuration.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Central Portal deployment stuck in `VALIDATING` | A POM field is missing or malformed — check `kmptoolkitPublish.pomName` / `pomDescription` were set on every published module |
| `signAllPublications()` did not run, artifacts unsigned | None of `signing.keyId`, `signingInMemoryKey`, or `ORG_GRADLE_PROJECT_signingInMemoryKey` was visible to the build — see `PublishConventionPlugin.kt`'s conditional |
| `signAndroidPublication` / `signXxxPublication` fails with `Could not read PGP secret key` | The `GPG_KEY_PASSWORD` secret is wrong or empty — Gradle parsed the key but could not unlock it with the passphrase in `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`. Verified locally against plugin `0.37.0`: a wrong or empty passphrase produces exactly this message. Check the secret exists in this repository (secrets are per-repository, not per-account) and matches the passphrase of the key in `GPG_KEY`. |
| `signXxxPublication` fails with `has no configured signatory` | `GPG_KEY` is in a format Gradle cannot parse, or `GPG_KEY_ID` does not match any key in it. Accepted formats: the raw ASCII-armored output of `gpg --export-secret-keys --armor <key id>`, or the base64 of the *binary* export (`gpg --export-secret-keys <key id> \| base64`, which is what Paginator uses). Base64 of the *armored* text is **not** accepted. |
| `checkKotlinAbi` fails right before a release | A module's public API changed without running `./gradlew updateKotlinAbi` and committing the updated `api/` dump — this is the ABI-validation gate doing its job, not a false positive |
| A module resolves with the wrong version from a consumer | `kmptoolkit.version` was bumped but the `kmptoolkit-bom` constraint wasn't regenerated — the BOM reads the same property, so re-running the build should be enough; if not, check the BOM module's `constraints { }` block by hand |
| `Component with package url: 'pkg:maven/...@x.y.z' already exists` during `publishAndReleaseToMavenCentral` | `kmptoolkit.version` in `gradle.properties` still holds an already published version (the release was tagged before step 2 was committed and merged). Maven Central never accepts the same coordinates twice: bump the version, merge it to `main`, then recreate the tag and the GitHub Release on that commit |
| `KLIB loader: The same 'unique_name=…' found in more than one library` during `compile…IosMainKotlinMetadata` of `kmptoolkit-systembars`, `kmptoolkit-logging-overlay`, `kmptoolkit-language-compose` and `kmptoolkit-hardware-keys` | Expected with Compose Multiplatform 1.12.x: its published metadata depends on both the androidx Lifecycle, SavedState, Collection and Annotation libraries and JetBrains' older forks of them, and only the shared iOS metadata compilation sees both copies. Harmless (iOS binaries link one copy) and not caused by this repository's build files; do not silence it with an exclude, a substitution or `-nowarn`. After each Compose Multiplatform upgrade run `:kmptoolkit-hardware-keys:compileIosMainKotlinMetadata --rerun`, count the warnings and remove this row once they are gone |
