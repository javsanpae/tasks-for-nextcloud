# Security model

## What the app stores

| Data | Where | Protection |
|---|---|---|
| Server URL, login name, **app password** | app-private SharedPreferences | AES-256-GCM, key generated inside the Android Keystore (non-exportable, hardware-backed where available) |
| Last opened list URL | same | same |
| Offline cache: lists, tasks (titles, notes, dates), queue of unsynced changes | one file in `noBackupFilesDir` | AES-256-GCM with the same Keystore key; written via temp file + atomic rename; **deleted on sign-out** (and the key destroyed), and cleared before a different account signs in |

If the Keystore key is lost or the file is tampered with (GCM authentication fails) the cache is treated as empty and
re-downloaded. Unsynced changes in a cache that can no longer be decrypted are lost; this is the price of encrypting at rest.

No analytics, crash reporting, advertising IDs or logging of URLs/credentials/task content. `allowBackup=false`
and data-extraction rules exclude everything from cloud backup and device transfer.

## Authentication

- Primary: **Login Flow v2** in a Custom Tab (the user's real browser). The app only receives a revocable,
  app-specific password. The user's account password, 2FA codes and SSO session are never visible to the app.
- On sign-out the app calls `DELETE /ocs/v2.php/core/apppassword` so the credential is removed server-side, then
  deletes the local ciphertext and the Keystore key. If the server revokes the password, the next request's 401
  signs the user out.
- Fallback: manual app-password entry (password field is not autocorrected, masked, and cleared on failure).

## Network

- HTTPS only (`cleartextTrafficPermitted=false`; `http://` input is rejected; server-provided login/poll URLs
  must be https). System CAs only — user-installed CAs are not trusted.
- Authenticated requests **never follow redirects**; every URL derived from a server response (hrefs) must have
  the same origin as the configured server, otherwise the app refuses to send the credential.
- Conditional requests (`If-Match` / `If-None-Match`) prevent lost updates. Offline edits that meet a changed
  server copy are merged field by field (a field changed on this device wins, everything else keeps the server's
  value); a task deleted elsewhere is re-created rather than silently discarding the user's edit.

## Parsing hostile input

The server (or a malicious calendar item shared with the user) controls the data we parse.
- XML: documents containing a DOCTYPE/ENTITY are rejected (no XXE, no entity expansion); entity resolver disabled;
  nesting depth capped; responses capped at 16 MiB.
- iCalendar: input size and nesting capped; malformed items are skipped individually.
- RRULE evaluation is bounded (no unbounded loops on impossible rules).

## Hardening of the app itself

Minimal permissions (`INTERNET` only), a single exported activity (launcher), R8 minification/resource
shrinking in release, warnings-as-errors in the build, lint errors fail the build.

## Known gaps

- No certificate pinning (the server is user-chosen).
- Screenshots are not blocked (`FLAG_SECURE`) — tasks are content the user is meant to see.
- No app lock / biometric gate; anyone who can unlock the device and app can read the (encrypted-at-rest) cache.
- Pending changes are flushed only while the app process is alive (no background `WorkManager` job yet).

## Reporting

Please open a private security advisory on the project's repository.
