# Tasks for Nextcloud (Android)

[![CI](https://github.com/javsanpae/tasks-for-nextcloud/actions/workflows/ci.yml/badge.svg)](https://github.com/javsanpae/tasks-for-nextcloud/actions/workflows/ci.yml)

An open-source, iOS-inspired Android client for [Nextcloud Tasks](https://apps.nextcloud.com/apps/tasks).
Nextcloud Tasks stores tasks as CalDAV `VTODO` items, so this app speaks standard CalDAV (RFC 4791) and
iCalendar (RFC 5545) — no proprietary API, and tasks stay interoperable with the Tasks web app, DAVx⁵/OpenTasks,
Thunderbird, iOS Reminders, etc.

## Features

- Sign in with your server address using **Nextcloud Login Flow v2** (browser-based; the app never sees your
  password, 2FA or SSO). Fallback: paste an app password.
- Pick which **task list** (CalDAV calendar with VTODO support) you are working in; read-only shared lists are respected.
- **Create, edit, complete and delete** tasks (swipe to delete, with undo).
- **Recurring tasks** like the web app: daily / weekly (choose weekdays) / monthly (date, nth weekday, last day) /
  yearly, every *N* units, ending never / after *N* times / on a date. Completing a repeating task moves it to its
  next occurrence; the last occurrence completes it.
- **Alerts** like a calendar app: pick any of several presets (at the time, 5/10/15/30 min, 1/2 h, 1/2 days, 1 week
  before; for all-day tasks "on the day / N days before at 9:00") or build a custom one, up to 5 per task. When one is
  due you get an Android notification with the task title and when it is due, and a **Complete** button that finishes the
  task right from the notification shade (a repeating task moves on to its next date). Alerts are stored as standard
  `VALARM`s, so they sync and also show up in the Nextcloud Tasks web app; alerts set there are shown and fire here too.
- Due date and time, notes, priority; due-today / overdue highlighting; light and dark mode.
- **Works offline**: the last-synced lists and tasks are cached (encrypted) and open instantly; changes made offline
  are queued and pushed when the connection returns (also on app start, pull-to-refresh and network-regained). The queue
  is coalesced (create+edit → one create, create+delete → nothing) and conflicts with edits made elsewhere are merged
  field by field. A small "Offline · 2 changes waiting" status shows what is pending.
- Edits are applied *on top of* the server's data, so properties this app doesn't know (alarms, attachments,
  sub-task links, `X-` properties, other clients' fields) are preserved byte for byte.

## Security & privacy

See [SECURITY.md](SECURITY.md). In short: HTTPS only, no user-installed CAs, no redirects with credentials,
credentials encrypted with a hardware-backed Android Keystore key, no backups, no analytics/telemetry/logging,
XXE-safe XML parsing, bounded parsers, and an app password you can revoke any time (the app revokes it on sign-out).

## Building

Requirements: JDK 17, Android SDK 36.

```bash
./gradlew :app:assembleDebug        # debug APK  -> app/build/outputs/apk/debug
./gradlew :app:testDebugUnitTest    # unit + UI render tests
./gradlew :app:lintDebug
./gradlew :app:assembleRelease      # R8-minified, unsigned; sign with your own key
```

`./gradlew :app:recordRoborazziDebug` renders the real Compose screens to PNGs in `app/build/screenshots`
(Robolectric — no emulator needed).

### Testing against a real server

`LiveNextcloudTest` exercises the whole stack (create/edit/complete/recur/delete/undo) and is skipped unless
credentials are in the environment. Use an app password created for testing:

```bash
NC_URL=cloud.example.com NC_USER=alice NC_APP_PASSWORD=xxxx-xxxx \
  ./gradlew :app:testDebugUnitTest --tests '*LiveNextcloudTest*' -i
```

It only touches items titled `[nctasks-test] …` and removes them afterwards.

## Architecture

```
app/src/main/kotlin/app/nctasks
├── data/ical      RFC 5545 parser/writer (lossless tree) + TodoCodec (VTODO <-> Task, recurrence advance)
├── data/caldav    CalDavClient (PROPFIND/REPORT/PUT/DELETE, discovery, ETag concurrency), hardened XML
├── data/auth      Login Flow v2, URL normalisation, Keystore-backed CredentialStore
├── data/repo      OfflineFirstRepository (local-first + change queue + sync/merge), LocalStore (encrypted cache), RemoteTasks
├── domain         Task, TaskList, Recurrence (RRULE subset + next-occurrence calculator), Reminder + ReminderPlanner
├── reminders      ReminderEngine (one exact alarm for the next reminder), notifications, boot/clock receiver
├── background     WorkManager: hourly sync + flush after completing from a notification
└── ui             Compose screens (login, lists, tasks, editor, repeat), iOS design system, view models
```

No DI framework, no database, no serialization library: the dependency list is AndroidX/Compose, OkHttp and
coroutines. The offline cache is a single AES-GCM encrypted file (Android Keystore key); the UI observes one
`StateFlow<Snapshot>` from the repository and never waits for the network.

**Sync model.** Every edit is applied to the local copy instantly and appended to a persistent queue of
`Create` / `Update(base, new)` / `Delete` operations (coalesced per task). A background sync pushes the queue in order
with conditional requests, then pulls the server state; unsynced local edits win over the pulled copy until pushed.
A change made while an earlier push for the same task is in flight is queued behind it and re-based onto the server's
answer, so nothing is lost or overwritten.

## Development & releases

Repository: [`javsanpae/tasks-for-nextcloud`](https://github.com/javsanpae/tasks-for-nextcloud). See
[CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test and send pull requests.

### Pipelines (`.github/workflows`)
| Workflow | When | What |
|---|---|---|
| **CI** | every PR (also from forks, no secrets) and every push to `main` | runs all unit tests, renders the UI screenshots, lint, debug build **and** an R8 release build; uploads the debug APK, screenshots and a test summary so a PR can be tried and reviewed without building |
| **PR title** | PR opened/edited | enforces Conventional Commit titles (they decide the version) |
| **Release** | push to `main` | computes the next version from the commits since the last tag, builds and signs the APK, then creates the tag and GitHub release with the APK and its SHA-256 |

Versioning (`.github/scripts/release_version.py`, unit-tested): `feat` → minor, `fix`/`perf`/`security`/`refactor` → patch,
`!` or `BREAKING CHANGE:` → major (minor while 0.x), `docs`/`chore`/`ci`/`test`/`build`/`style` → no release,
`[skip release]` opts a commit out. The first release is `0.1.0`. `versionCode` is derived from the version
(`1.2.3` → `1002003`), so updates always install over older versions. Dependabot updates ship as patch releases.

### One-time setup (maintainer)
1. **Create a release key** and back it up safely (if you lose it, nobody can update in place):
   ```bash
   keytool -genkeypair -v -keystore nctasks-release.jks -alias nctasks -keyalg RSA -keysize 4096 -validity 10000
   ```
2. **Add four repository secrets** (Settings → Secrets and variables → Actions): `RELEASE_KEYSTORE_BASE64`
   (`base64 -w0 nctasks-release.jks`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
   Until they exist the Release workflow skips publishing with a warning instead of failing.
3. **Protect `main`** (Settings → Branches → rule for `main`): require a pull request, require the status checks
   `Build, test, lint`, `Release scripts` and `PR title`, and disallow force-pushes.
4. **Settings → General → Pull Requests**: allow squash merging only and set *Default commit message* to
   *Pull request title* (the title is what the release script reads). Under *Actions → General → Workflow permissions*
   make sure workflows may be granted `contents: write` (the Release job requests it explicitly).

Locally the same signing is enabled by exporting `NCTASKS_KEYSTORE`, `NCTASKS_KEYSTORE_PASSWORD`, `NCTASKS_KEY_ALIAS`
and `NCTASKS_KEY_PASSWORD` before `./gradlew :app:assembleRelease`; without them the release build is simply unsigned.
Verify a download with `sha256sum -c` and `apksigner verify --print-certs`.

## Limitations / roadmap

- Background sync runs about hourly while online (Android decides the exact moment), so a task created on another device gets its alert here only after the next sync; pulls download the full list each time (no sync-token/CTag optimisation yet).
- Alert times for all-day tasks default to 9:00 (custom times are possible per alert); there is no snooze button yet.
- Sub-tasks, tags, start dates and list creation/management are not editable yet (they are preserved).
- Recurrence rules outside the supported subset (e.g. `BYSETPOS`, hourly) are shown as "repeats" and kept untouched.
- Only English strings so far (everything is in `strings.xml`).
- Fonts use the system sans-serif rather than SF Pro (licensing).

## License

Copyright (C) 2026 Javier Sánchez

GNU General Public License v3.0 or later (`GPL-3.0-or-later`) — see [LICENSE](LICENSE).

You may use, modify, distribute and even sell this app commercially, **provided that anything you distribute that is
based on it is also released under the GPL with its source code available**. (The same licence the official
Nextcloud Android app uses.) Bundled libraries (AndroidX/Compose, OkHttp, Kotlin coroutines) are Apache-2.0
licensed, which is compatible with the GPL.

Contributions are accepted under the same licence. Not affiliated with or endorsed by Nextcloud GmbH.
