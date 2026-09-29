<h1 align="center">RF Sentinel</h1>
<h3 align="center">BLE &amp; WiFi Police Scanner for Android</h3>

<p align="center">
  Passively detects body cameras, license-plate cameras, drones, trackers and other
  surveillance equipment from the Bluetooth and WiFi signals they broadcast.
</p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/releases/latest"><img src="https://img.shields.io/github/v/release/CIS-C0/RFSentinel?label=release&color=00BFAE" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android%20Auto-supported-4285F4?logo=androidauto&logoColor=white" alt="Android Auto supported" />
  <img src="https://img.shields.io/badge/tracking-none-555555" alt="No tracking" />
  <a href="https://github.com/CIS-C0/RFSentinel/stargazers"><img src="https://img.shields.io/github/stars/CIS-C0/RFSentinel?style=flat&color=FFB000" alt="GitHub stars" /></a>
</p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/releases/latest"><img src="docs/download-button.svg" width="420" alt="Download the app for Android - free, latest version (.apk)" /></a>
</p>

<p align="center">
  <img src="docs/screenshots/list-dedsec.jpg" width="270" alt="List view with demo detections (DedSec theme)" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/radar-dedsec.jpg" width="270" alt="Radar view with demo detections (DedSec theme)" />
</p>
<p align="center"><sub>List and radar views (DedSec theme, demo data). <a href="#themes">See all 12 themes</a>.</sub></p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/stargazers"><img src="docs/star-banner.svg" width="560" alt="Like RF Sentinel? Give it a star on GitHub - it's free, takes one click and helps others find it" /></a>
</p>

---

## Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [Android Auto](#android-auto)
- [Themes](#themes)
- [Privacy](#privacy)
- [Limitations](#limitations)
- [Building from source](#building-from-source)
- [Signatures and sources](#signatures-and-sources)
- [Acknowledgements](#acknowledgements)
- [Disclaimer](#disclaimer)

## Overview

RF Sentinel is a non-rooted Android app that listens to Bluetooth LE
advertisements and WiFi beacons. It lists every device around you with its
vendor, type and signal strength, and flags law-enforcement and surveillance
equipment:

| Category | Examples |
|---|---|
| Body cameras | Axon body cams and TASER equipment, Getac, Utility |
| License-plate readers | Flock Safety cameras, Genetec |
| Audio sensors | Flock Raven |
| Public-safety gear | Motorola Solutions radios, in-car routers, e-ticket printers |
| Drones | Remote ID decoded (serial, position, altitude, operator location) |
| Trackers | AirTags and other Find My / Find Hub trackers that follow you |
| Camera glasses | Ray-Ban Meta, Snap Spectacles, Vuzix and others |

It never transmits anything. It only reads what devices already broadcast
publicly.

## Features

**Detection**
- Payload signatures, vendor prefixes (IEEE MA-L/M/S) and Bluetooth SIG identifiers, each with a confidence tier: weak, probable or strong
- Evidence fusion: independent matches on the same device strengthen each other
- Patrol-vehicle detection: several kinds of police-type equipment whose signals move together are flagged as a possible police vehicle
- Address-rotation linking: follows a device when its Bluetooth address changes
- Follower alerts: a warning when a tracker or flagged device keeps moving with you
- Editable watchlist with exact addresses, vendor prefixes, and name or vendor rules, plus regional presets (Global, Canada, US)

**Identification**
- Ordinary devices named precisely: exact AirPods/Beats model, device class from standard Bluetooth services, IEEE registrant
- WiFi access points, including hidden networks, identified from WPS data, Cisco AP names and vendor elements, and linked to visible networks on the same router
- Decoded Apple Continuity, iBeacon, Eddystone, Fast Pair and ASTM F3411 Remote ID

**Interface**
- Live list and radar view, with filters and search
- Device details: evidence, identity, signal graph, **Locate** mode, history and raw advertisement
- OpenStreetMap map with recorded GPS traces and the devices heard along them
- Alerts with sound, vibration patterns and spoken announcements, plus a discreet mode
- Android Auto app, Quick Settings tile and home-screen widget
- 12 themes, each styled theme with its own animated header

**Data**
- Export as CSV, JSON, KML, GPX or GeoJSON
- Everything stays on the phone

## Installation

Requires **Android 8.0 or newer**.

1. On your phone, tap **Download the app** above, or open the [latest release](https://github.com/CIS-C0/RFSentinel/releases/latest).
2. Under **Assets**, tap the file ending in **`-release.apk`**. Don't pick the "Source code" archives.
3. Open the downloaded file from the notification, or from **Files → Downloads**.
4. If Android blocks it as an unknown source, tap **Settings**, enable **Allow from this source**, go back and tap **Install**.
5. Open **RF Sentinel**. A short setup wizard walks you through theme, region, alerts and permissions.

To update, install the newer APK over the existing one. Your history and settings are kept.

## Usage

Tap **Start scanning** and grant location and *Nearby devices*. The notification
permission is optional; without it, alerts still play and show in the app.

### Main screen

- **Threat banner:** shows all clear, weak, probable or strong, or *may be following you*.
- **Live list:** every device heard in the last 3 minutes, with vendor, type,
  radio, signal, rough distance, and NEW / ★ / FOLLOWING badges. Flagged
  devices sort first and flash in their category colour. Matches above your
  alert threshold play a sound and vibrate: one pulse for weak, two for
  probable, three for strong.
- **Radar view:** closer to the centre means a stronger signal. The angle is
  not a direction, because a phone can't measure one.
- **Filters and search:** All, Flagged, Trackers, Drones, New, Favorites,
  Bluetooth, WiFi. Search matches name, address, vendor and type.
- **Tap** a device for details. **Long-press** for quick actions: watchlist,
  whitelist, favorite, copy.

### Device details

The app never connects to a device. The details screen shows:
- why the device is flagged: each signature with its evidence and source
- identity and how it was determined
- address type and whether the device is trackable
- decoded protocol data
- WiFi band, channel, standard and security
- drone Remote ID, with the drone and operator positions openable in your maps app
- a live signal graph and **Locate** mode, which beeps faster as you get closer
- history, including past matches and how far the device travelled with you
- the full raw advertisement

### Map and traces

- The **map** shows your position, the trace being recorded, and devices placed
  where your phone was when their signal was strongest. That approximates the
  device's position, not a fix.
- **Record trace** saves your GPS route and every device heard along it. It
  keeps running with the screen off, and can start automatically with each scan.
- Traces export as **GPX**, **KML**, **GeoJSON** or **CSV**, with all devices or
  flagged ones only.

### Export

Menu → **Export all devices...** or **Export...**:

| Export | Formats |
|---|---|
| Current scan (all devices, matched or not) | CSV, JSON, KML |
| All devices ever seen | CSV, JSON |
| Match log | CSV, JSON, GPX, KML |
| Your watchlist | JSON |
| Everything in one file | JSON |

## Android Auto

RF Sentinel runs in Android Auto as a driver-safe, template-based app:

- **Home screen:** the threat headline, live counts, Start/Stop, and a speaker
  button that mutes or unmutes all alert sound.
- **Device lists** (flagged, drones & trackers, all nearby) and **device details**
  with Whitelist, Watch and Favorite actions. **Navigate** opens your car's
  navigation app for drones with a known position.
- **Alerts** appear as a heads-up on the car screen. While connected, they play
  through the car speakers as navigation-guidance audio, briefly lowering your
  music.

Sideloaded apps only appear in Android Auto after one-time setup:
1. Open **Android Auto settings** on your phone.
2. Tap **Version** ten times to unlock developer mode.
3. Open **⋮ → Developer settings** and enable **Unknown sources**.
4. Reconnect to the car.

## Themes

Pick a theme in the setup wizard or under **Settings → Appearance**.

| Theme | Look |
|---|---|
| **DedSec** | Hacker street art: pixel skull, self-decrypting title, glitch effects, cut-corner controls |
| **Night Drive** | Red-only cockpit HUD. Every colour is mapped to red to preserve night vision while driving |
| **Night Vision** | Green phosphor image intensifier with grain and a scope vignette |
| **Amber CRT** | 1980s terminal with a blinking cursor and scanlines |
| **Synthwave** | Chrome title, striped sunset and a scrolling wireframe grid |
| **Tactical** | Olive and coyote tan, stencil callsign, Zulu clock and reticle |
| **Blueprint** | Technical drawing on drafting blue |
| **Paper** | Newspaper masthead, the most readable theme in direct sunlight |
| **System / Light / Dark / Material You** | Standard looks. Material You follows your wallpaper colours |

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-list.jpg" width="190" alt="DedSec theme, list view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-list.jpg" width="190" alt="Night Drive theme, list view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-list.jpg" width="190" alt="Night Vision theme, list view" /><br/><sub><b>Night Vision</b></sub></td><td align="center"><img src="docs/screenshots/themes/amber-list.jpg" width="190" alt="Amber CRT theme, list view" /><br/><sub><b>Amber CRT</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/synthwave-list.jpg" width="190" alt="Synthwave theme, list view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-list.jpg" width="190" alt="Tactical theme, list view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-list.jpg" width="190" alt="Blueprint theme, list view" /><br/><sub><b>Blueprint</b></sub></td><td align="center"><img src="docs/screenshots/themes/paper-list.jpg" width="190" alt="Paper theme, list view" /><br/><sub><b>Paper</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/system-list.jpg" width="190" alt="System theme, list view" /><br/><sub><b>System</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-list.jpg" width="190" alt="Light theme, list view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-list.jpg" width="190" alt="Dark theme, list view" /><br/><sub><b>Dark</b></sub></td><td align="center"><img src="docs/screenshots/themes/material-you-list.jpg" width="190" alt="Material You theme, list view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

<details>
<summary><b>Radar view in every theme</b></summary>

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-radar.jpg" width="190" alt="DedSec theme, radar view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-radar.jpg" width="190" alt="Night Drive theme, radar view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-radar.jpg" width="190" alt="Night Vision theme, radar view" /><br/><sub><b>Night Vision</b></sub></td><td align="center"><img src="docs/screenshots/themes/amber-radar.jpg" width="190" alt="Amber CRT theme, radar view" /><br/><sub><b>Amber CRT</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/synthwave-radar.jpg" width="190" alt="Synthwave theme, radar view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-radar.jpg" width="190" alt="Tactical theme, radar view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-radar.jpg" width="190" alt="Blueprint theme, radar view" /><br/><sub><b>Blueprint</b></sub></td><td align="center"><img src="docs/screenshots/themes/paper-radar.jpg" width="190" alt="Paper theme, radar view" /><br/><sub><b>Paper</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/system-radar.jpg" width="190" alt="System theme, radar view" /><br/><sub><b>System</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-radar.jpg" width="190" alt="Light theme, radar view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-radar.jpg" width="190" alt="Dark theme, radar view" /><br/><sub><b>Dark</b></sub></td><td align="center"><img src="docs/screenshots/themes/material-you-radar.jpg" width="190" alt="Material You theme, radar view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

</details>

<sub>Screenshots use made-up demo devices (debug builds only). They run through the real detection pipeline, so scores, groupings and labels match what the app shows.</sub>

## Privacy

- **Receive-only.** Nothing is transmitted, jammed, spoofed or connected to.
- **No accounts, no cloud, no analytics.** Detections are stored in a local
  database and excluded from cloud backup.
- **One network use:** OpenStreetMap tiles, only while the map is open. The tile
  server sees your IP address and the area you view, never your scans. Map data
  © OpenStreetMap contributors.
- **Data leaves the phone only when you export it.**

## Limitations

- **A match is a signature, not an identification.** `00:25:DF` means "a device
  registered to Axon Enterprise", not "a police officer". Every match shows its
  evidence and a confidence tier. Verify weak matches.
- **Phone radios are weaker than dedicated hardware.** Expect shorter range and
  fewer catches than purpose-built scanners.
- **WiFi scanning is throttled** by Android to about four scans per two minutes.
  The default 30-second interval respects that limit.
- **WiFi only sees access points.** Client devices, such as in-car laptops on
  cellular, are invisible.
- **Signal strength is not distance.** Estimates are often off by 2–3×, and the
  radar angle is not a direction.
- **Screen-off coverage is partial.** With the screen off, Android keeps only a
  filtered scan. That covers payload signatures, trackers, drones, glasses and
  exact-address watchlist entries, but not prefix-only matches.
- **Cellular-only devices are undetectable.** This includes most non-Flock
  license-plate readers and LTE GPS trackers.
- **Randomized addresses** defeat vendor-prefix matching. Payload signatures and
  address-rotation linking partly compensate.

## Building from source

Requirements: Android Studio, or JDK 17+ with the Android SDK (platform 37).

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. `tools/gen_assets.py`
regenerates the offline IEEE and Bluetooth SIG databases.

| Component | Version |
|---|---|
| Gradle | 9.6.0 (wrapper included) |
| Android Gradle Plugin | 9.4.0 (built-in Kotlin) |
| KSP / Room | 2.3.12 / 2.8.5 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

<details>
<summary><b>Signed release builds</b></summary>

`./gradlew assembleRelease` signs the APK using `keystore.properties` in the
project root (git-ignored):

```properties
storeFile=keystore/rfsentinel-release.jks
storePassword=...
keyAlias=rfsentinel
keyPassword=...
```

Without this file, the release APK is unsigned. Updates must be signed with the
same key as the installed app, so keep the keystore backed up. Each version is
published as a GitHub Release with the signed APK attached. Bump
`appVersionCode` and `appVersionName` in `app/build.gradle.kts` for every release.

</details>

## Signatures and sources

Every bundled prefix, company ID and UUID was checked against the IEEE registry
or the Bluetooth SIG assigned numbers. Each entry cites its source, score and
category. The full reference, including how evidence fusion, patrol-vehicle
detection and WiFi identification work, is in [docs/SIGNATURES.md](docs/SIGNATURES.md).

New signatures are welcome, provided they cite a registry or published research.
Please don't guess.

- **all-cameras-are-beacons** signature reference (Apache-2.0): signature values and confidence ladder
- **opendroneid-core-c** (Apache-2.0): Remote ID message layout
- **IEEE Registration Authority:** vendor database
- **Bluetooth SIG assigned numbers:** company IDs, service UUIDs, appearance values

## Acknowledgements

Inspired by the nyanBOX hardware device and the RF Party app, which was based
on Alan Meekins' DEF CON 31 talk *"Snoop Unto Them As They Snoop Unto Us"*.
Some feature ideas come from SØPHIA and BLE Radar (MetaRadar). No code from
those projects is included. The DedSec theme font is Share Tech Mono (SIL Open
Font License 1.1). The DedSec theme is an unofficial, original homage and
contains no Ubisoft artwork.

## Disclaimer

RF Sentinel is provided for awareness, research and personal privacy. Whether
passive Bluetooth and WiFi scanning is legal depends on where you are, so check
your local laws. A detection indicates that a device with a given signature is
nearby. It is not proof of who is present or what they are doing. This is not
legal advice.
