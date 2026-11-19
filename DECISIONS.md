# Decisions

Each entry: what was decided, what else was considered, why, and how to change it.
Written while the owner was away; nothing here is hard to reverse.

## 1. ADB library: libadb-android 3.1.1 (used under Apache-2.0)

- **Decision:** `com.github.MuntashirAkon:libadb-android:3.1.1` from JitPack for the
  ADB protocol, the TLS upgrade and pairing.
- **Alternatives:** write SPAKE2 + TLS pairing by hand; bundle the `adb` binary as LADB
  does; depend on Shizuku (the thing this app replaces).
- **Why:** it is the maintained library that does exactly this, and pairing crypto is
  not something to hand-roll. Licence check: the library is dual-licensed
  GPL-3.0-or-later **or** Apache-2.0, and this project takes the Apache-2.0 option. Its
  transitive dependency `spake2-android` is **LGPL-3.0**: compatible for an app that
  uses it unmodified as a separate library, with the obligations written down in
  `NOTICE.md`. Bouncy Castle is MIT-style. The library says it has had no security
  audit.
- **Change it:** versions are in `gradle/libs.versions.toml`; the only code that touches
  the library is `adb/AdbRepository.kt`. JitPack is restricted to the
  `com.github.MuntashirAkon*` groups in `settings.gradle.kts`. If JitPack ever
  disappears, vendor the two AARs.

## 2. TLS provider: bundled Conscrypt 2.5.3

- **Decision:** ship `org.conscrypt:conscrypt-android:2.5.3` (Apache-2.0).
- **Alternatives:** the platform's Conscrypt through a hidden-API bypass library;
  Conscrypt 2.9.0 (newest).
- **Why:** pairing needs `exportKeyingMaterial`, which the platform only offers through
  hidden APIs. A hidden-API bypass is fragile on new Android versions. 2.5.3 is the
  version libadb-android documents and tests against, and it worked on the Android 17
  emulator. It costs about 2 MB per ABI.
- **Change it:** bump `conscrypt` in the version catalog. libadb finds it by reflection
  (`org.conscrypt.OpenSSLProvider`, `Conscrypt.exportKeyingMaterial`), so check those
  still exist.

## 3. ADB key: RSA-2048 in app-private storage, wrapped by the Android Keystore

- **Decision:** `adb/AdbKeys.kt` generates the key pair and a 20-year self-signed
  certificate (Bouncy Castle `bcpkix`), stores the certificate in `files/adb/cert.der`
  and the private key in `files/adb/key.bin`, encrypted with an AES-GCM key that lives
  in the Android Keystore. `allowBackup=false` and the backup rules exclude everything.
