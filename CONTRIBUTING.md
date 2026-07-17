# Contributing to FSLib

Thank you for your interest in contributing to FSLib!

## Getting Started

1. Fork the repository
2. Clone your fork locally
3. Create a feature branch from `master`
4. Make your changes

## Development Setup

- **JDK 25** — required since 6.0.0. KVision 9.6.0's Gradle plugin fails at configuration on an older
  JDK, and its runtime artifacts are Java 25 bytecode.
- **MongoDB** (if working on the `:mongodb` module)
- **Docker** (optional — the Mongo conformance tests use Testcontainers; without it they skip locally
  and run in CI)

```bash
./gradlew build          # Build all modules
./gradlew :core:allTests # Run core tests
./gradlew :ssr:test      # Run SSR tests
```

The Gradle **daemon** itself must run on JDK 25. If that isn't your default, pass it per invocation
rather than committing a machine-specific path:

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk-25 build

# macOS/Homebrew:
./gradlew -Dorg.gradle.java.home="$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home" build
```

After changing Kotlin or KVision versions, the Kotlin/JS dependencies shift and `:kotlinStoreYarnLock`
fails; run `./gradlew kotlinUpgradeYarnLock` to actualize the lock file.

## Guidelines

- Add KDoc comments to all public APIs
- Use `fieldName(Model::property)` for Tabulator column fields
- Write code comments and user-facing strings in English
- Follow existing patterns in the codebase

## Pull Requests

1. Ensure `./gradlew build` passes
2. Keep PRs focused on a single change
3. Write a clear description of what and why

## Releasing

Releases go to Maven Central through the Central Portal. **A release is artifacts + notes + migration
notes** — all three land in the version-bump commit, *before* publishing. Two constraints make this
non-negotiable rather than tidy: Maven Central versions are **immutable**, and `.md` files are **not**
part of the published artifacts. Once a version is out, its documentation can only ever be fixed here.

1. **Pick the version from the commits, not from intent.** Conventional Commits + SemVer: any `!` or
   `BREAKING CHANGE:` since the last tag ⇒ **major**, however small the diff looks.
2. **One commit** carries: the `fsLib` bump in `gradle/libs.versions.toml`, the `CHANGELOG.md` entry,
   the `MIGRATION.md` section — or an explicit "no migration required" line — and a **repo-wide sweep**,
   not a fixed list of files. Enumerating "README + CHANGELOG" is how `USAGE-GUIDE.md` and `CLAUDE.md`
   sat two majors out of date while still teaching a removed API. Sweep both axes:
   ```bash
   grep -rn '[0-9]\+\.[0-9]\+\.[0-9]\+' --include='*.md' .   # stale coordinates/versions
   grep -rni 'automatic\|auto-create\|on first\|side effect' --include='*.md' .   # stale behavior claims
   ```
   Behavior claims are the dangerous half: a stale version number is obvious, while "the module registers
   its provider automatically" reads as current forever. Any doc that *teaches* a changed API — not just
   the ones that cite its version — is in scope, `*.kt` KDoc included.
3. **Full green build on JDK 25** (compile + tests).
4. **Clean the staging directory first:** `rm -rf build/staging-deploy build/central-bundle.zip`.
   Staging *appends*, and the upload zips the whole directory — a leftover artifact from an earlier
   release will be re-submitted and rejected.
5. `./gradlew -Dorg.gradle.java.home=<jdk25> publishAllPublicationsToStagingRepository`, then confirm
   only the new version is staged.
6. `./gradlew -Dorg.gradle.java.home=<jdk25> publishToCentralPortal` — `publishingType=AUTOMATIC`
   validates and releases, and is **irreversible**.
7. Tag `vX.Y.Z`, push it, and cut the GitHub Release from the CHANGELOG entry.

If a migration step is silent at compile time and only bites at runtime, say so in the CHANGELOG's
Migration Guide **and** in `MIGRATION.md`, with the symptom the user will actually see. Upgraders read
the migration guide to decide whether they need to do anything; an empty one means "nothing to do".

## Reporting Issues

Open an issue at https://github.com/tfonrouge/fsLib/issues with:
- Steps to reproduce
- Expected vs actual behavior
- FSLib version and Kotlin version

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
