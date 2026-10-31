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
