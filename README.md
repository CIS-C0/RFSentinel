<h1 align="center">RF Sentinel</h1>
<h3 align="center">BLE &amp; WiFi police scanner for Android</h3>

<p align="center">
  Passively detects body cameras, license-plate cameras, patrol vehicles, drones,
  trackers following you, camera glasses and fake cell tower signs from the
  signals they already broadcast. No root, no Termux, no account.
</p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/releases/latest"><img src="https://img.shields.io/github/v/release/CIS-C0/RFSentinel?label=release&color=00BFAE" alt="Latest release" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Android%20Auto-supported-4285F4?logo=androidauto&logoColor=white" alt="Android Auto supported" />
  <img src="https://img.shields.io/badge/tracking-none-555555" alt="No tracking" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License: GPL-3.0" /></a>
  <a href="https://github.com/CIS-C0/RFSentinel/stargazers"><img src="https://img.shields.io/github/stars/CIS-C0/RFSentinel?style=flat&color=FFB000" alt="GitHub stars" /></a>
  <a href="https://discord.gg/NDTjn8HMGq"><img src="https://img.shields.io/badge/Discord-join%20the%20community-5865F2?logo=discord&logoColor=white" alt="Join the Discord" /></a>
</p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/releases/latest"><img src="docs/download-button.svg" width="420" alt="Download the app for Android - free, latest version (.apk)" /></a>
</p>

<p align="center">
  <b><a href="https://cis-c0.github.io/RFSentinel/">Website</a> ·
  <a href="https://cis-c0.github.io/RFSentinel/qa.html">Q&amp;A</a> ·
  <a href="https://cis-c0.github.io/RFSentinel/how-it-works.html">How it works</a> ·
  <a href="https://discord.gg/NDTjn8HMGq">Discord</a></b>
</p>

<p align="center">
  <img src="docs/screenshots/list-dedsec.jpg" width="260" alt="Live list with flagged body camera, Flock camera and patrol-vehicle gear (DedSec theme, demo data)" />
  &nbsp;
  <img src="docs/screenshots/detail-axon.jpg" width="260" alt="Device details: why an Axon body camera is flagged, with evidence and sources (demo data)" />
  &nbsp;
  <img src="docs/screenshots/list-fsociety.jpg" width="260" alt="Live list in the fsociety theme (demo data)" />
</p>
<p align="center">
  <img src="docs/screenshots/android-auto/home.jpg" width="560" alt="Android Auto home: threat headline, recent alerts and the live map" />
</p>
<p align="center"><sub>Live list (DedSec theme), why a device is flagged, the fsociety theme, and the Android Auto home screen. Demo data. <a href="#themes">All 13 themes</a> · <a href="#android-auto">Android Auto</a></sub></p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/stargazers"><img src="docs/star-banner.svg" width="560" alt="Like RF Sentinel? Give it a star on GitHub - it's free, takes one click and helps others find it" /></a>
  <br />
  <a href="https://discord.gg/NDTjn8HMGq"><img src="docs/discord-banner.svg" width="560" alt="Join the RF Sentinel Discord - help, ideas, new signatures, ESP32 builds" /></a>
</p>

---

## Contents

