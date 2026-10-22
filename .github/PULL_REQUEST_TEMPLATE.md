<!--
The PR title becomes the commit message on main and decides the next release version:
  feat: …      -> minor release        fix: … / perf: … / security: … -> patch release
  feat!: … or a "BREAKING CHANGE:" footer -> major release
  docs: / chore: / ci: / test: / build: / style: -> no release
-->

## What and why


## Checklist
- [ ] PR title follows `type(scope): description` (see above)
- [ ] `./gradlew :app:recordRoborazziDebug :app:lintDebug` passes locally (CI runs the same)
- [ ] New behaviour has tests (the sync/merge logic and parsers in particular)
- [ ] User-visible text is in `strings.xml`
- [ ] No logging of URLs, credentials or task content; no new permissions or cleartext traffic
- [ ] UI changes: screenshots attached (CI also publishes them as an artifact)
- [ ] I agree my contribution is licensed under GPL-3.0-or-later
