# Notices and licences

Berk's Apps is a personal project. It builds on the work below.

## Install mechanism: UnknownAuto

The way Berk's Apps gets Android to record an install as coming from Google Play
(installer `com.android.vending`, initiator `com.google.android.packageinstaller`)
was learned from, and follows, UnknownAuto by GitMag:
https://github.com/GitMag/UnknownAuto

Reimplemented in Kotlin from its Java source: the installer launch command and its
intent extras, the staged-APK content provider with URI grants to the system
installers, the Android 14-and-older "start for result as shell" launch, the
"try the other launch path once and remember which one works" retry, the
downgrade/signature pre-checks, and the acceptance rule. UnknownAuto does this through
Shizuku; Berk's Apps does it through its own Wireless debugging connection.

```
MIT License

Copyright (c) 2026 GitMag

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The idea of pairing an app with the phone's own Wireless debugging through a
notification reply comes from Shizuku (https://github.com/RikkaApps/Shizuku,
Apache-2.0) and LADB (https://github.com/tytydraco/LADB). No code from either is
included.

## Bundled libraries

| Library | Used for | Licence |
| --- | --- | --- |
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1, Muntashir Al-Islam | ADB protocol, TLS connection, pairing | Dual: GPL-3.0-or-later **or** Apache-2.0. Used here under **Apache-2.0**. |
| [spake2-java / spake2-android](https://github.com/MuntashirAkon/spake2-java) 2.2.1, Muntashir Al-Islam (pulled in by libadb-android) | SPAKE2 key exchange for pairing | **LGPL-3.0** |
| [Bouncy Castle](https://www.bouncycastle.org/) bcprov / bcpkix / bcutil 1.81 | ADB public key encoding, the self-signed certificate | Bouncy Castle Licence (MIT-style) |
| [Conscrypt](https://github.com/google/conscrypt) conscrypt-android 2.5.3 | TLS 1.3 with exported keying material (needed for pairing) | Apache-2.0 |
| AndroidX (Activity, Core, Lifecycle), Jetpack Compose, Material 3 | App framework and UI | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines | Language runtime | Apache-2.0 |

Test-only (not in the APK): JUnit 4 (EPL-1.0), org.json (Public Domain).

### LGPL-3.0 note (spake2-android)

spake2-android is used unmodified, as a separate library fetched by Gradle from
JitPack. Its source is at https://github.com/MuntashirAkon/spake2-java. To use a
modified version, change the dependency (or substitute a local build) and rebuild
this app from source; nothing in this project prevents that. If the APK is ever
distributed to other people, ship this notice with it and keep the app's source (or
at least its object code in relinkable form) available to them, as the LGPL requires.

Full licence texts: Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0,
LGPL-3.0 https://www.gnu.org/licenses/lgpl-3.0.html,
Bouncy Castle https://www.bouncycastle.org/licence.html.