- [At a glance](#at-a-glance)
- [What it detects](#what-it-detects)
- [Features](#features)
- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Using the app](#using-the-app)
- [Android Auto](#android-auto)
- [ESP32 boards](#esp32-boards-optional)
- [Themes](#themes)
- [Privacy](#privacy)
- [Limitations](#limitations)
- [Q&A](#qa)
- [Building from source](#building-from-source)
- [Signatures and sources](#signatures-and-sources)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license) · [Disclaimer](#disclaimer)

## At a glance

| | |
|---|---|
| **What** | A non-rooted Android app that lists every Bluetooth LE and WiFi device around you with its vendor, type and signal, and flags law-enforcement and surveillance equipment |
| **How** | Receive-only: reads advertisements, beacons and cell info that devices broadcast publicly. Never transmits to, connects to or jams anything |
| **Runs on** | Android 8.0+ phones, Android Auto, plus optional ESP32 boards (OUI-Spy, GhostESP, Marauder, Flipper Zero + BFFB, FREE-WiLi 2) and USB WiFi adapters |
| **Costs** | Nothing. GPL-3.0, no ads, no account, no subscription, no analytics |

## What it detects

| Category | Examples | Identified by |
|---|---|---|
| **Body cameras** | Axon, Motorola / WatchGuard, Digital Ally, Zepcam, Vievu, Utility, Getac, in-car video | `BWCDEVICE` payload tag, TASER company ID `0x034D`, Axon UUIDs `0xFC81` / `0xFE6B` / `0xFE6C`, IEEE prefixes such as `00:25:DF` |
| **License-plate readers** | Flock Safety, Genetec, Perceptics, Quercus, Tattile | `Flock-` SSIDs, `FS Ext Battery` / `Penguin-` / `FS-` BLE names, company ID `0x09C8`, plus the OpenStreetMap / DeFlock camera map |
| **Speed & red-light cameras** | Fixed cameras mapped in OpenStreetMap | Offline map layer, warned ~30 s ahead with the mapped limit |
| **Audio sensors** | Flock Raven | 128-bit service UUIDs `0x3100`-`0x3500` |
| **Patrol vehicles** | Two or more kinds of police-type gear travelling together | Signal correlation between body cams, radios, in-car routers, printers, rugged laptops |
| **Public-safety gear** | P25 / TETRA radios, in-car cellular routers (Sierra Wireless, Cradlepoint, Peplink, Inseego), in-car printers (Zebra, Brother), police radar makers, vehicle upfit, breath / drug screening devices, cell-site simulator and forensic makers | Company IDs, UUIDs, IEEE prefixes, Zebra factory-serial names |
| **Trackers** | AirTag & Find My, Google Find Hub, Samsung SmartTag, Tile | Separated-from-owner frames, then follow detection |
| **Drones** | Any ASTM F3411 / FAA / EU Remote ID broadcaster | Decoded Remote ID over BLE and WiFi: serial, position, altitude, speed, operator location |
| **Camera glasses** | Ray-Ban Meta, Snap Spectacles, Vuzix, HeyCyan-based glasses | Company IDs `0x0D53`, `0x03C2`, `0x060C`, Meta UUIDs |
| **Fake cell towers** | IMSI catchers / cell-site simulators | Test network codes, sudden 4G→2G, cloned or reserved cell identities, unexpected networks |
| **Network cameras** *(off by default)* | Arlo, Blink, Ezviz, Wyze, Amcrest, Swann, Night Owl... | SSIDs and IEEE blocks |

Every match shows its evidence, its source and a confidence tier: **weak** (< 50),
**probable** (50-79) or **strong** (80+). Full reference: [docs/SIGNATURES.md](docs/SIGNATURES.md).

## Features

**Detection**
- Payload, name, UUID and company-ID signatures plus IEEE vendor prefixes (MA-L / MA-M / MA-S), each with a cited source and a confidence
- **Evidence fusion:** independent matches on one device strengthen each other (noisy-OR, capped at 90)
- **Patrol-vehicle detection:** police-type gear whose signals rise and fall together is grouped as a possible police vehicle
- **Address-rotation linking:** keeps following a device when its Bluetooth address changes
- **Follower alerts:** a tracker or flagged device that stays with you (default 10 min and 800 m, adjustable) raises *may be following you*
- **Remote ID drones** decoded live, with a *drone overhead* alert within 200 m
- Optional **cell tower change alert**: says so each time the phone moves to another serving tower (Settings; a change while parked is a classic fake-tower sign)
- **Fake cell tower signs** checked every 15 s, with persistence, call and border suppression (modelled on [EFF's Rayhunter](https://github.com/EFForg/rayhunter) as far as Android allows without root)
- **Hidden WiFi networks** identified from WPS data, Cisco AP names and vendor elements, and linked to the visible network on the same router
- Ordinary devices named precisely: exact AirPods / Beats model, device class, IEEE registrant; decoded Apple Continuity, iBeacon, Eddystone, Fast Pair
- Editable **watchlist** (exact addresses, prefixes, name / vendor rules) with Global, Canada and US presets; per-category on/off switches
- Bluetooth 5 extended advertising and LE Coded (long range) where the phone supports it

**Known cameras**
- Plate-reader, speed and red-light cameras from OpenStreetMap appear on the map by themselves and are kept offline, refreshed weekly
- Warned as you approach one, even cellular-only cameras no radio can detect
- Pre-download everything within a radius you set (10-200 km), or every mapped Flock camera in the US and Canada (~143,000)
- Tap a camera to silence its alerts for good

**Alerts**
- Sound, vibration pattern per tier (1 / 2 / 3 pulses) and spoken announcements through the phone's own speech engine (prioritised, voice and speed pickers)
- **Short spoken alerts** option ("Body cam", "Police car", "Speed camera, 50")
- Discreet mode, adjustable alert threshold, mute from the app or the car
- **Floating threat bubble** over Waze, Google Maps or any app
- **Floating mini map** over other apps: the devices and known cameras around you, like the app's map (pinch to zoom, drag, resize)
- Android Auto alert cards say what it is, how sure, about how far and how many more are flagged ("BODY CAM · strong · ~40 m · near (-58 dBm) · +2 more flagged")
- Notification, Quick Settings tile and home-screen widget

**Map & history**
- OpenStreetMap map with your GPS trace and every device pinned exactly where its signal peaked (tap a spot to list everything heard there), the same filter chips as the list, self-centering
- Optional **cell tower layer** (or the map's **Cells** chip): every tower seen while scanning, at the spot where its signal was strongest
- **Record traces** of your route and the devices along it, with the screen off (with the network names each device asked for, when a USB adapter or Marauder board is plugged in)
- **History map & timeline:** heatmap of where flagged equipment showed up, and when (hour of day, day of week)

**Driving**
- **Android Auto** app: threat headline, next camera ahead, recent alerts, filtered device lists, cell towers, hardware status, live map with cameras and drones, OpenStreetMap turn-by-turn navigation, alerts through the car speakers with Mute 30 min / Ignore buttons
- **Starts by itself in the car** (your car's Bluetooth or Android Auto) and stops when you leave
- Night Drive red-only theme; screen stays on while charging

**Hardware**
- ESP32 boards over USB OTG (OUI-Spy, GhostESP, Marauder incl. dual-band ESP32-C5 boards, or through a Flipper Zero) or Bluetooth (OUI-SPY App-Controlled) add their detections and extend range
- **USB WiFi adapters in monitor mode** over OTG, no root: RTL8811AU / RTL8821AU (e.g. ALFA AWUS036ACS, 2.4 GHz), **RTL8812BU / RTL8822BU** (2.4 + 5 GHz, e.g. TP-Link Archer T3U, Wise Tiger AC1200), **RTL8814AU** (ALFA AWUS1900, 2.4 + 5 GHz), **MT7612U** (ALFA AWUS036ACM, 2.4 + 5 GHz), **RTL8187** (ALFA AWUS036H), **RT3070** (ALFA AWUS036NH / NEH) and, experimental, **AR9271** (ALFA AWUS036NHA). No Android scan limit, longer range, and **client devices** (laptops, phones, cameras connected to a network) the phone's WiFi scan can't see. Single-antenna dongles are detected and handled automatically
- **Requested networks:** the WiFi names nearby devices ask for (probe requests), from a USB adapter or a Marauder board, in an optional list; **watch** a name to get an alert whenever any device asks for it
- **More from every frame:** WPS maker / model / device name, a **probe fingerprint** that survives MAC randomization (watchable: "every device of this type"), and the real name of **hidden networks** when a device joins them

**Interface**
- Live list and radar view, filter chips with live counts (flagged, trackers, drones, favorites, new, Bluetooth, WiFi, cells, external hardware), search
- **Tools** (button next to the map button): **Cell towers** (serving and neighbour cells with network, IDs, channel and signal, plus every tower seen while scanning), **WiFi channels** and **WiFi spectrum** analyzers for 2.4 / 5 / 6 GHz
- **Cell towers in the live list:** the serving cell and its neighbours appear under *All*, after the devices
- **Scan watchdog:** a scanner that goes silent is restarted on its own
- Device details: evidence, identity, decoded data, signal graph, **Locate** mode, history, raw advertisement
- Setup wizard (theme, region, alerts and voice, bubble, car start, camera warnings, cell alerts, camera radius, screen, permissions); Settings save themselves
- **Check for updates** (menu ⋮): compares with the latest GitHub release, only when you tap it
- 13 themes, including the **DedSec** (Watch Dogs 2) and **fsociety** (Mr. Robot) fan themes

**Data & privacy**
- Everything stays on the phone; export as CSV, JSON, KML, GPX or GeoJSON
- **Report unknown device:** a privacy-safe signature (no full address, serials, location or times) to help add new equipment
- No accounts, no cloud, no analytics

## Quick start

Requires **Android 8.0 or newer**.

1. On your phone, tap **Download the app** above, or open the [latest release](https://github.com/CIS-C0/RFSentinel/releases/latest).
2. Under **Assets**, tap the file ending in **`-release.apk`** (not the "Source code" archives).
3. Open the downloaded file from the notification, or from **Files → Downloads**.
4. If Android blocks it, tap **Settings**, enable **Allow from this source**, go back and tap **Install**.
5. Open **RF Sentinel**. The setup wizard walks you through theme, region, alerts, camera radius and permissions.
6. Tap **Start** and grant location and *Nearby devices*.

<p align="center"><img src="docs/screenshots/setup-theme.jpg" width="260" alt="Setup wizard: pick a theme" /></p>

**Updating:** install the newer APK over the existing one. History and settings are kept.
Samsung, Xiaomi or OnePlus stopping the scan? Set RF Sentinel's battery usage to
**Unrestricted** (Settings has a shortcut).

## How it works

```
BLE adverts ─┐
WiFi beacons ─┼─> signatures + watchlist ─> evidence fusion ─> patrol grouping ─> alerts
Cell info ────┤    (SIG IDs, UUIDs, payload   (noisy-OR,       (RSSI correlation    (sound, voice,
ESP32 boards ─┘     tags, IEEE prefixes)       cap 90)           r ≥ 0.6, ≥ 8 s)      bubble, car)
                         │
                         └─> address-rotation linking ─> follow detection (time + distance with you)
```

- **Confidence tiers:** weak < 50 (amber, silent by default), probable 50-79 (alerts at the default threshold), strong 80+.
- **Fusion:** `1 - (1 - top) × Π(1 - 0.5 × support)`; e.g. watchlisted address 70 + Zebra serial name 50 → 78.
- **Patrol vehicle:** at least two different roles whose RSSI correlates (Pearson r ≥ 0.6 over ≥ 8 s), or both parked and seen within 30 s; scored 24 + 12 × roles, +10 if moving together, cap 88.
- **Rotation linking:** same advert fingerprint (names, UUIDs, payload sizes, TX power, connectable) as exactly one device that went silent 1.5-30 s earlier.

Details: [How it works](https://cis-c0.github.io/RFSentinel/how-it-works.html) and [docs/SIGNATURES.md](docs/SIGNATURES.md).

## Using the app

### Main screen

- **Threat banner:** all clear, weak, probable or strong, or *may be following you*.
- **Live list:** every device heard in the last 3 minutes, with vendor, type,
  radio, signal, rough distance, and NEW / ★ / FOLLOWING badges. A green
  **INTERNAL** badge means the phone heard it; a blue **ESP32** badge means a
  connected board reported it. Flagged devices sort first and flash in their
  category colour.
- **List / Radar / Map:** the toggle switches list and radar; **Map** sits right
  next to it. On the radar, closer to the centre means a stronger signal; the
  angle is not a direction (a phone can't measure one).
- **Filters and search:** All, Flagged, Trackers, Drones, Favorites, New,
  Bluetooth, WiFi, Cells, External (heard by an ESP32 board or a USB WiFi
  adapter). Search matches name, address, vendor and type.
- **Tap** a device for details. **Long-press** for watchlist, whitelist,
  favorite, copy, ignore, and **Report unknown device**.
- **Floating bubble** (Settings, needs *Display over other apps*): green when
  clear, orange for probable, red for strong or a follower, with the count.
- **Floating mini map** (Settings, same permission): the devices and known
  cameras around you over Waze, Google Maps or any app, centred on you, with a
  threat-colour border. Pinch to zoom, drag to move, the corner handle resizes
  it, tap to open the full map.
- **In the car** (Settings → *Start scanning automatically in the car*): pick
  your car's Bluetooth; scanning starts when the phone connects to it or to
  Android Auto, and stops when you leave if the car started it.

### Device details

The app never connects to a device. The details screen shows why it's flagged
(each signature with its evidence and source), identity and how it was
determined, address type and trackability, decoded protocol data, WiFi band,
channel and security, drone Remote ID with drone and operator positions, a live
signal graph with **Locate** mode (beeps faster as you get closer), history, and
the raw advertisement.

### Map and traces

- The **map** shows your position, the trace being recorded, and every device
  heard around you, placed where your phone was when its signal was strongest.
  That's an approximation, not a fix. Each dot stays pinned to its spot as you
  pan and zoom; tap a spot where several devices were heard to pick one from a list.
- **Cell towers** (map menu ⋮ → *Show cell towers*): towers seen while scanning,
  at the spot where your phone heard them strongest - an estimate, not the
  tower's real position (looking that up online would reveal where you are).
- **Known cameras** (map menu ⋮): *Download nearby cameras* fetches plate readers
  within your radius (10-200 km, 100 by default); *Download Flock* fetches every
  plate reader mapped in the US and Canada (~18 MB, refreshed weekly on WiFi).
  Both use [DeFlock](https://deflock.org)'s hourly snapshot of OpenStreetMap,
  plus cameras it leaves out, straight from OpenStreetMap. Tap a camera →
  *Ignore alerts* to never be warned about it again.
- **Record trace** saves your route and every device heard along it, with the
  screen off, and can start with each scan.
- **Screen:** *always on*, *on while charging* (default) or *normal*.

### Export

Menu → **Export all devices...** or **Export...**:

| Export | Formats |
|---|---|
| Current scan (all devices) | CSV, JSON, KML |
| All devices ever seen | CSV, JSON |
| Match log | CSV, JSON, GPX, KML |
| Recorded traces | GPX, KML, GeoJSON, CSV |
| Watchlist | JSON |
| Everything in one file | JSON |

## Android Auto

A driver-safe, template-based Android Auto app:

<p align="center">
  <img src="docs/screenshots/android-auto/home.jpg" width="400" alt="Android Auto home screen with the threat headline and recent alerts" />
  <img src="docs/screenshots/android-auto/recent-alerts.jpg" width="400" alt="Android Auto recent alerts, kept after the device has left" />
</p>
<p align="center">
  <img src="docs/screenshots/android-auto/flagged.jpg" width="400" alt="Android Auto list of flagged devices with the radio that heard each one" />
  <img src="docs/screenshots/android-auto/more.jpg" width="400" alt="Android Auto More screen: snooze alerts, spoken alerts, cell towers, hardware" />
</p>
<p align="center">
  <img src="docs/screenshots/android-auto/live-map.jpg" width="400" alt="Android Auto live map with the devices around you" />
  <img src="docs/screenshots/android-auto/cameras.jpg" width="400" alt="Android Auto live map zoomed out with known cameras" />
</p>
<p align="center"><sub>Home, recent alerts, flagged devices, More, live map and known cameras (demo data).</sub></p>

- **Home**, most important first (a car shows about six rows while driving):
  the threat headline (tap for the flagged devices), the **next known camera on
  your way** with its distance, **recent alerts**, the map, nearby devices and
  **More**. Start/Stop and a speaker button that mutes or unmutes all alert
  sound sit in the header.
- **Nearby devices** with the phone's filters and live counts (flagged,
  trackers, drones, favorites, new, Bluetooth, WiFi, ESP32 / USB). Each row says
  which radio heard it.
- **Device details** with Whitelist, Watch and Favorite; trackers get the
  phone's three ignore choices (*it's mine*, *today only*, *pause follow
  warnings*). **Navigate** hands drones with a known position to your
  navigation app. A device that has left still shows its last alert.
- **Recent alerts:** the last 40 alerts (devices, cameras, cell warnings), kept
  after the device is gone.
- **Cameras:** tap one to see it alone on the map, navigate to it, or tap its
  row to turn its alerts off or back on (faded on the maps).
- **More:** snooze alerts for 30 minutes, spoken alerts in the car, short
  spoken alerts, whether AirTags count as trackers, **cell towers** (fake-cell
  check status, serving cell and neighbours) and **hardware** status (phone
  radios and the scan watchdog, ESP32 / Marauder / Flipper on USB, OUI-SPY over
  Bluetooth, USB WiFi adapter).
- **Live map:** RF Sentinel's own OpenStreetMap map on the car screen with
  devices, known plate / speed / red-light cameras and your position. Drag,
  pinch or **+ / −**; **◎** follows the car again. Needs car API level 7; older
  Android Auto gets car-drawn maps with device and camera lists.
- **Navigation:** *Navigate to...* searches with [Nominatim](https://nominatim.org)
  (on submit only), keeps recent destinations on the phone, routes with
  [OSRM](https://project-osrm.org), speaks turns and re-routes.
- **Alerts** appear as heads-up cards with **Mute 30 min** and **Ignore**
  buttons, and play through the car speakers as navigation audio, briefly
  lowering your music.
- Screens only redraw when what they show has changed, and a screen that fails
  to build shows a short message instead of closing the app.

**In a real car, Android Auto only shows apps like this one when they come
from a trusted store.** Google lets apps built with its Car App Library (as
RF Sentinel is) run in a real car only when they're installed from Google Play
or ONE store, and Android Auto's **Unknown sources** developer setting doesn't
cover them ([Google's testing guide](https://developer.android.com/training/cars/testing#real-vehicles)).
The GitHub APK's car screens therefore run only on Google's
[Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
emulator.

**The GitHub APK still works in a real car**, through the two app types that
setting does cover. Android Auto settings → tap **Version** ten times →
**⋮ → Developer settings** → enable **Unknown sources**, then reconnect:

- **RF Sentinel screen (media-style):** tabs for **Threats**, **Cameras**
  (nearest known cameras, the one ahead first), **Alerts** and **Status**
  (start / stop scanning, mute, cell towers, hardware), plus a card with the
  current threat and **Scan** / **Mute** buttons. It never plays anything, so
  your music keeps playing and keeps the steering-wheel buttons.
- **Alerts as messages:** every alert arrives as a message from RF Sentinel,
  which the car shows and can read aloud. Reply **"mute"** to silence alerts
  for 30 minutes or **"ignore"** to stop alerting about the latest device.

If RF Sentinel is missing from the car's launcher, tick it under
**Customize launcher**. The live map needs the full car app, so it stays on
the Desktop Head Unit.

## ESP32 boards (optional)

Plug an ESP32 board into the phone with a USB OTG cable while scanning (allow
USB access the first time), or pair one over Bluetooth. What the board reports
merges into the list with a blue **ESP32** badge.

| Firmware | Link | What RF Sentinel gets |
|---|---|---|
| [OUI-Spy](https://github.com/colonelpanichacks/oui-spy-unified-blue) (recommended) | USB | Flock-You, Detector and Sky Spy detections as they happen: Flock cameras, the board's watchlist matches, Remote ID drones with position |
| [OUI-SPY App-Controlled](https://github.com/lukeswitz/oui-spy-unified-blue) | Bluetooth | Flock-BLE, Flock-WiFi, Sky Spy, Detector and (optional) Wardrive survey engines |
| [GhostESP](https://github.com/GhostESP-Revival/GhostESP) | USB | The WiFi networks it scans, about every 10 s |
| [FREE-WiLi 2](https://freewili.com) | USB | The WiFi networks its ESP32-C5 scans from its console's WiFi menu (2.4 + 5 GHz) |
| [ESP32 Marauder](https://github.com/justcallmekoko/ESP32Marauder) | USB, or through a Flipper Zero | WiFi networks and client devices from its passive beacon and probe sniffers, batched every 2 s; on ESP32-C5 boards (Marauder v8, BFFB v2, LilyGo T-Dongle C5, C5 DevKit) both 2.4 and 5 GHz |

Native-USB boards (ESP32-S2/S3/C3/C5/C6) and boards with a CP210x or CH340 bridge
work. RF Sentinel only asks OUI-Spy for its version and live table, only asks
GhostESP to scan and list networks, and only runs Marauder's passive
`sniffbeacon` / `sniffprobe` sniffers (no attack commands, no settings changes).

**Marauder on ESP32-C5** (Marauder v8, BFFB v2, T-Dongle C5, C5 DevKit) hears 5 GHz
networks the phone's throttled WiFi scan misses, which helps catch vehicle
routers on a pass. Keep **Channel Hop** on in Marauder's settings; RF Sentinel
says so if the board stays on one channel.

**Flipper Zero (e.g. with the BFFB board):** plug the Flipper into the phone and
open **GPIO → USB-UART Bridge** on the Flipper; RF Sentinel then talks to the
board's Marauder through it. A BFFB v2 can also be plugged in by its own USB-C
port.

**OUI-SPY over Bluetooth:** start scanning with the board on, then **Settings →
Pair OUI-SPY board** and pick `OUI-SPY-xxxx`. Pair it only there, not in
Android's Bluetooth settings: the board keeps no pairing keys, so a phone-side
pairing breaks the link (the app tells you to tap *Forget* if it finds one).
While scanning, RF Sentinel switches on the board's Flock-BLE, Flock-WiFi, Sky
Spy and Detector engines, and by default its Wardrive survey engine so the board
relays **every** WiFi network and Bluetooth device for RF Sentinel's own rules.
In that mode the board sends standard WiFi probe requests; turn **Settings →
OUI-SPY: relay every network and Bluetooth device** off to keep it listening
only. The board's UniPwn, PCAP and Foxhunter engines are never used.

### USB WiFi adapter (monitor mode)

Plug a supported USB WiFi adapter into the phone with an OTG cable while
scanning and allow USB access on the prompt. RF Sentinel drives it directly
over Android's USB host API (no root, no kernel driver), puts it in
**receive-only monitor mode** and hops the channels (2.4 GHz on 88xxau, RTL8187,
RT3070 and AR9271; 2.4 GHz and 5 GHz on 88x2bu, 8814au and MT7612U). Access points
(beacons, probe responses) and client devices (probe requests, data frames)
go through RF Sentinel's own rules like everything else, with a purple
**USB WIFI** badge.

| Chip | Example adapters | Status |
|---|---|---|
| Realtek RTL8811AU / RTL8821AU (`0bda:0811`) | ALFA AWUS036ACS | Supported (2.4 GHz) |
| Realtek RTL8812BU / RTL8822BU (`0bda:b812`, `0bda:b82c` and ~35 other IDs) | TP-Link Archer T3U, ASUS USB-AC53/AC55/AC58, Edimax EW-7822U*, Wise Tiger AC1200 | Supported (2.4 + 5 GHz), tested on a Wise Tiger 8812BU |
| Realtek RTL8814AU (`0bda:8813` and 13 other IDs) | ALFA AWUS1900, TP-Link Archer T9UH, ASUS USB-AC68, Netgear A7000 | Supported (2.4 + 5 GHz), not yet tested on hardware |
| MediaTek MT7612U / MT7632U (`0e8d:7612` and 16 other IDs) | ALFA AWUS036ACM, ASUS USB-AC55, Netgear A6210 | Supported (2.4 + 5 GHz), not yet tested on hardware |
| Realtek RTL8187L / RTL8187B (`0bda:8187` and 20 other IDs) | ALFA AWUS036H, Netgear WG111v2/v3 | Supported (2.4 GHz), tested on an ALFA AWUS036H |
| Ralink RT3070 (`148f:3070` and 4 other IDs) | ALFA AWUS036NH / AWUS036NEH | Supported (2.4 GHz), tested on an ALFA AWUS036NEH |
| Atheros AR9271 (`0cf3:9271` and 17 other IDs) | ALFA AWUS036NHA, TP-Link TL-WN722N v1 | Experimental (2.4 GHz) |
| RTL8812AU, RTL8811CU / 8821CU, RT5370, MT7610U, AR7010 | AWUS036ACH... | Detected and named, not supported yet |

The adapter never transmits (on the MT7612U and RT3070 even the automatic ACK
replies are switched off).
Settings shows its status, and **Export USB WiFi adapter log** saves a text
file (app and phone version, the USB devices plugged in, the driver log) to
send to the developer - no nearby devices' addresses or network names are in it.

On a single-antenna 8812BU dongle the antenna sits on one of the chip's two
receive paths, while the chip hears 2.4 GHz beacons on path A only. RF Sentinel
compares both paths on the first seconds of traffic and moves 2.4 GHz reception
to the one with the antenna (about 30 dB better on the dongle we tested - more
than Realtek's own Windows driver gets from it).

### RTL-SDR: two-way radio transmitting nearby

Plug an RTL-SDR dongle (R820T / R820T2 / R860 tuner, e.g. RTL-SDR Blog V3, or the
RTL-SDR Blog V4) into the phone with an OTG cable while scanning. RF Sentinel
sweeps the North American public-safety radio bands - the 700 and 800 MHz bands
where police, fire and EMS radios (not towers) transmit, and the shared VHF / UHF
land-mobile bands - and measures only **how much energy each 12.5 kHz channel
carries**. Nothing is demodulated, decoded, recorded or decrypted: it can't know
what was said or who said it.

It first learns which channels are busy all the time where you are (towers,
repeaters, trunking control channels, pagers) and ignores them; a strong burst on
a normally quiet channel means a radio is transmitting close by, and raises a
**Two-way radio transmitting nearby** alert (category *RADIO*, can be switched off
in Settings). On 700 / 800 MHz that's a probable sign of a public-safety radio; on
VHF / UHF, which businesses, schools and transit share, only a very strong signal
counts, and only as a weak sign. Consumer walkie-talkies (FRS / GMRS, MURS),
marine, railroad, weather and paging channels are skipped. The dongle's log is in
the exported USB adapter log.

**Requested networks** (Menu → *Requested networks*): phones and laptops ask by
name for networks they joined before. With an adapter or a Marauder board
plugged in, RF Sentinel can keep these names (off by default: they're other
people's network names; always kept while a map trace records). Tap **Watch** on
one to get an alert whenever any device asks for it. Devices also show their
**WPS** maker / model / name, a **probe fingerprint** you can watch ("every
device of this type", even with random addresses), and hidden networks show
their real name once a device joins them. Built in: Axon Fleet in-car network
names ("Axon12-5g", vehicle number + "-5g") and Cradlepoint vehicle routers on
their default names.

## Themes

Pick a theme in the setup wizard or under **Settings → Appearance**.

| Theme | Look |
|---|---|
| **DedSec** | Watch Dogs 2-style: black and white with electric blue, a full-width DedSec poster banner, a sliced wordmark and a `marcus@ctOS-2.0:~$` terminal typing hacktivist lines |
| **fsociety** | Mr. Robot-style: black and blood red, a full-width fsociety poster banner, a red striped wordmark and a `root@fsociety:~#` terminal typing quotes from the show |
| **Night Drive** | Red-only cockpit HUD that preserves night vision while driving |
| **Night Vision** | Green phosphor image intensifier with grain and a scope vignette |
| **Amber CRT** | 1980s terminal with a blinking cursor and scanlines |
| **Synthwave** | Chrome title, striped sunset and a scrolling wireframe grid |
| **Tactical** | Olive and coyote tan, stencil callsign, Zulu clock and reticle |
| **Blueprint** | Technical drawing on drafting blue |
| **Paper** | Newspaper masthead, the most readable theme in direct sunlight |
| **System / Light / Dark / Material You** | Standard looks; Material You follows your wallpaper colours |

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-list.jpg" width="190" alt="DedSec theme, list view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/fsociety-list.jpg" width="190" alt="fsociety theme, list view" /><br/><sub><b>fsociety</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-list.jpg" width="190" alt="Night Drive theme, list view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-list.jpg" width="190" alt="Night Vision theme, list view" /><br/><sub><b>Night Vision</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/amber-list.jpg" width="190" alt="Amber CRT theme, list view" /><br/><sub><b>Amber CRT</b></sub></td><td align="center"><img src="docs/screenshots/themes/synthwave-list.jpg" width="190" alt="Synthwave theme, list view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-list.jpg" width="190" alt="Tactical theme, list view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-list.jpg" width="190" alt="Blueprint theme, list view" /><br/><sub><b>Blueprint</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/paper-list.jpg" width="190" alt="Paper theme, list view" /><br/><sub><b>Paper</b></sub></td><td align="center"><img src="docs/screenshots/themes/system-list.jpg" width="190" alt="System theme, list view" /><br/><sub><b>System</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-list.jpg" width="190" alt="Light theme, list view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-list.jpg" width="190" alt="Dark theme, list view" /><br/><sub><b>Dark</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/material-you-list.jpg" width="190" alt="Material You theme, list view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

<details>
<summary><b>Radar view in every theme</b></summary>

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-radar.jpg" width="190" alt="DedSec theme, radar view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/fsociety-radar.jpg" width="190" alt="fsociety theme, radar view" /><br/><sub><b>fsociety</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-radar.jpg" width="190" alt="Night Drive theme, radar view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-radar.jpg" width="190" alt="Night Vision theme, radar view" /><br/><sub><b>Night Vision</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/amber-radar.jpg" width="190" alt="Amber CRT theme, radar view" /><br/><sub><b>Amber CRT</b></sub></td><td align="center"><img src="docs/screenshots/themes/synthwave-radar.jpg" width="190" alt="Synthwave theme, radar view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-radar.jpg" width="190" alt="Tactical theme, radar view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-radar.jpg" width="190" alt="Blueprint theme, radar view" /><br/><sub><b>Blueprint</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/paper-radar.jpg" width="190" alt="Paper theme, radar view" /><br/><sub><b>Paper</b></sub></td><td align="center"><img src="docs/screenshots/themes/system-radar.jpg" width="190" alt="System theme, radar view" /><br/><sub><b>System</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-radar.jpg" width="190" alt="Light theme, radar view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-radar.jpg" width="190" alt="Dark theme, radar view" /><br/><sub><b>Dark</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/material-you-radar.jpg" width="190" alt="Material You theme, radar view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

</details>

<sub>Screenshots use made-up demo devices (debug builds only), run through the real detection pipeline, so scores, groupings and labels match what the app shows.</sub>

## Privacy

- **Receive-only.** Nothing is transmitted, jammed, spoofed or connected to.
- **No accounts, no cloud, no analytics.** Detections live in a local database
  excluded from cloud backup.
- **Network use is limited to OpenStreetMap data:** map tiles while the map is
  open, and the known-camera list for the area you look at, fetched once per
  area and refreshed weekly from overpass-api.de (or the mirrors
  overpass.kumi.systems and overpass.private.coffee). Camera downloads also
  fetch DeFlock's region files from cdn.deflock.me. These servers see your IP
  address and the area, never your scans. Turn off *Show known cameras
  automatically* to fetch only when you tap a download. In Android Auto,
  navigation uses Nominatim and OSRM only when you search or start a route.
  Map data © OpenStreetMap contributors (ODbL).
- **Data leaves the phone only when you export or share it.**
- **Optional permissions:** *Nearby devices → connect* only to list your paired
  Bluetooth devices when you pick your car; *Display over other apps* only for
  the floating bubble. No contacts, storage, camera or microphone access.

## Limitations

- **A match is a signature, not an identification.** `00:25:DF` means "a device
  registered to Axon Enterprise", not "a police officer". Verify weak matches.
- **Phone radios are weaker than dedicated hardware.** Expect shorter range and
  fewer catches than purpose-built scanners; an ESP32 board helps.
- **WiFi scanning is throttled** by Android to about four scans per two minutes;
  the default 30-second interval respects that.
- **WiFi only sees access points.** Client devices, such as in-car laptops, are
  invisible without monitor mode.
- **Signal strength is not distance.** Estimates are often off by 2-3×, and the
  radar angle is not a direction.
- **Bluetooth needs the phone screen on.** With the screen off, Android pauses
  app Bluetooth scanning, and on some phones (Pixel tested) nothing gets through
  until the screen comes back on. WiFi, the fake-cell checks, known-camera
  warnings and ESP32 / USB hardware keep working. Android Auto warns you when a
  scan starts with the phone screen off and shows "Bluetooth limited" on its
  home screen.
- **Cellular-only devices are undetectable by radio**, including most non-Flock
  plate readers and LTE GPS trackers. The known-camera map covers mapped ones.
- **Not a radar detector.** Police radar and lidar, and radios' voice channels
  (VHF / UHF / 700 / 800 MHz), can't be received by a phone.
- **Fake cell tower checks are heuristic.** Without root, Android shows the
  cells but not ciphering or signalling, so each sign has innocent
  explanations. The strongest protection is turning off *Allow 2G* (Android
  12+); Android 15+ can also warn when a network asks for your SIM identity or
  turns encryption off (*Cellular security → Network notifications*, linked from
  Settings).
- **Randomized addresses** defeat vendor-prefix matching. Payload signatures and
  rotation linking partly compensate.
- **Android only.** iOS doesn't expose the advertisement data, WiFi scans or
  cell info this needs.

## Q&A

<details>
<summary><b>How do I detect police with my Android phone?</b></summary>

Install RF Sentinel, tap **Start scanning**, and grant location and *Nearby
devices*. It matches the Bluetooth LE and WiFi signals of body cameras, radios,
in-car routers, printers and plate readers, and groups gear that moves together
into a possible police vehicle. [More](https://cis-c0.github.io/RFSentinel/police-detector.html)
</details>

<details>
<summary><b>How do I know if an AirTag is following me on Android?</b></summary>

A separated AirTag broadcasts Apple company ID `0x004C`, type `0x12`. RF Sentinel
flags it, follows it through address changes, and warns once it has been with you
for 10 minutes over 800 m (adjustable). **Locate** mode helps find it.
[More](https://cis-c0.github.io/RFSentinel/tracker-detector.html)
</details>

<details>
<summary><b>Can it detect Flock cameras?</b></summary>

Yes: `Flock-` WiFi networks, Flock BLE names and company ID `0x09C8`, plus every
Flock camera mapped in OpenStreetMap / DeFlock, offline.
[More](https://cis-c0.github.io/RFSentinel/flock-camera-detector.html)
</details>

<details>
<summary><b>Can it detect a Stingray / IMSI catcher without root?</b></summary>

It checks the signs Android exposes: test network codes, sudden 4G→2G switches
with strong LTE still visible, cloned or reserved cell identities. It's
heuristic; disabling 2G is the real protection.
[More](https://cis-c0.github.io/RFSentinel/imsi-catcher-detector.html)
</details>

<details>
<summary><b>Is there a free alternative to SØPHIA (s0phia ops)?</b></summary>

RF Sentinel: free and open source, with police, ALPR, tracker, drone and fake-cell detection on top of passive BLE and WiFi recon.
[Comparison](https://cis-c0.github.io/RFSentinel/sophia-alternative.html)
</details>

<details>
<summary><b>Does it need root? Is it on Google Play?</b></summary>

No root. Not on Google Play: the signed APK is on
[GitHub Releases](https://github.com/CIS-C0/RFSentinel/releases/latest).
</details>

**70+ more answers on the [Q&A page](https://cis-c0.github.io/RFSentinel/qa.html)**, and how RF Sentinel compares with Flock You, OUI-Spy, Wardrive Go, Fieldwatch and others: [comparison](https://cis-c0.github.io/RFSentinel/alternatives.html).

## Building from source

Requirements: Android Studio, or JDK 17+ with the Android SDK (platform 37).

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. `tools/gen_assets.py`
regenerates the offline IEEE and Bluetooth SIG databases. The website is built
from `site/` with `python tools/build_site.py` (see [site/README.md](site/README.md)).

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

<details>
<summary><b>Demo data for screenshots</b></summary>

Debug builds can fill the list with made-up devices that run through the real
detection pipeline:

```bash
adb shell am start -n com.rfsentinel.app.debug/com.rfsentinel.app.MainActivity --ez demo true
```

</details>

## Signatures and sources

Every bundled prefix, company ID and UUID was checked against the IEEE registry
or the Bluetooth SIG assigned numbers, and each entry cites its source, score
and category. The full reference, including evidence fusion, patrol-vehicle
detection and WiFi identification, is in [docs/SIGNATURES.md](docs/SIGNATURES.md).

- **all-cameras-are-beacons** signature reference (Apache-2.0): signature values and confidence ladder
- **opendroneid-core-c** (Apache-2.0): Remote ID message layout
- **IEEE Registration Authority:** vendor database
- **Bluetooth SIG assigned numbers:** company IDs, service UUIDs, appearance values

## Contributing

- **Found an unidentified device?** Long-press it → **Report unknown device**,
  check what's shared, and paste it into a [new issue](https://github.com/CIS-C0/RFSentinel/issues/new)
  or the [Discord](https://discord.gg/NDTjn8HMGq).
- **New signatures** are welcome when they cite a registry or published research.
  Please don't guess.
- **Map cameras** in OpenStreetMap (e.g. through [DeFlock](https://deflock.org)):
  every RF Sentinel user gets them.
- **Bugs and ideas:** [issues](https://github.com/CIS-C0/RFSentinel/issues) or Discord.
- **Star the repo** so others find it.

## Acknowledgements

Inspired by the nyanBOX hardware device and the RF Party app, which was based
on Alan Meekins' DEF CON 31 talk *"Snoop Unto Them As They Snoop Unto Us"*.
Some feature ideas come from SØPHIA and BLE Radar (MetaRadar). No code from
those projects is included. The USB WiFi monitor-mode driver for RTL8811AU /
RTL8821AU adapters (`app/src/main/java/com/rfsentinel/app/usb/`) is ported from
[Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod (GPL-3.0),
whose register tables come from Realtek's 88xxau Linux driver
([aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au), GPL-2.0; see
`usb/NOTICE.md`); its handshake /
PMKID capture was left out. The RTL8812BU / RTL8822BU driver is ported from the
receive path of [devourer](https://github.com/OpenIPC/devourer) by OpenIPC
(GPL-2.0), with the firmware and register tables from Realtek's rtl88x2bu
driver; every transmit path was left out. The RTL8814AU, MT7612U, RT3070 and
AR9271 drivers and the FREE-WiLi 2 reader are ported from Wardrive Go too
(checked against and fixed from the Linux rt2800usb, mt76x2u and ath9k_htc
drivers), and the RTL8187 driver from the Linux rtl8187 driver with Kismet's
Android PCAP Capture as the USB reference. Their firmware (Ralink rt2870.bin,
MediaTek mt7662, open ath9k_htc) is the unmodified linux-firmware images, with
their licences in `assets/usbwifi/`; see `usb/NOTICE.md`. The map's anchored point layer follows
Wardrive Go's approach - thank you.

Part of the MAC-prefix data was cross-checked with, and extended from, the lists
in [Flock You](https://github.com/colonelpanichacks/flock-you) and
[OUI-Spy](https://github.com/colonelpanichacks/oui-spy-unified-blue) (with
@NitekryDPaul's [nite-oui-collection](https://github.com/nitekry/nite-oui-collection)),
[Wardrive Go](https://github.com/RocketGod-git/wardrive-go),
[Flock-You-Android](https://github.com/MaxwellDPS/Flock-You-Android) and
[Fieldwatch](https://github.com/OffGridPete/Fieldwatch) - thank you. Every prefix
was re-checked against its IEEE registrant; see
[docs/SIGNATURES.md](docs/SIGNATURES.md#community-lists-compared-2026-10). Known
plate-reader locations come from [DeFlock](https://github.com/FoggedLens/deflock)'s
snapshot of OpenStreetMap, mapped by its volunteers (data © OpenStreetMap
contributors, ODbL) - thank you. The DedSec and fsociety theme font is Share Tech
Mono (SIL Open Font License 1.1). The DedSec theme is an unofficial fan homage to
Watch Dogs; its header poster uses DedSec imagery. Watch Dogs, DedSec, ctOS and Blume
are trademarks of Ubisoft. The fsociety theme is an unofficial fan homage to Mr. Robot
and quotes short lines from the show; Mr. Robot is a trademark of Universal Content
Productions. RF Sentinel is not affiliated with or endorsed by Ubisoft, USA Network
or NBCUniversal.

## License

Copyright © 2026 CIS-C0

RF Sentinel is free software: you can redistribute it and/or modify it under
the terms of the **GNU General Public License v3.0** as published by the Free
Software Foundation. It is distributed in the hope that it will be useful, but
**without any warranty**; see the [LICENSE](LICENSE) file for the full text.

Any modified version you distribute must also be released under GPL-3.0 with
its source code. Bundled third-party material keeps its own license: the Share
Tech Mono font (SIL Open Font License 1.1, `app/src/main/assets/licenses/`), the
Realtek 88xxau register tables in the USB WiFi driver (from Realtek's GPL-2.0 Linux
driver, via Wardrive Go; see `app/src/main/java/com/rfsentinel/app/usb/NOTICE.md`), and
the signature and Remote ID references credited above (Apache-2.0).

## Disclaimer

RF Sentinel is provided for awareness, research and personal privacy. Whether
passive Bluetooth and WiFi scanning is legal depends on where you are, so check
your local laws. A detection indicates that a device with a given signature is
nearby. It is not proof of who is present or what they are doing. This is not
legal advice.
