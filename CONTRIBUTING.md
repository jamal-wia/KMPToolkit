# Contributing

Thanks for considering a contribution to KMPToolkit. This file covers the mechanics; see
`CLAUDE.md` for the full set of repository rules (language policy, library invariants, API
compatibility, branching, testing, documentation).

## Before you start

- **Open an issue for anything beyond a small fix** — a new module, a public API change, a new
  dependency — so the design can be discussed before code is written. See `docs/01-architecture.md`
  for the principles a new module is expected to follow (no DI framework, no hardcoded consumer
  identifiers, no user-facing text).
- **Check the module boundary first.** Read the target module's `docs/<module>/01-overview.md`
  — specifically its "What this is not" section — before adding a feature. If what you want doesn't
  fit, it may belong in a new module instead of an existing one.

## Making a change

1. Branch from `develop` (never commit directly to `main` or `develop`).
2. Make the change. Every public symbol needs KDoc; every behavioral change needs a matching update
   to that module's `docs/<module>/` (see `CLAUDE.md` § Documentation is mandatory).
3. Add tests. See `CLAUDE.md` § Tests — cases are derived from requirements, not from what the
   implementation happens to do; boundary and platform-specific cases are not optional.
4. Run the checks locally before opening a pull request:
   ```bash
   ./gradlew build checkKotlinAbi
   ./gradlew testDebugUnitTest iosSimulatorArm64Test
   ```
5. If your change adds or changes public API, run `./gradlew updateKotlinAbi` and commit the
   updated `api/` dump alongside the code change — don't hand-edit the dump file.
6. Add an entry under `## [Unreleased]` in `CHANGELOG.md`.

## Code style

SOLID / KISS / YAGNI, composition over inheritance, immutability by default, explicit type
annotations wherever a declaration's type isn't otherwise visible — see `CLAUDE.md` § Code style
for the full list with examples.

## Language

Everything in the repository — code, comments, KDoc, commit messages, documentation — is in
English. See `CLAUDE.md` § Language policy.

## Reporting issues

Please include: the artifact and version, target platform (Android API level / iOS version),
minimal repro, and expected vs. actual behavior.
