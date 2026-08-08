# Releasing

This is the maintainer runbook for publishing a new `kmptoolkit-*` version to Maven Central. It is
not something the assistant runs on its own — publishing always requires the maintainer's explicit
go-ahead (see `CLAUDE.md` § Publishing).

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

## Release steps

1. **Confirm `main` is green.** `./gradlew build checkKotlinAbi` and
   `./gradlew testDebugUnitTest iosSimulatorArm64Test` must both pass on the commit you intend to
   release (see `CLAUDE.md` § Verification).
2. **Bump the version.** Edit `kmptoolkit.version` in `gradle.properties` — this is the single
   source of truth; every `kmptoolkit-*` module and the `kmptoolkit-bom` constraints read it.
   Follow semver: see `CLAUDE.md` § Public API and compatibility for what qualifies as
   patch/minor/major.
3. **Update `CHANGELOG.md`.** Move the `[Unreleased]` entries under a new `## [x.y.z] - YYYY-MM-DD`
   heading.
4. **Update the root `README.md` install snippet** if the shown version string is pinned literally
   rather than left as a placeholder.
5. **Commit and push** the version bump on a task branch, per `CLAUDE.md` § Branches and commits.
   Do not tag or release from this commit yet — get it reviewed and merged to `main` first.
6. **Create a GitHub Release** against the merged commit on `main`, tagged `x.y.z` (no `v` prefix,
   matching `kmptoolkit.version`). This is a manual, human action — the assistant does not create
   GitHub releases (see `CLAUDE.md` § Publishing).
7. **The `publish.yml` workflow fires automatically** on the release. It runs `checkKotlinAbi`, the test
   suite, then `publishAndReleaseToMavenCentral` for every module.
8. **Monitor the deployment** in the [Central Portal](https://central.sonatype.com/publishing) —
   `automaticRelease = true` means a successful build publishes without a manual "release" click,
   but validation failures (missing POM fields, unsigned artifacts) still show up there first.
9. **Verify the release resolves** from a clean project:
   ```kotlin
   dependencies {
       implementation(platform("io.github.jamal-wia:kmptoolkit-bom:x.y.z"))
       implementation("io.github.jamal-wia:kmptoolkit-coroutines")
   }
   ```

## Manual publish (fallback)

If the CI workflow is unavailable, publish locally from a machine with the one-time setup above:

```bash
./gradlew checkKotlinAbi testDebugUnitTest
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

`--no-configuration-cache` matches the CI workflow — the vanniktech publishing tasks are not
configuration-cache compatible as of plugin version `0.37.0`.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Central Portal deployment stuck in `VALIDATING` | A POM field is missing or malformed — check `kmptoolkitPublish.pomName` / `pomDescription` were set on every published module |
| `signAllPublications()` did not run, artifacts unsigned | None of `signing.keyId`, `signingInMemoryKey`, or `ORG_GRADLE_PROJECT_signingInMemoryKey` was visible to the build — see `PublishConventionPlugin.kt`'s conditional |
| `checkKotlinAbi` fails right before a release | A module's public API changed without running `./gradlew updateKotlinAbi` and committing the updated `api/` dump — this is the ABI-validation gate doing its job, not a false positive |
| A module resolves with the wrong version from a consumer | `kmptoolkit.version` was bumped but the `kmptoolkit-bom` constraint wasn't regenerated — the BOM reads the same property, so re-running the build should be enough; if not, check the BOM module's `constraints { }` block by hand |