- **Alternatives:** plain PKCS#8 file (what most apps do); a non-exportable Keystore RSA
  key (ADB's legacy signature and the bundled Conscrypt both want the raw key).
- **Why:** cheap hardening against the key file being copied off the device.
- **Consequence:** if the Keystore key is lost, the stored key cannot be read; the app
  then makes a new key and asks to pair again.
- **Change it:** everything is inside `AdbKeys`.

## 4. Finding Wireless debugging: mDNS, connect to 127.0.0.1, manual port as fallback

- **Decision:** `adb/AdbDiscovery.kt` browses `_adb-tls-pairing._tcp` and
  `_adb-tls-connect._tcp` with `NsdManager`, keeps only services whose address belongs
  to this phone, and the app then connects to `127.0.0.1:<port>`. The last working port
  is remembered and tried as well. Both screens accept a typed port.
- **Alternatives:** scan local ports; switch adbd to a fixed port with `adb tcpip 5555`
  after the first connection (Shizuku/LADB offer this).
- **Why:** mDNS is what `adb` itself uses. A fixed TCP port was rejected because it
  leaves unauthenticated-looking ADB listening until reboot, and it changes a phone
  setting.
- **Android 17:** apps targeting API 37 need the runtime permission
  `ACCESS_LOCAL_NETWORK` for mDNS. It is declared and requested in Setup step 3. On the
  API 37 emulator the system showed the "nearby devices" dialog and discovery worked
  after allowing it.
- **Change it:** timeouts are constants in `AdbRepository` and `AdbDiscovery`.

## 5. Pairing UI: foreground service with an inline-reply notification

- **Decision:** `adb/PairingService.kt` is a foreground service (type `specialUse`) that
  browses for the pairing service and shows a notification with an "Enter pairing code"
  reply field. The reply may be `123456` or `123456 37123` (code and port). Setup also
  has in-app fields for split-screen use.
- **Alternatives:** foreground-service type `connectedDevice` (needs an extra
  network/Bluetooth permission to qualify); no service, only a broadcast receiver (the
  process may be killed while Settings is in front, and receivers get about 10 s).
- **Why:** the pairing dialog closes when Settings loses focus, so the code has to be
  typed somewhere that does not cover Settings. `specialUse` needs no extra permission;
  its only cost is a Play Store review question, and this app is not on Play.
- **Change it:** the type is in `AndroidManifest.xml` and `PairingService.foreground()`.
  The service stops after 10 minutes (`SESSION_TIMEOUT_MS`). Tapping "Start pairing"
  again while it runs restarts the search and the timer.

## 6. Install flow: the simple "app path" first, Wireless debugging only as a fallback

- **Decision:** installing needs no setup. Berk's Apps itself opens Android's installer
  (`startActivityForResult`, `ACTION_INSTALL_PACKAGE`, with the extras that name Google
  Play as installer), checks the result with `PackageManager`, and only if the record is
  not accepted does it point the user at the **Advanced** tab (pairing with Wireless
  debugging), which unlocks the second way of starting the installer. The app remembers
  per phone which way produced an accepted record (`preferShell` preference) and starts
  with that one next time.
- **Why (experiment, API 37 Google Play emulator, x86_64, 2026-11-20):** with Developer
  options off, Wireless debugging off and Berk's Apps never paired, the app path alone
  gave installer = `com.android.vending`, initiator =
  `com.google.android.packageinstaller` in every case tried:
  1. fresh install (target not installed);
  2. update over an `adb install` sideload (was installer = null, initiator =
     `com.android.shell`);
  3. update over an earlier app-path install;
  4. a clean install of Berk's Apps itself followed by a fresh install of the target.
  No shell launch happened in that boot, so the result does not depend on one. The
  shell launch (`am start` as shell) on the same image gave installer =
  `com.google.android.packageinstaller`, which Android Auto rejects. In every case the
  values the app read with `PackageManager.getInstallSourceInfo` were the same as
  `adb shell dumpsys package` read from the host.
- **Not known:** whether Samsung's Android 17, or any Android 11–16 phone, behaves like
  this emulator. UnknownAuto's source says "current Android keeps the mark only when the
  shell starts the installer; some Android 17 builds have that check inverted", and
  aa-torque issue 126 reports the installer dropping the extra for an ordinary app. So
  the shell path is kept as the fallback rather than removed. No other system image is
  installed on this PC, so only API 37 was tried.
- **The three launch paths** (all in `install/PlayInstaller.kt`, logic from UnknownAuto's
  `InstallActivity.java` and `ShellService.java`):
  1. **App path** (default): as above. Needs the "Install unknown apps" permission; the
     app opens that settings screen once and continues by itself when the user returns.
  2. **Shell path, Android 15+:** `am start` of the system installer activity with the
     Play extras, run as the shell over the app's own ADB connection.
  3. **Shell path, Android 11–14:** the shell starts the installer for a result
     delivered to this app's activity, by calling the hidden
     `IActivityTaskManager.startActivity` from an `app_process` program
     (`shell/ResultLauncher.kt`) that gets the activity token from
     `shell/TokenProvider.kt`.
  If an install goes through without the mark, the other path is tried once
  automatically when it is available (the shell path only when already paired), and the
  preference flips. If the shell cannot start the installer, the app path is used.
- **Alternatives:** shell path first (the first version of this app; it forced every
  user through Developer options for nothing on the emulator); `pm install -i
  com.android.vending` (initiator stays the shell); a `PackageInstaller` session owned
  by this app (cannot name Play as installer).
- **Not tested at all:** the Android 11–14 shell path. On the API 37 emulator the helper
  program was started by hand without an activity token on offer; it ran without an
  exception and printed "no activity token". That proves only that the class loads and
  runs under `app_process`. It also assumes user 0.
- **Change it:** the first path is the default of `KEY_PREFER_SHELL` in `PlayInstaller`;
  the command lines are in `adb/ShellCommand.kt`. To drop Android 11–14 shell support,
  delete the `shell` package and the `TokenProvider` manifest entry. To drop the shell
  fallback entirely, delete the `adb` and `shell` packages, the Advanced tab and the
  libadb, Conscrypt and Bouncy Castle dependencies; the APK shrinks from about 42 MB to
  a few MB.

## 7. Acceptance check, where the record is read, and when

- **Decision:** "Accepted" uses UnknownAuto's rule: installer is `com.android.vending`,
  initiator is Play or a `*packageinstaller*` package and never `com.android.shell`.
  The record is read with `PackageManager.getInstallSourceInfo`. When the Advanced
  connection is up, `dumpsys package <pkg>` is read over ADB instead and parsed from the
  `Package [<pkg>]` block (`install/InstallRecord.kt`); the result card says which
  source it used. The per-app status in the list always uses `PackageManager`.
- **Order of the verdict** (`InstallVerdict.decide`, unit-tested): not installed →
  "Not installed"; install time unchanged → "Nothing was installed", even if the
  version already on the phone is accepted; otherwise accepted or not accepted.
- **When it runs:** when the app comes back to the front after the installer, and also
  from a once-a-second check of the package's update time while waiting (so it works in
  split screen, where the app is never paused). After a cancel the app waits 3 more
  seconds for a change before saying nothing was installed.
