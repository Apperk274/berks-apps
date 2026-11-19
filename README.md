# Berk's Apps

A small Android app store for Berk's own Android Auto apps. It installs an app the
one way Android Auto accepts on a real car: so that Android records it as installed
from Google Play. It needs no computer, no Shizuku and no second helper app, and on
phones where the simple way works it needs no setup at all.

The first app in the list is **Auto Caster** (`dev.personal.autocaster`).

## Why this exists

Android Auto (seen on versions 17 and 18) hides apps built with the Car App Library
unless Android's install record for the app says both:

- `installerPackageName = com.android.vending` (Google Play), and
- `initiatingPackageName = com.google.android.packageinstaller` (the system installer).

A normal sideload does not produce that record. `adb install -i com.android.vending`
does not either, because the initiator stays `com.android.shell`. The record can only
be set while the app is being installed, so an already-installed app has to be
installed again the right way.

[UnknownAuto](https://github.com/GitMag/UnknownAuto) solved this with Shizuku.
Berk's Apps uses the same install mechanism in one app.

## Installing an app

1. Open Berk's Apps. On the **Apps** tab, tap **Install from file** and pick the APK
   (for Auto Caster, its `app-debug.apk`). Apps that have a published download show
   **Download and install** instead.
2. Berk's Apps checks the file: it must be a real APK, its package must match, and its
   signing certificate must match the one in the app list. Downloads must also match
   the SHA-256 in the list. On any mismatch it refuses.
3. Tap **Install for Android Auto**.
4. The first time only, Android asks whether Berk's Apps may install apps. Switch on
   **Allow from this source** and go back; the install continues by itself.
5. Android's own installer opens. Tap **Install** or **Update**. If Play Protect offers
   to scan, choose *More details → Install without scanning*.
6. Berk's Apps shows **Accepted by Android Auto** or **Not accepted by Android Auto**,
   with the two values it read back.

If an app was sideloaded before (for example with `adb install`), just install it
again from here; the same version is fine and its data is kept.

Each app in the list shows whether it is installed, whether an update is available,
and whether its current install record is one Android Auto accepts.

### If it says "Not accepted by Android Auto"

Phones differ in which way of starting the installer keeps the Google Play mark. If the
simple way did not keep it, the result card offers **Open advanced setup**. That is a
one-time pairing with the phone's own *Wireless debugging* (below). After it, tap
**Install for Android Auto** again: the app starts the installer the second way, and
remembers that this phone needs it.

## Advanced setup (only if needed)

Open the **Advanced** tab and follow the five steps:

1. **Developer options.** Settings → About phone → tap *Build number* seven times
   (Samsung: About phone → Software information → Build number).
2. **Wireless debugging.** Connect to Wi-Fi (any network; the internet is not used
   for this). In Developer options, switch on *Wireless debugging* and allow it for
   the network.
3. **Permissions.** Allow notifications (you type the pairing code into a
   notification) and, on Android 17, access to nearby devices on the local network
   (this lets the app find Wireless debugging on the same phone).
4. **Pair.** Tap *Start pairing*. In Wireless debugging, tap *Pair device with pairing
   code*. A six-digit code appears; **leave that dialog open**. Pull down the
   notification shade, tap *Enter pairing code* on the Berk's Apps notification, type
   the six digits and send. You are pairing the phone with itself; no other device is
   involved.
5. **Connect.** The app finds Wireless debugging and connects by itself.

If the notification never says *Pairing dialog found*, use *Type it here instead*:
put Settings and Berk's Apps in split screen so the dialog stays open, and type the
code plus the port shown in the dialog (the number after the colon). You can also
reply to the notification with both, for example `123456 37123`.

**After a restart** Android switches Wireless debugging off. The pairing is kept: turn
Wireless debugging on again (step 2) and tap **Connect**. This is only needed on phones
that need the advanced way, and only while installing or updating. Installed apps keep
working in the car without it.

## How the install works

Berk's Apps copies the APK into its private cache and offers it to the system
installer through a private content provider (read access is granted only to the
system installer packages). Then the system installer is started with a request that
names Google Play as the installer. Because the system installer performs the install,
it becomes the initiator. That is the record Android Auto wants.

There are two ways to start the installer, and Android builds differ in which one they
honour:

- **Simple way (default).** Berk's Apps starts the installer itself
  (`ACTION_INSTALL_PACKAGE` with `EXTRA_INSTALLER_PACKAGE_NAME = com.android.vending`,
  `EXTRA_NOT_UNKNOWN_SOURCE` and the Play referrer). On the Android 17 emulator this
  gave the accepted record for a fresh install and for updates, with no setup.
- **Advanced way.** Over its Wireless debugging connection, Berk's Apps runs one command
  as the `shell` user:

  ```
  am start -n <system installer activity> -a android.intent.action.INSTALL_PACKAGE \
    -d content://dev.personal.berksapps.apk/install.apk \
    -t application/vnd.android.package-archive \
    --es android.intent.extra.INSTALLER_PACKAGE_NAME com.android.vending \
    --ez android.intent.extra.NOT_UNKNOWN_SOURCE true \
    --ei android.intent.extra.INSTALL_REASON 1 \
    --es android.intent.extra.REFERRER_NAME android-app://com.android.vending
  ```

  This is UnknownAuto's main path. On Android 11–14 the shell instead starts the
  installer for a result that goes to Berk's Apps (a small helper run with
  `app_process`); that variant has not been tested on a device.

