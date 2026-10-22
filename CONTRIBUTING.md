# Contributing

Thanks for helping! This project is GPL-3.0-or-later; by contributing you agree your work is released under it.

## Setup
JDK 17 and the Android SDK (API 36). Everything else is fetched by Gradle.

```bash
./gradlew :app:recordRoborazziDebug   # all unit tests + renders every screen to app/build/screenshots (no emulator)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug          # installable APK in app/build/outputs/apk/debug
python3 -m unittest discover -s .github/scripts   # release-version script
```

These are exactly what CI runs on every pull request. CI also uploads the **debug APK** and the **UI screenshots** as
artifacts, so reviewers can try your change or look at it without building.

## Pull requests
1. Fork, branch from `main`, keep the change focused.
2. Add or update tests. Sync/merge logic, parsing and recurrence have thorough unit tests; please keep that standard.
3. **Title your PR like a Conventional Commit** — it becomes the squash-commit message and decides the release:

   | Title | Release on merge |
   |---|---|
   | `feat: add subtasks` | minor (0.4.0 → 0.5.0) |
   | `fix(sync): keep edits made while offline` / `perf:` / `security:` / `refactor:` | patch |
   | `feat!: …` or a `BREAKING CHANGE:` footer | major (while 0.x: minor) |
   | `docs:` `chore:` `ci:` `test:` `build:` `style:` | none |

   Add `[skip release]` to a message to opt out. A "PR title" check enforces the format.
4. Wait for green checks, then a maintainer reviews. Merging to `main` publishes a signed APK automatically
   when the commits call for a release.

## Ground rules (security & privacy are features here)
- HTTPS only; never follow redirects with credentials; never trust a server-supplied URL's origin.
- No logging of URLs, credentials or task content; no analytics or other network destinations than the user's server.
- Treat all server data as hostile: keep parsers bounded, no DTDs/entities, no unbounded loops.
- Anything stored on disk must be encrypted (see `data/auth` and `data/repo/LocalStore.kt`).
- Preserve properties you don't understand when editing a task (other clients' data must survive).
- Keep the dependency list short; justify new ones in the PR description.
- Strings go in `res/values/strings.xml`; the build treats Kotlin warnings as errors.

Security problems: please use GitHub's private vulnerability reporting (Security tab), not a public issue.

## Releases (maintainers)
Releases are cut by `.github/workflows/release.yml` on every push to `main`; see "Releasing" in the README for the
one-time signing setup.