- **Unfinished installs:** the pending install is saved. If the app process was killed
  meanwhile, the next start checks the result once and never reopens the installer by
  itself; a pending install older than 15 minutes is dropped silently.
- **Caveat:** the rule is a reconstruction of Android Auto's behaviour, not something
  Google documents.

## 8. Package visibility: QUERY_ALL_PACKAGES

- **Decision:** declare `QUERY_ALL_PACKAGES` (lint check suppressed in the manifest).
- **Alternatives:** a `<queries>` list (cannot cover an arbitrary APK picked from a
  file, nor resolving the phone maker's installer); asking the shell for everything.
- **Why:** an installer has to see whether the target is installed, its version and
  signer, and which system app handles installs. Not a Play Store app, so the policy
  restriction does not apply.
- **Change it:** replace with `<queries>` if the app is ever limited to catalog packages.

## 9. Catalog format, source and trust

- **Format** (`assets/catalog.json`, schema version 1): `name`, `package`,
  `description`, `version`, optional `versionCode`, `apkUrl`, `apkSha256`,
  `signingCertSha256`. `apkUrl` and `apkSha256` go together and may both be null (then
  only "Install from file" is offered). `signingCertSha256` is required.
- **Source:** the same file serves two purposes. It is bundled into the APK as the
  fallback list, and the copy on the `main` branch of the public repository
  `Apperk274/berks-apps` is the online list
  (`CatalogRepository.CATALOG_URL`, a `raw.githubusercontent.com` address). The app
  fetches it at start and on **Refresh**, keeps the last good copy, and falls back to
  the bundled one. Whoever can push to that branch controls which apps are offered, so
  protect the GitHub account.
- **Where APKs live:** GitHub releases of that same repository, for both apps, although
  Auto Caster's source is in a private repository. Alternatives were a separate
  "releases" repository or a release per app repository; one public place is simplest.
- **Trust rules:**
  - HTTPS only, and only to `github.com`, `raw.githubusercontent.com`,
    `objects.githubusercontent.com` and `release-assets.githubusercontent.com`
    (`catalog/DownloadPolicy.kt`). Redirects are followed by hand (parsed leniently, then checked), at most five, and
    each hop must be on that list. A catalog whose `apkUrl` is anywhere else is rejected
    as a whole. If GitHub starts serving release files from another host, add it to
    `DownloadPolicy.GITHUB_HOSTS` and ship an update.
  - A downloaded APK must match the catalog's package, SHA-256 and signing certificate.
    A partial or cancelled download is deleted; a size that differs from the announced
    length is an error.
  - A file-picked APK whose package is in the catalog must match the catalog's signing
    certificate. Its SHA-256 is not checked (a locally built APK differs). A file-picked
    APK that is not in the catalog is allowed, with a warning.
  - An online entry for a package that is also in the bundled list is ignored if its
    signing certificate differs from the bundled one. So the online list cannot swap the
    key of a known app; rotating a key needs an app update. New apps added only online
    are trusted on the strength of the online list alone.
- **Pinned certificate:** both entries pin the release key,
  `97ce724a7bee251fd62c2c745c2907d8221cf0ba4178142b49485203487207b6`. The earlier pin
  (a debug key) is gone, so debug-signed APKs of these two packages are refused.
- **Version comparison:** `versionCode` when both sides have it, otherwise dotted
  version names (`core/Versions.kt`). The card shows **Install**, **Update to x**,
  **Reinstall to fix** (installed but not accepted by Android Auto) or a small
  **Reinstall**.
- **Debug-only override:** a debug build reads an optional first line from
  `files/catalog-override.txt` in its private storage and uses it as the catalog
  address, also allowing that one host (over plain HTTP if the address says so). It
  exists to test downloads against a local server. The code is in
  `app/src/debug/.../BuildOverrides.kt`; the release source set has a version that
  returns nothing, and cleartext is only enabled in the debug manifest, so released
  APKs cannot be redirected.

## 10. Shell command safety

- **Decision:** `ShellCommand` has a private constructor; the only way to get one is
  through five factory functions. Each validates its arguments against a strict pattern
  and refuses anything outside `[A-Za-z0-9_./:=~+,@%$ *-]`, then single-quotes every
  token. `AdbRepository.run()` only accepts a `ShellCommand`.
- **Why:** libadb's `shell:` service takes one string that `sh` parses. The package name
  used in commands comes from the parsed APK or the validated catalog, the installer
  component from `PackageManager`.
- **Exit status:** the legacy `shell:` service returns none, so every command ends with
  `; echo __BERKSAPPS_EXIT:$?`. Reading stops at that marker, which also avoids a
  blocking read in the library after the stream closes.

## 11. Exported components

- **Decision:** exported are `MainActivity` (launcher) and `TokenProvider`.
  `TokenProvider` exists only for the Android 11–14 path: the shell-side helper must
  obtain this app's activity token through binder. It is protected by
  `INTERACT_ACROSS_USERS_FULL` (which normal apps cannot hold) and its `call()` rejects
  every caller whose UID is not the shell. The token is handed out once and expires
  after 30 seconds. `ApkProvider` (serves the staged APK) is not exported; read access
  is granted per-URI to the system installer packages only.
- **Change it:** removing the Android 11–14 path (see 6) removes the exported provider.

## 12. UI toolkit and build setup

- **Decision:** Jetpack Compose with Material 3, one activity, two tabs (Apps, Advanced).
  Strings are in the Kotlin sources, English only.
- **Toolchain:** copied from `android-auto-caster`: `mise.toml` (Temurin 17, Gradle
  9.9.1), AGP 9.5.1 with its built-in Kotlin (2.2.10), compileSdk/targetSdk 37.
- **Compose BOM 2026.09.00, not the newest:** BOM 2026.11.01 (Compose 1.13) requires
  compileSdk 37.1, and only platform `android-37.0` is installed. Bump the BOM when a
  newer platform is installed.
- **minSdk 30:** Wireless debugging exists from Android 11.
- **ABIs:** `arm64-v8a` and `x86_64` only (phone and emulator), to keep the APK small.
  Add `armeabi-v7a` in `app/build.gradle.kts` for 32-bit phones.
- **JSON:** the platform's `org.json`; unit tests use the `org.json:json` artifact.

## 13. Things deliberately left out of v1

- Split APKs / XAPK and root installs (UnknownAuto has them; the owner's app is a single
  APK).
- UnknownAuto's hint text about third-party installer apps; the app still routes around
  them by naming the system installer.
- Auto-connect in the background, a "forget pairing" button, resuming a broken download.
- Minification (see 14).

## 14. Release signing key shared by both apps

- **Decision:** one RSA-4096 key (alias `berk`, valid until 2059) in
  `%USERPROFILE%\.android\berk-release.jks`, outside every repository. Its path, alias
  and passwords are Gradle properties in the user-level
  `%USERPROFILE%\.gradle\gradle.properties` (`BERK_RELEASE_STORE_FILE`,
  `BERK_RELEASE_STORE_PASSWORD`, `BERK_RELEASE_KEY_ALIAS`, `BERK_RELEASE_KEY_PASSWORD`).
  Both apps' `app/build.gradle.kts` define a `release` signing config from those
  properties and stop `assembleRelease` with a clear message when they are missing.
  Debug builds do not need them.
- **Alternatives:** a key per app (two things to lose, and the catalog pin logic gains
  nothing); the key in the repository, encrypted (one leaked password away from
  disaster); Play App Signing (the apps are not on Play).
- **Why one key:** both apps belong to one person and are published from one place.
- **Risk to manage:** the keystore and its password exist only on this PC. **Back both
  up.** Losing them means no installed copy can ever be updated again. Leaking them
  means someone else can sign updates the app would accept, so treat a leak as "publish
  a new app under a new key".
- **Certificate SHA-256:**
  `97ce724a7bee251fd62c2c745c2907d8221cf0ba4178142b49485203487207b6`.
- **Minification:** left off. Reflection is used on purpose (libadb finds Conscrypt by
  name, the `app_process` helper is started by class name), so turning R8 on needs keep
  rules and a device test. The release APK is about 35 MB because of that.

## 15. Release process and naming

- **Decision:** `tools/release.ps1` (Windows PowerShell 5.1) builds, checks and stages
  a release, and publishes only with `-Publish`. Tags are `autocaster-v<versionName>`
  and `berksapps-v<versionName>`; files are `autocaster-<versionName>.apk` and
  `berksapps-<versionName>.apk`; download addresses are
  `https://github.com/Apperk274/berks-apps/releases/download/<tag>/<file>`.
- **What the script guarantees:** the APK's package is the expected one, its version is
  the one in `app/build.gradle.kts`, it has exactly one signer, and that signer is the
  certificate already pinned in the catalog.
- **Versions:** `appVersionCode` and `appVersionName` at the top of each
  `app/build.gradle.kts`. Both apps start public life at 0.1.0 (1).
- **Two catalog states, on purpose.** The catalog on `main` is live for every installed
  copy, so it may only advertise files that exist. The script therefore never edits the
  working-tree catalog (apart from a temporary swap during the Berk's Apps build, undone
  in a `finally`). It writes the catalog-to-be to `dist/catalog.json`; that file replaces
  the tracked one only after the releases are created.
- **Order when publishing:** push the source, create the GitHub releases, then copy
  `dist/catalog.json` over the tracked catalog, commit and push. The script's hint,
  `-Publish`, and the README all use this order. `-Publish` refuses to run with any
  uncommitted change.
- **Already released versions are skipped:** when the catalog lists an app's current
  version code with a download and `gh release view <tag>` finds the tag, the app is not
  rebuilt, so the hash of a published file can never be overwritten. If the tag exists
  but the catalog does not list it, `-Publish` stops instead of guessing. Without `gh`
  the check cannot be made and the script says so.
- **Self entry:** an APK cannot contain its own hash. Berk's Apps is built with a
  catalog holding the newest entry of every other app and an empty download for itself.
  So releasing Auto Caster alone never needs a new Berk's Apps (installed copies read
  the online list), and the list inside a Berk's Apps APK is only a fallback.