If an install goes through without the mark and the other way is available, Berk's
Apps opens the installer once more that way (one more tap on **Update**) and remembers
which way worked on this phone.

## Limitations

- **It depends on undocumented behaviour.** Neither Android Auto's check nor the
  installer's handling of these extras is a public contract. A future Android or
  Android Auto update can break it, and which way works differs between phones.
- Tested on the Android 17 emulator only, not yet on a real phone or in a car.
- On phones that need the advanced way: **Wireless debugging must be turned on again
  after every restart** before installing or updating, and it needs Wi-Fi.
- Single APKs only. Split APKs / XAPK are not supported (the system installer screen
  takes one file).
- Only the primary user profile is supported.
- **Downloads do not work yet.** The app list is fetched from a URL in
  `CatalogRepository.CATALOG_URL`; the owner's repositories are private, so until a
  list and an APK are published somewhere public the app uses its built-in list and
  *Install from file*.
- Samsung's *Auto Blocker* (Settings → Security and privacy) may block installs from
  unknown sources. If the installer refuses, try turning it off. (Not verified.)
- A third-party installer app set as the default for APKs (InstallerX and similar)
  cannot produce the Play mark; Berk's Apps goes around it to the system installer.

## Security notes

- The simple way uses only Android's normal "install unknown apps" permission. The user
  confirms every install in Android's own installer.
- The advanced way uses Wireless debugging, which is the same access a computer gets
  over `adb`. Berk's Apps uses it for a **fixed set of five commands**, all assembled in
  `adb/ShellCommand.kt`: `id -u`, `dumpsys package <pkg>`, the `am start` installer
  launch, the Android ≤14 `app_process` launch, and a filtered `logcat -d`. Every
  argument is validated against a strict pattern and single-quoted. Nothing from the
  network or the app list is ever run.
- The ADB private key is created on the phone, stored in the app's private storage and
  encrypted with a key held in the Android Keystore. Backups are disabled.
- Network access is HTTPS only (cleartext is disabled, redirects are followed only to
  HTTPS), and is used only for the app list and APK downloads.
- Exported components: the launcher activity, and one content provider that hands the
  activity's window token to the `shell` user for the Android ≤14 advanced path. It
  refuses every caller whose UID is not the shell.
- The ADB connection goes to `127.0.0.1` only. While Wireless debugging is on, anything
  else that has been paired with the phone can connect too; turn it off when you are
  done if that worries you.
- `QUERY_ALL_PACKAGES` is used to see whether list apps (and whatever you pick from a
  file) are installed and how; `REQUEST_INSTALL_PACKAGES` to open the system installer.
- libadb-android states that it has not had a security audit.

## Building

```
mise trust
mise install
mise exec -- ./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
```

Needs the Android SDK (platform `android-37.0`, build-tools `36.0.0`) and a
`local.properties` with `sdk.dir`. The APK lands in `app/build/outputs/apk/debug/`.

`DECISIONS.md` records the choices made while building this and how to change them.

## Credits and licences

- The install mechanism follows **UnknownAuto** by GitMag (MIT).
- ADB protocol and pairing: **libadb-android** by Muntashir Al-Islam (used under
  Apache-2.0), with **spake2-android** (LGPL-3.0), **Bouncy Castle** (MIT-style) and
  **Conscrypt** (Apache-2.0).
- The pairing-by-notification idea comes from **Shizuku** and **LADB**.

Details and licence texts are in [NOTICE.md](NOTICE.md). Berk's Apps is not affiliated
with Google, Android Auto, UnknownAuto or Shizuku.
