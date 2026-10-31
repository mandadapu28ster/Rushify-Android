# Rushify Android

Native Android client (Kotlin, Jetpack Compose) for the Rushify portfolio platform. **minSdk 28 (Android 9)**, target/compile 36.
Implements `implementation_plan_v2.md`: server connect + login, dashboard, holdings, Pulse (radar, news, watchlists), sync hub, offline cache, periodic refresh + notifications,
plus the later phases: growth / benchmark / dividend charts (Home → Performance, or More), Monzo balances + sync, corporate actions (confirm / dismiss) and symbols to confirm, and an AI assistant that uses the user's own Gemini key (chat is memory-only).
Keys, CSV import, manual-holding edits and clearing data stay web-only by design.

## Build and run
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (`local.properties` has `sdk.dir`).

## Tests
```
./gradlew :app:testDebugUnitTest            # 373 JVM tests: mocks, parsing, errors, session, cache rules, VMs, charts maths, worker, web-parity logic
./gradlew :app:connectedDebugAndroidTest    # 136 device tests: real Room + Compose UI against an in-process MockWebServer
```
Failure cases (401, 403 locked/inactive, 429, 409, 5xx, timeouts, malformed JSON, empty portfolio) run only against mocks.
Fixtures in `app/src/test/resources/fixtures` are anonymised hand-built payloads, no real portfolio data.
Never send wrong passwords or trigger syncs against a real server: accounts lock for 24 h and syncs use a daily quota.

## Debug-only sign-in (no password needed)
Debug builds include `DebugLoginReceiver`, so a JWT can be injected over adb. It is not in release builds.
```
adb shell am broadcast -n com.rushify.mobile/.DebugLoginReceiver --es server http://HOST:PORT --es token <jwt>
```

## Security decisions
* JWT only, in Keystore-backed EncryptedSharedPreferences. The password is never stored.
* Cache is app-private Room, keyed by `server|userId`, wiped on logout and server change. `allowBackup=false` plus extraction rules.
* Cleartext is allowed (self-hosted IP:port) but gated: URL validation, one-time "not encrypted" confirmation, persistent warning chip, no HTTPS→HTTP redirects.
* Optional: hide balances, block screenshots (FLAG_SECURE), biometric lock.
* No credentials or server addresses are baked into the app.

## Releases (APK for testers)

Publishing a GitHub release builds a signed APK and attaches it to that release. Testers download it from the release page.

**One-time setup** (needs the GitHub CLI logged in as the repo owner):

```bash
gh auth login
scripts/set-github-secrets.sh
```

This copies the local signing key (`keystore.properties` and the `.jks` it points at, never committed) into repository secrets, so every release is signed with the same key and installs as an update over the previous one. Back the key up: losing it means testers must uninstall and reinstall.

**Cut a release:** GitHub → Releases → Draft a new release → new tag such as `v0.2.0` → Publish. Or:

```bash
gh release create v0.2.0 --generate-notes
```

The `Release APK` workflow runs the unit tests, builds `Rushify-0.2.0.apk` (the tag becomes the version name, the run number the version code), checks the signature and uploads the APK plus a `.sha256` file to the release. A manual run (Actions → Release APK → Run workflow) builds a signed APK and keeps it as a workflow artifact without publishing anything.

**Installing:** open the APK on the phone, allow installs from that app when asked, install, then enter your server address. Private repository: testers need read access to the repo to download release files. `CI` runs unit tests, lint and a debug build on every push and pull request.