- **First publish:** the tracked catalog has both entries with empty downloads. That is
  what goes up with the first push; the full catalog follows after the two releases.
- **Alternatives:** GitHub Actions (would need the signing key as a repository secret,
  and Auto Caster's source is private); a Gradle task (awkward across two repositories).

## 16. Signature changes and self-update

- **Old debug-signed copies:** Android refuses to update across signing keys. The list
  compares the installed app's signer with the catalog pin and, on a mismatch, replaces
  the install buttons with an explanation and **Uninstall old version** (the system
  uninstall dialog; needs `REQUEST_DELETE_PACKAGES`). The same check runs again just
  before the installer is opened, for files picked by hand.
- **Berk's Apps itself** is a catalog entry like any other, without the Android Auto
  line and without "Install from file". An old debug-signed copy cannot replace itself,
  so its card links to the releases page and tells the user to download first, then
  uninstall.
- **Being killed by its own update:** Android stops the app while replacing it. The
  pending install is on disk, so the next start checks the result once and shows
  "Berk's Apps was updated". For its own package the Android Auto rule is not applied
  and the remembered install path is not changed.
- **Tested** on the API 37 emulator with a build signed with the release key and a
  local test catalog offering 0.1.1: download with progress, install, relaunch, result
  card, version 0.1.1 installed.
