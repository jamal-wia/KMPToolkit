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
