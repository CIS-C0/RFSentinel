<h1 align="center">RF Sentinel</h1>
<h3 align="center">BLE &amp; WiFi police scanner for Android</h3>

<p align="center">
  Passively detects body cameras, license-plate cameras, patrol vehicles, drones,
  trackers following you, camera glasses and fake cell tower signs from the
  signals they already broadcast. No root, no Termux, no account.
</p>

<p align="center">
  <b>RF Sentinel</b> (also written <b>RFSentinel</b>) is a free, open-source police detector app for Android.
  <a href="https://cis-c0.github.io/RFSentinel/what-is-rf-sentinel.html">What is RF Sentinel?</a>
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
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rfsentinel.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FCIS-C0%2FRFSentinel%22%2C%22author%22%3A%22CIS-C0%22%2C%22name%22%3A%22RF%20Sentinel%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22-release%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22includePrereleases%5C%22%3Afalse%7D%22%7D"><img src="docs/assets/badge_obtainium.png" height="56" alt="Get it on Obtainium" /></a>
</p>

<p align="center">
  <b><a href="https://cis-c0.github.io/RFSentinel/">Website</a> ·
  <a href="https://cis-c0.github.io/RFSentinel/qa.html">Q&amp;A</a> ·
  <a href="https://cis-c0.github.io/RFSentinel/how-it-works.html">How it works</a> ·
  <a href="https://discord.gg/NDTjn8HMGq">Discord</a></b>
</p>

<table align="center">
<tr>
<td align="center" width="50%"><img src="docs/screenshots/demo-walkthrough.gif" width="300" alt="RF Sentinel walkthrough: live list of flagged devices, why an Axon body camera is flagged, the radar, the map pane and the full-screen 3D driving map (DedSec theme, demo data)" /></td>
<td align="center" width="50%"><img src="docs/screenshots/mapbox-3d.gif" width="300" alt="Mapbox 3D map: list and map side by side while driving, then the full-screen 3D view with buildings and a 3D car turning with the road (Dark theme, demo data)" /></td>
</tr>
<tr>
<td align="center"><sub><b>Main features in 40 seconds</b><br/>live list · why it's flagged · radar · 3D driving map</sub></td>
<td align="center"><sub><b>3D driving view</b><br/>list + map split, then full screen (Mapbox map: Settings → Map → Map provider)</sub></td>
</tr>
</table>

<p align="center">
  <img src="docs/screenshots/main-fsociety.jpg" width="200" alt="Main screen with the live list and the map together (fsociety theme, demo data)" />
  &nbsp;
  <img src="docs/screenshots/details-night-drive.jpg" width="200" alt="Device details: why an Axon body camera is flagged, with evidence and sources (Night Drive theme, demo data)" />
  &nbsp;
  <img src="docs/screenshots/map-3d-synthwave.jpg" width="200" alt="Full-screen 3D map with the car, flagged devices and known cameras (Synthwave theme, demo data)" />
</p>
<p align="center">
  <img src="docs/screenshots/split-map-dark.jpg" width="200" alt="Split view: map above the live list (Dark theme, demo data)" />
  &nbsp;
  <img src="docs/screenshots/split-radar-dark.jpg" width="200" alt="Split view: radar above the live list (Dark theme, demo data)" />
</p>
<p align="center"><sub>List + map (fsociety) · why a device is flagged (Night Drive) · 3D map (Synthwave) · map and radar split views (Dark). Demo data only.<br/><a href="#themes">All 13 themes</a> · <a href="#android-auto">Android Auto</a></sub></p>

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
  - [Main screen](#main-screen) · [Device details](#device-details) · [Map and traces](#map-and-traces) · [Navigation](#navigation) · [Export](#export)
- [Android Auto](#android-auto)
- [External hardware](#external-hardware-optional)
  - [ESP32 boards](#esp32-boards)
  - [Supported USB WiFi adapters](#usb-wifi-adapter-monitor-mode)
  - [RTL-SDR: two-way radio detection](#rtl-sdr-two-way-radio-transmitting-nearby)
- [Sentinel Link: phones that share](#sentinel-link-phones-that-share)
  - [Over Meshtastic (LoRa)](#over-meshtastic-lora)
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
| **Runs on** | Android 8.0+ phones (64-bit and 32-bit ARM), Android Auto, plus optional ESP32 boards (OUI-Spy, GhostESP, Marauder, Flipper Zero + BFFB, FREE-WiLi 2) and USB WiFi adapters |
| **Costs** | Nothing. GPL-3.0, no ads, no account, no subscription, no analytics |

## What it detects

<table>
<tr>
<td align="center" width="25%"><b>Body cameras</b><br/><sub>Axon, Motorola, Digital Ally...</sub></td>
<td align="center" width="25%"><b>Plate readers</b><br/><sub>Flock Safety, Genetec, Raven audio</sub></td>
<td align="center" width="25%"><b>Patrol vehicles</b><br/><sub>police gear travelling together</sub></td>
<td align="center" width="25%"><b>Speed &amp; red-light cameras</b><br/><sub>warned ~30 s ahead</sub></td>
</tr>
<tr>
<td align="center"><b>Trackers following you</b><br/><sub>AirTag, Find Hub, SmartTag, Tile</sub></td>
<td align="center"><b>Drones</b><br/><sub>Remote ID with operator position</sub></td>
<td align="center"><b>Camera glasses</b><br/><sub>Ray-Ban Meta, Spectacles...</sub></td>
<td align="center"><b>Fake cell towers</b><br/><sub>IMSI-catcher signs</sub></td>
</tr>
<tr>
<td align="center"><b>Police aircraft</b><br/><sub>ADS-B, opt-in</sub></td>
<td align="center"><b>Waze reports</b><br/><sub>opt-in</sub></td>
<td align="center"><b>Card skimmers</b><br/><sub>gas pumps, ATMs</sub></td>
<td align="center"><b>Hacking tools &amp; deauth attacks</b><br/><sub>Flipper, Pineapple, evil twins</sub></td>
</tr>
</table>

<details>
<summary><b>Every category, with examples and how each one is identified</b></summary>

| Category | Examples | Identified by |
|---|---|---|
| **Body cameras** | Axon, Motorola / WatchGuard, Digital Ally, Zepcam, Vievu, Utility, Getac, in-car video | `BWCDEVICE` payload tag, TASER company ID `0x034D`, Axon UUIDs `0xFC81` / `0xFE6B` / `0xFE6C`, IEEE prefixes such as `00:25:DF` |
| **License-plate readers** | Flock Safety, Genetec, Perceptics, Quercus, Tattile | `Flock-` SSIDs, `FS Ext Battery` / `Penguin-` / `FS-` BLE names, company ID `0x09C8`, plus the OpenStreetMap / DeFlock camera map |
| **Speed & red-light cameras** | Fixed cameras mapped in OpenStreetMap | Offline map layer, warned ~30 s ahead with the mapped limit |
| **Audio sensors** | Flock Raven | 128-bit service UUIDs `0x3100`-`0x3500` |
| **Patrol vehicles** | Two or more kinds of police-type gear travelling together | Signal correlation between body cams, radios, in-car routers, printers, rugged laptops |
| **Public-safety gear** | P25 / TETRA radios, in-car cellular routers (Sierra Wireless, Cradlepoint, Peplink, Inseego), in-car printers (Zebra, Brother), police radar makers, vehicle upfit, breath / drug screening devices, cell-site simulator and forensic makers | Company IDs, UUIDs, IEEE prefixes, Zebra factory-serial names |
| **Trackers** | AirTag & Find My, Google Find Hub, Samsung SmartTag, Tile, Chipolo | Separated-from-owner frames and tracker service IDs, then follow detection |
| **Drones** | Any ASTM F3411 / FAA / EU Remote ID broadcaster, and the French *signalement électronique* | Decoded Remote ID over BLE, WiFi and Wi-Fi Aware (NAN, on phones that have it): serial, position, altitude, speed, operator / take-off location |
| **Camera glasses** | Ray-Ban / Oakley Meta, Snap Spectacles, Vuzix, HeyCyan-based glasses such as Nilox Smart AI Glasses | Company IDs `0x0D53`, `0x03C2`, `0x060C`, Meta UUIDs, the HeyCyan SDK UUID, name tokens |
| **Fake cell towers** | IMSI catchers / cell-site simulators | Test network codes, sudden 4G→2G, cloned or reserved cell identities, unexpected networks, a never-seen cell far stronger than the last while you stand still, an area code none of the neighbours share |
| **Police aircraft** *(off by default)* | Police, sheriff, state patrol, federal and RCMP / provincial police aircraft, and unlisted aircraft circling low overhead | Community ADS-B feeds (adsb.fi / adsb.lol), and / or ADS-B heard directly by an RTL-SDR (no internet), matched against ~1,400 law-enforcement airframes from the FAA and Transport Canada registries |
| **Waze reports** *(online, off by default)* | Police reported by Waze users, and any other kind you tick (accidents, hazards, road closures, traffic jams), each with its own level: sound and voice, notification only, or silent. Shows what Waze says it is (hiding, mobile speed camera...) | Two sources: your own OpenWeb Ninja API key, or *Waze direct* (opt-in: no key, free, sends Waze your IP address and a position rounded to a 1 km grid). You set the alert range (100 m to 10 km), the map range, and how often Waze is asked (15 s to 5 min direct, 2 to 10 min OpenWeb Ninja), slower when parked and faster on fast roads. Scored by distance, age and driver confirmations and re-scored every second as you move, so an alert fires the moment a report comes into range, not at the next check. Optional: only alert for reports on the road you're on, ahead of you (checked against the map's roads: a parallel road, a cross street, an overpass or a report behind you stays quiet; while you are guided to a destination the route decides: only reports on the part still ahead of you count, corners included; the ones that aren't on your way are left off the list unless you turn that off, and stay on the map), and spoken call-outs at 1 km, 500 m and 200 m. Third-party service, at your own risk |
| **Card skimmers** | Bluetooth modules inside gas-pump and ATM skimmers | HC-05 / HC-06 / HC-03 / RN42 / BT04-A names, serial-port service |
| **Hacking tools** *(off by default)* | Flipper Zero, Pwnagotchi, WiFi Pineapple, ESP deauthers, evil twin WiFi networks, Bluetooth spam floods, **WiFi deauth attacks** | Flipper UUIDs / company ID `0x0E29` / IEEE block, Pwnagotchi beacon JSON, default SSIDs, same network name from two makers with mismatched security, bursts of new pairing pop-ups; with a USB WiFi adapter, floods of unprotected deauthentication frames knocking devices off a network |
| **Emergency vehicles (V2X, Europe)** | Police, fire, ambulance and safety cars that broadcast European car-to-car radio (ITS-G5) | Needs an ESP32-C5 with the [V2X2MAP](https://github.com/pit711/V2X2MAP) firmware: the vehicle's own role, light bar and siren state and position (CAM), and its "emergency vehicle approaching" warnings (DENM). Ordinary cars are counted, not listed. Not in North America, which uses C-V2X |
| **Action & hidden cameras** *(off by default)* | GoPro, Insta360, DJI Osmo, YI, SJCAM, AKASO, Garmin VIRB, Ricoh THETA, Kodak PIXPRO, Sony action cams, Apeman, Campark...; WiFi spy cameras in setup mode (V380, LookCam Pro, TinyCam Pro, HDSmartIPC) | GoPro company ID `0x02F2` / UUIDs `0xFEA5` `0xFEA6`, Insta360 (Arashi Vision) `0x10D7` / `0xFC30`, IEEE blocks, names |
| **Network cameras** *(off by default)* | Arlo, Blink, Ezviz, Wyze, Ring, Verkada, Avigilon Alta, Axis, Hanwha, Bosch, FLIR, March Networks, GeoVision, Mobotix, Sunell, IDIS... | SSIDs and IEEE blocks |

</details>

Every match shows its evidence, its source and a confidence tier: **weak** (< 50),
**probable** (50-79) or **strong** (80+). Known cameras also raise the threat
headline as you get closer (strong within 50 m, fading out by 500 m). Full reference: [docs/SIGNATURES.md](docs/SIGNATURES.md).

## Features

Everything the app does, grouped by area.

<details>
<summary><b>Detection</b></summary>

- Payload, name, UUID and company-ID signatures plus IEEE vendor prefixes (MA-L / MA-M / MA-S), each with a cited source and a confidence
- **Evidence fusion:** independent matches on one device strengthen each other (noisy-OR, capped at 90)
- **Patrol-vehicle detection:** police-type gear whose signals rise and fall together is grouped as a possible police vehicle
- **Address-rotation linking:** keeps following a device when its Bluetooth address changes, including in Locate
- **Live read card:** while Locate follows a device or a flagged device is being pinpointed, the map and the floating map show its flag, signal bar, dBm, rough distance and whether you're getting closer, with a ✕ to close it
- **GPS dot:** the main screen's status shows red without a position, yellow when rougher than 20 m, green at 20 m or better
- **Pinpointing:** a flagged device you pass from more than one side is placed where it most likely is, from its signal at every spot you passed (multilateration), with a circle it's very likely in; a straight road that can't tell the side gets a circle covering both. GPS runs precise every second while a flagged device is in range (Settings → Location, on by default)
- **Follower alerts:** a tracker or flagged device that stays with you (default 10 min and 800 m, adjustable) raises *may be following you*, and so does one that keeps turning up - heard at 3 or more places along your way within 2 hours, even with gaps between
- **Remote ID drones** decoded live, with a *drone overhead* alert within 200 m
- Optional **cell tower change alert**: says so each time the phone moves to another serving tower (Settings; a change while parked is a classic fake-tower sign)
- **Fake cell tower signs** checked every 15 s, with persistence, call and border suppression (modelled on [EFF's Rayhunter](https://github.com/EFForg/rayhunter) as far as Android allows without root)
- **Hidden WiFi networks** identified from WPS data, Cisco AP names and vendor elements, and linked to the visible network on the same router
- Ordinary devices named precisely: exact AirPods / Beats model, device class, IEEE registrant; decoded Apple Continuity, iBeacon, Eddystone, Fast Pair
- Editable **watchlist** (exact addresses, prefixes of 24 to 44 bits picked with a slider, name, *name starts with*, vendor and Bluetooth advert-pattern rules; search, export and undo) with Global, Canada, US, France, UK, Portugal, Germany, Spain and Italy presets; per-category on/off switches
- Bluetooth 5 extended advertising and LE Coded (long range) where the phone supports it
- **I saw it:** on a device's page, tell the app what it is (e.g. pole camera on the bridge) and it learns that device - its exact address, and its Bluetooth advert pattern or WiFi probe fingerprint so it's recognised after its address changes. Patterns many devices share are refused, and a learned pattern only ever counts as probable
- **Probe-rate clue** (USB WiFi adapter): non-stop wildcard probing from one fixed address backs up a fixed-camera match, shown on the device page

</details>

<details>
<summary><b>Known cameras</b></summary>

- Plate-reader, speed and red-light cameras from OpenStreetMap appear on the map by themselves and are kept offline, refreshed weekly
- Warned as you approach one, even cellular-only cameras no radio can detect
- Pre-download everything within a radius you set (10-200 km), or every mapped Flock camera in the US and Canada (~143,000)
- Tap a camera to silence its alerts for good

</details>

<details>
<summary><b>Alerts</b></summary>

- Sound, vibration pattern per tier (1 / 2 / 3 pulses) and spoken announcements through the phone's own speech engine (prioritised, voice and speed pickers)
- **Radar-detector beeps** option: after an alert, beeps speed up as the flagged device gets closer
- **Short spoken alerts** option ("Body cam", "Police car", "Speed camera, 50")
- **Voice loudness** boost up to +15 dB (without distorting) for when music drowns the voice, in the car too, and an option to **pause music** while an alert is spoken
- Discreet mode, adjustable alert threshold, mute from the app or the car
- **Snooze like a radar detector:** tap the speaker at the top of the main screen to silence alerts for a while (5 to 60 minutes, set under Alerts), tap again for sound; long-press it for other lengths or to mute until you turn it back on. The minutes left show on the button
- **Floating threat bubble** over Waze, Google Maps or any app, with a pop-up card saying what was just detected
- **Alert card on the full screen map:** each alert (a flagged device, a known camera, a Waze report, a cell warning) pops up as a card under the turn banner - what it is, how far, how sure, "on your route" when it is - and goes by itself after a few seconds (longer for a strong match or something following you) or when dismissed; tap it to look at the place. Silent, honours Discreet mode and the alert snooze
- **Floating mini map** over other apps: the devices and known cameras around you, like the app's map (pinch to zoom, drag, resize)
- Android Auto alert cards say what it is, how sure, about how far and how many more are flagged ("BODY CAM · strong · ~40 m · near (-58 dBm) · +2 more flagged")
- Notification, Quick Settings tile and home-screen widget

</details>

<details>
<summary><b>Map &amp; history</b></summary>

- Vector map ([MapLibre](https://maplibre.org) with [OpenFreeMap](https://openfreemap.org), OpenStreetMap data; light and a readable night map with shops, landmarks, one-way arrows and highway shields; optional [Mapbox](https://www.mapbox.com) map) with your GPS trace and every device pinned where its signal peaked, flagged devices at their pinpointed spot with its circle (tap a spot to list everything heard there), the same filter chips as the list (including Waze, which draws reports as coloured dots by type), self-centering
- Optional **cell tower layer** (or the map's **Cells** chip): every tower seen while scanning, at the spot where its signal was strongest
- **Record traces** of your route and the devices along it, with the screen off (with the network names each device asked for, when a USB adapter or Marauder board is plugged in)
- **History map & timeline:** heatmap of where flagged equipment showed up, and when (hour of day, day of week)
- **Sightings map:** on a device's page, every spot that device was heard, joined in time order for each encounter
- The main screen's map and the full screen map show the same view (place, zoom, heading, tilt, following you) and the same place card and routes
- **Snapshots:** menu ⋮ → *Save snapshot* keeps the device list exactly as shown, to look at again later (History → *Snapshots*, beside the recorded traces), on the map, or exported
- **Device history:** every device the app remembers, in range or not, searchable by address (with or without colons), name, vendor, type, your own name for it or your note, filtered to favorites, flagged before or seen today; tap one for its details page. History is kept as long as you set in Settings → Data

</details>

<details>
<summary><b>Driving</b></summary>

- **Android Auto** app: threat headline, next camera ahead, recent alerts, filtered device lists, cell towers, hardware status, live map with cameras and drones, OpenStreetMap turn-by-turn navigation, alerts through the car speakers with Mute (snooze) / Ignore buttons
- **Starts by itself in the car** (your car's Bluetooth or Android Auto) and stops when you leave
- **Record a trace in the car** (Settings → Auto-start): a GPS trace from the moment the phone connects to the car until you leave, saved by itself
- Night Drive red-only theme; screen stays on while charging

</details>

<details>
<summary><b>Hardware</b></summary>

- ESP32 boards over USB OTG (OUI-Spy, GhostESP, Marauder incl. dual-band ESP32-C5 boards, or through a Flipper Zero) or Bluetooth (OUI-SPY App-Controlled) add their detections and extend range
- **USB WiFi adapters in monitor mode** over OTG, no root: RTL8811AU / RTL8821AU (e.g. ALFA AWUS036ACS, 2.4 GHz; 5 GHz experimental, Settings → External hardware), **RTL8812AU** (e.g. ALFA AWUS036ACH, 2.4 + 5 GHz, experimental), **RTL8812BU / RTL8822BU** (2.4 + 5 GHz, e.g. TP-Link Archer T3U, Wise Tiger AC1200), **RTL8814AU** (ALFA AWUS1900, 2.4 + 5 GHz), **MT7612U** (ALFA AWUS036ACM, 2.4 + 5 GHz), **MT7921AU** (ALFA AWUS036AXML / AXM, WiFi 6E: 2.4 + 5 + **6 GHz**), **RTL8187** (ALFA AWUS036H), **RT3070** (ALFA AWUS036NH / NEH) and, experimental, **AR9271** (ALFA AWUS036NHA). No Android scan limit, longer range, and **client devices** (laptops, phones, cameras connected to a network) the phone's WiFi scan can't see. Single-antenna dongles are detected and handled automatically
- **Requested networks:** the WiFi names nearby devices ask for (probe requests), from a USB adapter or a Marauder board, in an optional list; **watch** a name to get an alert whenever any device asks for it
- **Record frames to .pcap** for Wireshark (Settings → External hardware): every frame a USB WiFi adapter or V2X board hears, with its channel and signal
- The ALFA AWUS036ACS's light blinks with received traffic (Settings → External hardware switches it off, for the adapters whose light the app can control)
- RTL-SDR and HackRF transmissions show on the **radar** too, for a minute after they were last heard
- **More from every frame:** WPS maker / model / device name, a **probe fingerprint** that survives MAC randomization (watchable: "every device of this type"), and the real name of **hidden networks** when a device joins them

</details>

<details>
<summary><b>Interface</b></summary>

- Live list and radar view, filter chips with live counts (flagged, trackers, drones, favorites, new, Bluetooth, WiFi, cells, Waze, external hardware, and device kinds: vehicles, phones, computers, audio, cameras, routers, hotspots, TVs & consoles, smart home, Apple, other), sort and group by category, radio, maker or distance, search; Waze reports in range show nearest first with their exact distance and direction from you (ahead / behind / left / right while driving), and flash at the top once they reach your alert threshold; pull the list down (or use the menu) to check Waze right now, and the Waze status screen shows what is sent and when
- **Tools** (wrench button next to the List / Radar / Map toggle): **Cell towers** (serving and neighbour cells with network, IDs, channel and signal, plus every tower seen while scanning), **WiFi channels** and **WiFi spectrum** analyzers for 2.4 / 5 / 6 GHz
- **Cell towers in the live list:** the serving cell and its neighbours appear under *All*, after the devices
- **Scan watchdog:** a scanner that goes silent is restarted on its own
- **List time** (Settings → Scanning): how long a device stays on the list and radar after it was last heard, down to 5 s (WiFi only when scan throttling is off), with its own slider for devices heard only by external hardware
- **Location is off** warning: Android gives apps no Bluetooth or WiFi results while Location is off, so the main screen says so and opens the setting when tapped
- Device details: evidence, identity, decoded data, signal graph, **Locate** mode, history, raw advertisement
- Setup wizard (theme, region, alerts and voice, bubble, car start, camera warnings, cell alerts, camera radius, screen, permissions); Settings save themselves
- **Check for updates** (menu ⋮): compares with the latest GitHub release, only when you tap it
- **Screen rotation** (Settings → General): normal, auto-rotate **including upside down**, or always upside down with the USB-C port at the top for an antenna or adapter - even on phones like the Pixel that never auto-rotate upside down
- 13 themes, including the **DedSec** (Watch Dogs 2) and **fsociety** (Mr. Robot) fan themes
- **Theme maker:** build your own theme - an accent colour the whole palette grows from (the way Material You does), light, dark or pure black, a font (or your own .ttf / .otf), a banner picture, the animated home-screen header with your title, and your own map marker. Export a theme as a file; importing one lets you tick which parts to take (colours, font, banner, header, marker), into a new theme or into one of yours
- **Map marker:** an arrow, chevron or one of 13 cars and bikes in 16 colours and any size, in place of the plain dot

</details>

<details>
<summary><b>Sentinel Link</b></summary>

- **Phones that share:** link RF Sentinel phones near each other over Bluetooth with a 6-digit code; each one shows what the others detect (their external hardware too) on its list, radar and map, marked **VIA** with the phone's name, with full alerts. [How to](#sentinel-link-phones-that-share)
- **Over Meshtastic:** reach Sentinels kilometres away through a Meshtastic LoRa node paired with the phone; watchlist matches go first. Not yet tested on real hardware. [How to](#over-meshtastic-lora)

</details>

<details>
<summary><b>Data &amp; privacy</b></summary>

- Everything stays on the phone; export as CSV, JSON, KML, GPX or GeoJSON
- **Back up and restore settings** (Settings → Data): one file with your settings, watchlist rules, Home / Work / starred places, floating-window positions and custom themes; restore asks which of those to take. API keys and the "I accept the risk" choices are never in the file
- **Device history export and import** (Device history menu, or Settings → Data): export every remembered device, import a file from another phone or an earlier export - devices already known are merged, not duplicated
- **Report unknown device:** a privacy-safe signature (no full address, serials, location or times) to help add new equipment
- No accounts, no cloud, no analytics

</details>

## Quick start

Requires **Android 8.0 or newer** on an ARM phone, 64-bit or 32-bit (practically every Android phone; the release APK is about 44 MB).

1. On your phone, tap **Download the app** above, or open the [latest release](https://github.com/CIS-C0/RFSentinel/releases/latest).
2. Under **Assets**, tap the file ending in **`-release.apk`** (not the "Source code" archives).
   For a build with only free software (no Mapbox map; non-free USB WiFi firmware downloaded
   only if you agree), take the one ending in **`-foss.apk`** instead.
3. Open the downloaded file from the notification, or from **Files → Downloads**.
4. If Android blocks it, tap **Settings**, enable **Allow from this source**, go back and tap **Install**.
5. Open **RF Sentinel**. The setup wizard walks you through theme, region, alerts, camera radius and permissions.
6. Tap **Start** and grant location and *Nearby devices*.

**With Obtainium:** install [Obtainium](https://obtainium.imranr.dev/), then on the phone tap the
**Get it on Obtainium** badge above (or [this link for the FOSS APK](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.rfsentinel.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FCIS-C0%2FRFSentinel%22%2C%22author%22%3A%22CIS-C0%22%2C%22name%22%3A%22RF%20Sentinel%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22-foss%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22includePrereleases%5C%22%3Afalse%7D%22%7D)). It adds RF Sentinel
with the right APK already picked and updates it from GitHub Releases. You can also add
`https://github.com/CIS-C0/RFSentinel` by hand and set **APK filter** to `-release\.apk$` (or `-foss\.apk$`).

<p align="center"><img src="docs/screenshots/setup-theme.jpg" width="260" alt="Setup wizard: pick a theme" /></p>

**Updating:** *Check for updates* has an **Install now** button: it downloads the new release inside the app (checked against the size and SHA-256 GitHub lists for it, and against the app's own signing key) and hands it to Android's installer. Android asks you once to allow RF Sentinel to install apps, then shows its Update card. You can also install the newer APK over the existing one yourself, or let Obtainium do it. History and settings are kept.
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
- **Patrol vehicle:** at least two different roles whose RSSI correlates (Pearson r ≥ 0.6 over ≥ 8 s), or both parked and seen within 30 s, including a police-specific role or both a radio and a vehicle router (printers, breath screening and interface modules only add to a group); scored 24 + 12 × roles, +10 if moving together, cap 88.
- **Rotation linking:** same advert fingerprint (names, UUIDs, payload sizes, TX power, connectable) as exactly one device that went silent 1.5-30 s earlier.

Details: [How it works](https://cis-c0.github.io/RFSentinel/how-it-works.html) and [docs/SIGNATURES.md](docs/SIGNATURES.md).

## Using the app

### Main screen

A threat banner, the live list of everything in range, and the radar and map views.

<details>
<summary><b>Banner, list, views, filters and floating windows</b></summary>

- **Threat banner:** all clear, weak, probable or strong, or *may be following you*.
- **Live list:** every device in range (each leaves 30 s after it was last heard on Bluetooth, 60 s on WiFi, adjustable), with vendor, type,
  radio, signal, rough distance, and NEW / ★ / FOLLOWING badges. A green
  **INTERNAL** badge means the phone heard it; a blue **ESP32** badge means a
  connected board reported it. Flagged devices sort first and flash in their
  category colour.
- **List / Radar / Map:** turn on one view to fill the screen, or two or all
  three to see them together (stacked, side by side in landscape), with a handle
  between each to resize. The Map view is the full map with every button; its
  ⛶ button opens it on its own screen. On the radar, closer to the centre means
  a stronger signal; the angle is not a direction (a phone can't measure one).
- **Scrolled to the top**, the list stays there as alerts arrive; scrolled down,
  a *New alert* pill takes you up.
- **Filters and search:** All, Flagged, Trackers, Drones, Favorites, New,
  Bluetooth, WiFi, Cells, External (heard by an ESP32 board or a USB WiFi
  adapter), and by kind: Vehicles, Phones, Computers, Audio, Cameras, Routers,
  Hotspots, TVs & consoles, Smart home, Apple and Other (none of those). A
  device's kind comes from every clue: type, name, maker, WPS model and
  equipment-maker elements; a plain WiFi client stays under Other. Search
  matches name, address, vendor and type.
- **Sort by / Group by** (Menu): category (the default), radio, maker or
  distance, on the main list and in snapshots; alerts stay on top.
- **Tap** a device for details. **Long-press** for watchlist, whitelist,
  favorite, copy, ignore, and **Report unknown device**.
- **Whitelist** (Menu): trusted devices by address, and WiFi networks by name
  (*Name starts with*, *Name contains* or *Exact name*), for a router that is on
  every bus of a fleet. Trusted devices are never flagged, logged or alerted on.
- **Floating bubble** (Settings, needs *Display over other apps*): green when
  clear, orange for probable, red for strong or a follower, with the count.
  Each new alert shows a small card beside it for a few seconds (what, type, distance).
- **Floating mini map** (Settings, same permission): the devices and known
  cameras around you over Waze, Google Maps or any app, centred on you, with a
  threat-colour border. Pinch to zoom, drag to move, the corner handle resizes
  it, tap to open the full map.
- **In the car** (Settings → *Start scanning automatically in the car*): pick
  your car's Bluetooth; scanning starts when the phone connects to it or to
  Android Auto, and stops when you leave if the car started it.

</details>

### Device details

The app never connects to a device. The details screen shows why it's flagged
(each signature with its evidence and source), identity and how it was
determined, address type and trackability, decoded protocol data, WiFi band,
channel and security, drone Remote ID with drone and operator positions, a live
signal graph with **Locate** mode (beeps faster as you get closer; it keeps going while you
look at the map, where a live read card shows the signal, rough distance and trend), history, and
the raw advertisement, plus its **Category**. **Rename** gives the device your own name (shown in the
list, on the map and in history) and **Add note** keeps a note with it; named or
noted devices stay in the history. **I saw it** is different: it doesn't just label
this device, it adds it to your watchlist with what you say it is (e.g. pole camera
on the bridge): its exact address and / or its Bluetooth advert pattern or WiFi
probe fingerprint, so you're alerted the next time it's around, even under a new
random address.

### Map and traces

Your position, recorded traces, the devices heard around you and the known cameras.

<details>
<summary><b>Map views, cell towers, cameras, traces and crash log</b></summary>

- The **map** shows your position, the trace being recorded, and every device
  heard around you, placed where your phone was when its signal was strongest.
  That's an approximation, not a fix. Each dot stays pinned to its spot as you
  pan and zoom; tap a spot where several devices were heard to pick one from a list.
- **North up / heading up / 3D:** the compass button steps through north up,
  heading up (turns with your direction of travel while moving), and a **3D driving
  view** - tilted in perspective with your position low on the screen, like a car
  navigation app. The sun / moon button switches the map between light and dark, the gear
  opens the map settings. **Settings → Map** sets the **default zoom** (17 is close to Waze;
  the 3D view pulls back from it as you speed up), the **3D view angle** (30° to 60°) and the
  **map provider**: OpenFreeMap (free, no account) or Mapbox. The floating mini map always
  uses OpenFreeMap.
- **Cell towers** (Settings → *Map* → *Show cell towers*): towers seen while scanning,
  at the spot where your phone heard them strongest - an estimate, not the
  tower's real position (looking that up online would reveal where you are).
- **Known cameras** (Settings → *Known cameras*): *Download cameras around me* fetches plate readers
  within your radius (10-200 km, 100 by default); *US & Canada plate cameras* fetches every
  plate reader mapped in the US and Canada (~18 MB, refreshed weekly on WiFi).
  Both use [DeFlock](https://deflock.org)'s hourly snapshot of OpenStreetMap,
  plus cameras it leaves out, straight from OpenStreetMap. Where the direction is mapped,
  a cone in the camera's colour shows which way it faces when you zoom in, and a camera
  facing across your way (watching a cross street) doesn't warn. Tap a camera →
  *Ignore alerts* to never be warned about it again, or *Set direction* when its
  direction isn't mapped (kept on your phone only).
- **CCTV cameras** (Settings → *Known cameras* → *Show CCTV cameras*, off by default):
  ordinary surveillance cameras mapped in OpenStreetMap - city street cameras,
  shop and building cameras. Everything within your camera download radius is
  fetched in the background (also by *Download cameras around me*); they show from
  city zoom, with a shaded view cone where the direction is mapped when you zoom in.
  Public ones by default; *Include private and indoor CCTV* adds the rest. Map only
  (no alerts). Most are wired, so no scanner can detect them; this shows the ones
  volunteers have mapped (the same data as [Surveillance under Surveillance](https://sunders.uber.space/)).
- **Record trace** saves your route and every device heard along it, with the
  screen off, and can start with each scan.
- **Screen:** *always on*, *on while charging* (default) or *normal*.
- **Crash log:** if the app ever closes unexpectedly, what went wrong is saved on the
  phone (never sent automatically); it offers to send it next time, or Settings →
  *Lists, tools & about* → *Export crash log*.

</details>

### Navigation

Turn-by-turn directions on the map, with your scanner running alongside.

<details>
<summary><b>Search, routes round cameras, guidance and which servers see what</b></summary>

- **Where to?** The magnifier button on the map searches places and addresses as you type: your own
  places that fit show first, results say what kind of place they are ("Park · Munich"), the best name match
  comes before one that merely mentions the words, and a pair of coordinates works too. Pressing the keyboard's
  search key (or the *Search for* row) looks in more places at once. Empty, it lists **Home**, **Work**, starred and recent places (all kept on the phone) and kinds of
  place to look for - petrol, charging, food, parking, shops, pharmacy, hospital, cash, toilets, rest stops -
  **near you**, and **along your route** while you are guided (a stop to add, in the order you reach them).
  A **long press** on the map drops a pin: it names what's there, offers *Directions*, and can be starred.
  Starred places can be renamed with the pencil beside their star (in search and on the place card).
- **Routes:** up to three, each with its time and the main roads it takes, drawn on the map.
  *Avoid tolls / highways / ferries* ticked while you plan (or on the trip menu) is remembered for the next
  destination, and is the same setting as Settings → *Navigation*.
- **Round cameras:** *Avoid speed cameras* and *Avoid plate readers* (the same chips, trip menu and Settings →
  *Navigation* switches) plan the route around the known cameras of the camera map. The route is asked for, the
  cameras on it are found, the road each one is on is left out and the route is asked for again (a few times)
  until none is on it or there is no way round. Each route says what is left on it ("no plate readers", "2 speed
  cameras") and the fastest way stays on offer to compare. A camera at the start or end, or on the only bridge,
  can't be avoided and is counted. The cameras along the route are fetched first when they are not on the phone
  yet (Settings → *Known cameras* → *Download automatically*, or download them there beforehand); it also works
  when you are re-routed and in Android Auto. A detour can be longer.
  **Start** begins guidance - and the scan too, unless you turn that off (Settings → *Navigation*);
  a scan already running is left as it is, and it keeps going after you arrive.
- **Guidance:** a banner with the next turn, its distance and street, the **lane arrows** where the map
  has them, and the turn after it when it follows closely; an arrival bar with your arrival
  time, time and distance left; *whole route* view; mute; *add a stop*. It follows your north-up /
  heading-up / 3D choice, zooms in as a turn comes up, keeps the screen on, and carries on with the
  screen off (a notification shows the next turn). Roundabouts name the exit ("2nd exit"). On the main
  screen's small map pane there is a slim one-line banner; tap it for the full screen map.
- **Arrival time** starts from the route's usual driving time and then follows how you really drive
  (stops at lights, a slow road), so it is closer than a fixed figure without any traffic service.
- **Night map:** after sunset, worked out on the phone from your position and the time, the map turns to its
  night look while you are guided (Settings → *Navigation*; the sun / moon button keeps the look you choose).
- **Spoken directions** ("In 500 meters, keep right", then "Keep right") come as navigation audio, so music
  dips under them as in Maps, in the voice, speed and loudness chosen under Alerts. A "something is following you"
  alert cuts in on a direction; the last prompt before a turn cuts in on other alerts; otherwise they take turns.
  Miles or kilometres by your region, or your choice.
- **Re-routes** by itself when you leave the route or turn round, and ends when you arrive. *Open in...*
  hands the destination to Google Maps, Waze or any map app.
- Not included: live traffic (the arrival time is the route's usual driving time), speed limits and offline routes.
- Routes come from [Valhalla](https://valhalla.github.io/valhalla/) on the OpenStreetMap Germany server and
  places from [Photon](https://photon.komoot.io) as you type, and from OpenStreetMap's own
  [Nominatim](https://nominatim.org) as well when you submit a search (the keyboard's search key, or the
  *Search for* row) - whichever answers first is shown at once. They see what you search for, where you start,
  stop and finish, and your IP address - never what you scan - and only when you search, long-press the map
  or start a route. Search sends your position rounded to about 1 km. Avoiding cameras adds the places on your
  route where known cameras are (the road of each, nothing else about them) to the route question. Looking for petrol, food and the like asks OpenStreetMap's
  Overpass servers (the same ones as the camera downloads) for that kind of place around you - your position
  rounded to about 1 km, or the road ahead as points rounded to about 200 m.

</details>

### Export

Menu → **Export...** (or Settings → Data → Export all devices):

| Export | Formats |
|---|---|
| Current scan (all devices) | CSV, JSON, KML |
| All devices ever seen | CSV, JSON |
| Match log (Menu → History → *Match history*, searchable, filter by today or kind) | CSV, JSON, GPX, KML |
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

<details>
<summary><b>Car screens, alerts and navigation</b></summary>

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
- **More:** snooze alerts (the length set under Alerts), spoken alerts in the car, short
  spoken alerts, whether AirTags count as trackers, **cell towers** (fake-cell
  check status, serving cell and neighbours) and **hardware** status (phone
  radios and the scan watchdog, ESP32 / Marauder / Flipper on USB, OUI-SPY over
  Bluetooth, USB WiFi adapter).
- **Live map:** RF Sentinel's own OpenStreetMap map on the car screen with
  devices, known plate / speed / red-light cameras and your position. Drag,
  pinch or **+ / −**; **◎** follows the car again. Needs car API level 7; older
  Android Auto gets car-drawn maps with device and camera lists.
- **Navigation:** *Navigate to...* searches with [Photon](https://photon.komoot.io) and
  [Nominatim](https://nominatim.org) (on submit only), starts from Home, Work, starred and recent places kept on the phone,
  routes with [Valhalla](https://valhalla.github.io/valhalla/), speaks turns and re-routes.
- **Alerts** appear as heads-up cards with **Mute N min** (the snooze length) and **Ignore**
  buttons, and play through the car speakers as navigation audio, briefly
  lowering your music.
- Screens only redraw when what they show has changed, and a screen that fails
  to build shows a short message instead of closing the app.

</details>

**In a real car, Android Auto only shows apps like this one when they come
from a trusted store.**

<details>
<summary><b>Using the GitHub APK in a real car</b></summary>

Google lets apps built with its Car App Library (as
RF Sentinel is) run in a real car only when they're installed from Google Play
or ONE store, and Android Auto's **Unknown sources** developer setting doesn't
cover them ([Google's testing guide](https://developer.android.com/training/cars/testing#real-vehicles)).
The GitHub APK's car screens therefore run only on Google's
[Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
emulator.

**The GitHub APK still works in a real car**, through the two app types that
setting does cover. Android Auto settings → tap **Version** ten times →
**⋮ → Developer settings** → enable **Unknown sources**, then reconnect:

- **RF Sentinel screen (media-style):** tabs for **Threats** (police aircraft overhead, from the
  online feeds or your RTL-SDR, then flagged devices), **Cameras**
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

</details>

## External hardware (optional)

RF Sentinel works with the phone alone. These add-ons extend what it can hear.

### ESP32 boards

Plug an ESP32 board into the phone with a USB OTG cable while scanning (Android
offers RF Sentinel when it's plugged in; tick **Always** and it never asks again),
or pair one over Bluetooth. SDR dongles and USB WiFi adapters work the same way. What the board reports
merges into the list with a blue **ESP32** badge.

| Firmware | Link | What RF Sentinel gets |
|---|---|---|
| [OUI-Spy](https://github.com/colonelpanichacks/oui-spy-unified-blue) (recommended) | USB | Flock-You, Detector and Sky Spy detections as they happen: Flock cameras, the board's watchlist matches, Remote ID drones with position |
| [OUI-SPY App-Controlled](https://github.com/lukeswitz/oui-spy-unified-blue) | Bluetooth | Flock-BLE, Flock-WiFi, Sky Spy, Detector and (optional) Wardrive survey engines |
| [GhostESP](https://github.com/GhostESP-Revival/GhostESP) | USB | The WiFi networks it scans, about every 10 s |
| [FREE-WiLi 2](https://freewili.com) | USB | The WiFi networks its ESP32-C5 scans from its console's WiFi menu (2.4 + 5 GHz) |
| [ESP32 Marauder](https://github.com/justcallmekoko/ESP32Marauder) | USB, or through a Flipper Zero | WiFi networks and client devices from its passive beacon and probe sniffers, batched every 2 s; on ESP32-C5 boards (Marauder v8, BFFB v2, LilyGo T-Dongle C5, C5 DevKit) both 2.4 and 5 GHz |

<details>
<summary><b>Supported boards, Marauder, Flipper Zero and OUI-SPY setup</b></summary>

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

**OUI-SPY over Bluetooth:** with the board on, open **Settings → Pair OUI-SPY
board**: it looks for the board's Bluetooth service for a few seconds (scanning
needn't be running) and lists `OUI-SPY-xxxx`. If none is found, force-stop the
OUI-SPY app (App info → Force stop) and unplug the board for a few seconds: it
doesn't advertise while another app is connected to it. Pair it only there, not in
Android's Bluetooth settings: the board keeps no pairing keys, so a phone-side
pairing breaks the link (the app tells you to tap *Forget* if it finds one).
While scanning, RF Sentinel switches on the board's Flock-BLE, Flock-WiFi, Sky
Spy and Detector engines, and by default its Wardrive survey engine so the board
relays **every** WiFi network and Bluetooth device for RF Sentinel's own rules.
In that mode the board sends standard WiFi probe requests; turn **Settings →
OUI-SPY: relay every network and Bluetooth device** off to keep it listening
only. **Settings → OUI-SPY engines** chooses which detection engines run (turn
the two Flock ones off where there are no Flock cameras). With **Phone Bluetooth
scan with OUI-SPY** off, the phone's own Bluetooth scan pauses while the board is
live and the board does the Bluetooth scanning (it hears farther); it comes back
if the board drops out. Keep relaying on with it, or only the board's engine
matches come in. The board's UniPwn, PCAP and Foxhunter engines are never used.

</details>

### USB WiFi adapter (monitor mode)

Plug a supported USB WiFi adapter into the phone with an OTG cable while
scanning and allow USB access on the prompt. RF Sentinel drives it directly
over Android's USB host API (no root, no kernel driver), puts it in
**receive-only monitor mode** and hops the channels (2.4 GHz on 88xxau, RTL8187,
RT3070 and AR9271; 2.4 GHz and 5 GHz on 88x2bu, 8814au and MT7612U; 2.4, 5 and 6 GHz on the
MT7921AU, 6 GHz on its 15 preferred scanning channels, where access points beacon). Access points
(beacons, probe responses) and client devices (probe requests, data frames)
go through RF Sentinel's own rules like everything else, with a purple
**USB WIFI** badge.

| Chip | Example adapters | Status |
|---|---|---|
| Realtek RTL8811AU / RTL8821AU (`0bda:0811`) | ALFA AWUS036ACS | Supported (2.4 GHz; 5 GHz experimental, opt-in) |
| Realtek RTL8812AU (`0bda:8812`, `0bda:881a` and ~30 other IDs) | ALFA AWUS036ACH, TP-Link Archer T4U / T4UH, Netgear A6200v2, Edimax | Experimental (2.4 + 5 GHz): written from the Realtek driver, not yet tested on a real adapter |
| Realtek RTL8812BU / RTL8822BU (`0bda:b812`, `0bda:b82c` and ~35 other IDs) | TP-Link Archer T3U, ASUS USB-AC53/AC55/AC58, Edimax EW-7822U*, Wise Tiger AC1200 | Supported (2.4 + 5 GHz), tested on a Wise Tiger 8812BU |
| Realtek RTL8814AU (`0bda:8813` and 13 other IDs) | ALFA AWUS1900, TP-Link Archer T9UH, ASUS USB-AC68, Netgear A7000 | Supported (2.4 + 5 GHz), not yet tested on hardware |
| MediaTek MT7612U / MT7632U (`0e8d:7612` and 16 other IDs) | ALFA AWUS036ACM, ASUS USB-AC55, Netgear A6210 | Supported (2.4 + 5 GHz), tested on an ALFA AWUS036ACM |
| MediaTek MT7921AU (`0e8d:7961` and 4 other IDs) | ALFA AWUS036AXML / AXM, Comfast CF-952AX, Netgear A8000 / A7500, TP-Link TXE50UH | Supported (2.4 + 5 + 6 GHz), tested on an ALFA AWUS036AXML / AXM |
| Realtek RTL8187L / RTL8187B (`0bda:8187` and 20 other IDs) | ALFA AWUS036H, Netgear WG111v2/v3 | Supported (2.4 GHz), tested on an ALFA AWUS036H |
| Ralink RT3070 (`148f:3070` and 4 other IDs) | ALFA AWUS036NH / AWUS036NEH | Supported (2.4 GHz), tested on an ALFA AWUS036NEH |
| Atheros AR9271 (`0cf3:9271` and 17 other IDs) | ALFA AWUS036NHA, TP-Link TL-WN722N v1 | Experimental (2.4 GHz) |
| RTL8811CU / 8821CU, RT5370, MT7610U, AR7010 | | Detected and named, not supported yet |

<details>
<summary><b>Receive-only, the adapter log and single-antenna dongles</b></summary>

The adapter never transmits (on the MT7612U and RT3070 even the automatic ACK
replies are switched off).
Settings → External hardware shows its status, and **Export USB adapter log** saves a text
file (app and phone version, the USB devices plugged in, the driver log) to
send to the developer - no nearby devices' addresses or network names are in it.

On a single-antenna 8812BU dongle the antenna sits on one of the chip's two
receive paths, while the chip hears 2.4 GHz beacons on path A only. RF Sentinel
compares both paths on the first seconds of traffic and moves 2.4 GHz reception
to the one with the antenna (about 30 dB better on the dongle we tested - more
than Realtek's own Windows driver gets from it).

</details>

### RTL-SDR: two-way radio transmitting nearby

**ADS-B aircraft receiver** (Settings → *RTL-SDR radio*, off by default): the dongle
listens on 1090 MHz instead of sweeping the radio bands and decodes the aircraft
around you itself - position, altitude, speed, call sign - with no internet. They go
through the same police-aircraft checks as the online feeds and show on the map and
in Android Auto; a registered police aircraft heard without a position is reported as
in radio range. Needs *Police aircraft* on; its *Online aircraft feeds* switch can then
be turned off so nothing leaves the phone. A 1090 MHz antenna helps.

Plug an RTL-SDR dongle (R820T / R820T2 / R860 tuner, e.g. RTL-SDR Blog V3, or the
RTL-SDR Blog V4) or a **HackRF One** into the phone with an OTG cable while scanning.
The HackRF is only ever used to receive: its transmitter, RF amplifier and antenna-port
power stay off. RF Sentinel
sweeps the North American public-safety radio bands - the 700 and 800 MHz bands
where police, fire and EMS radios (not towers) transmit, and the shared VHF / UHF
land-mobile bands - and measures only **how much energy each 12.5 kHz channel
carries**. Nothing is demodulated, decoded, recorded or decrypted: it can't know
what was said or who said it.

<details>
<summary><b>How a nearby transmitter is told apart, settings and requested networks</b></summary>

It first learns which channels are busy all the time where you are (towers,
repeaters, trunking control channels, pagers) and ignores them; a strong burst on
a normally quiet channel means a radio is transmitting close by, and raises a
**Two-way radio transmitting nearby** alert (category *RADIO*, can be switched off
in Settings). On 700 / 800 MHz that's a probable sign of a public-safety radio; on
VHF / UHF, which businesses, schools and transit share, only a very strong signal
counts, and only as a weak sign. Consumer walkie-talkies (FRS / GMRS, MURS),
marine, railroad, weather and paging channels are skipped. The dongle's log is in
the exported USB adapter log.

**TETRA radios** (on by default when the phone's region is in Europe, a switch in
Settings): emergency services in Europe and many other countries use TETRA, encrypted
digital radio. Its handsets transmit on 380-385 MHz in short time slots - a ~14 ms
burst every ~57 ms - and RF Sentinel watches that band and looks for that rhythm, so
a **TETRA radio transmitting nearby** alert means a handset or car radio, not a mast
(base stations send on 390-395 MHz, which is never watched). Energy and timing only:
TETRA isn't decoded, and its encryption isn't touched.

**Cellular transmitters** (optional, off by default): it can also watch the phone /
modem side of the in-range LTE bands (600, 700 including Band 14 - the public-safety
broadband block FirstNet uses in the US - 850 and part of 1700 MHz) for a transmitter
close to you: a vehicle modem, a Cradlepoint-style router, a camera with a SIM. Energy
only, nothing decoded or identified, and towers (which use the other half of each band)
are never watched. A phone counts too, so it's a weak sign on its own; it matters when
it travels with you or lines up with a device flagged over WiFi / Bluetooth.

Hits show in the **main list** (the *Radio* filter, or under *All*): frequency,
its name when known, signal above the noise, and whether it's **getting closer or
moving away**. The dongle's status shows on the main screen next to the other
hardware. **Settings → RTL-SDR radio**:

- **Sensitivity** (how far above the noise a burst must be, down to 6 dB to test how far a radio carries), the **built-in bands**
  on / off, **your own bands**, **ranges never to report** (e.g. a business band)
  and **frequencies to watch** (reported whenever they're active), one per line in MHz.
- **Radio waterfall** (Tools → *Radio waterfall*): a live picture of the watched
  bands, one row per sweep, while detection carries on. Tap a signal (or **Tune**) to
  zoom in on about 2 MHz around it in real time; radio detection pauses until **Back
  to sweep** or you leave the screen. Receive-only, energy only.
- On a radio signal's page, **Locate** opens the waterfall tuned to its frequency (follow
  the strongest signal) and **Watch this frequency** adds it to the frequencies to watch.
- **Say the frequency** (and its name) instead of "Radio transmitting nearby", and
  a **closer / moving away** call-out when the signal changes by the set number of dB.
- **Frequency names**: import a CSV (RadioReference's export, CHIRP, or any file with
  a frequency and a name column), and / or let **RadioReference** name the FCC
  licences near you (US; your own RadioReference Premium login and developer key,
  stored encrypted; your position rounded to ~1 km is sent, at most once a day per
  area). A hit takes the closest name within the set ± kHz.

**Requested networks** (Menu → History → *Requested networks*): phones and laptops ask by
name for networks they joined before. With an adapter or a Marauder board
plugged in, RF Sentinel can keep these names (off by default: they're other
people's network names; always kept while a map trace records). Tap **Watch** on
one to get an alert whenever any device asks for it, or **search WiGLE** for
where those networks are (map pins, a *By device* view, and where each was asked for;
needs your own WiGLE API name and token, stored encrypted; only the searched name and, for
a search near you, your position rounded to ~10 km are sent). Devices also show their
**WPS** maker / model / name, a **probe fingerprint** you can watch ("every
device of this type", even with random addresses) and its coarser **probe shape**
(FlipDeFlock's hash, which can be watched too), and hidden networks show
their real name once a device joins them. Built in: Axon Fleet in-car network
names ("Axon12-5g", vehicle number + "-5g") and Cradlepoint vehicle routers on
their default names.

</details>

## Sentinel Link: phones that share

Link two or more RF Sentinel phones near each other: each one shows the
same devices on its list, radar and map, whichever phone heard them first,
including what any phone's ESP32 board, USB WiFi adapter or RTL-SDR picks up.
A device another phone heard is marked **VIA** with that phone's name, and its
alerts say *via NAME*. The **Sentinels** filter chip shows only those.

**Link the phones (once):**

1. On both phones: Settings → **Sentinel Link** → *Open Sentinel Link*, and
   give each phone a name (*This phone's name*).
2. Keep the phones close. Tap **Link up a Sentinel** on one and **Join a
   Sentinel** on the other, and allow *Nearby devices* if asked.
3. Both phones show a 6-digit code. If the codes are the same, tap **Confirm**
   on both. Different codes: tap *Cancel* (another phone answered).
4. To add a third phone, tap *Link up a Sentinel* on any phone of the group
   and *Join a Sentinel* on the new one.

**Then:** linked phones share while they scan, whenever they are in Bluetooth
range of each other (about 10 to 50 m, more in the open); they find each other
again by themselves. *Share with linked Sentinels* turns sharing off without
leaving; *Leave the group* forgets it. The list of Sentinels shows each one as
*Linked now* or when it was last heard.

**Privacy:** the link is encrypted (a key agreed with the 6-digit code, AES-GCM
for every message), and the phones' Bluetooth adverts carry only a tag that
changes over time and means nothing without the group's key. Nothing goes
through the internet.

### Over Meshtastic (LoRa)

> **Not yet tested on real hardware.** The Meshtastic part follows Meshtastic's
> phone API and passes its tests, but hasn't been tried with a real node yet.
> Reports welcome.

Phones too far apart for Bluetooth can share through
[Meshtastic](https://meshtastic.org) nodes: LoRa boards with the official
Meshtastic firmware, which reach kilometres. Each phone uses its own node,
paired with it over Bluetooth.

**What you need:** a LoRa board running Meshtastic per phone, for example a
Heltec V3, a LilyGO T-Beam, or a Seeed XIAO ESP32S3 with its Wio-SX1262 LoRa
board (a plain ESP32 has no LoRa radio). Flash it at
[flasher.meshtastic.org](https://flasher.meshtastic.org) and set its **LoRa
region** once in the Meshtastic app. Fit the antenna before powering it.

**Set up:**

1. Link the phones with Sentinel Link first (above): the mesh uses the group's key.
2. Disconnect the Meshtastic app from the node (a node serves one app at a time).
3. In Sentinel Link → **Meshtastic node**, turn on *Use a Meshtastic node* and
   pick yours from *Choose a node*. If Android asks for a PIN, it's on the
   node's screen, or **123456** for boards without a screen.
4. Start a scan. The status line goes to *Meshtastic node · connected*.

RF Sentinel adds a private channel named **RFSentinel** to a free channel slot
of the node (or the slot you choose; never the primary channel, which is often a
city's public one). **Hops** (1 by default) sets how many times other nodes pass a
packet on, so a city mesh isn't flooded; its key is made from the group's key, so every phone of the group
gets the same channel and only your group can read it. Each packet is also
encrypted and signed with the group's key. If every slot is in use, free one in
the Meshtastic app.

LoRa is slow, so not everything fits. Each packet carries the phone's position
once and a few devices: **watchlist matches first** (sent again every minute
while in range, and a new one goes out within about 20 s), then flagged devices, then
every other device. The slider sets how often a packet goes out (15 s to 5 min,
1 minute by default). Devices you whitelisted are never sent. Radio and drone
detections, and full device details, stay on Bluetooth links.

## Themes

Pick a theme in the setup wizard or under **Settings → Appearance**.

Your own themes sit under the built-in ones: **Create a custom theme**, long-press one to edit it, or **Import a theme file**.

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

- **Receive-only.** Nothing is transmitted to, jammed, spoofed or connected to.
  The only thing it ever sends is Sentinel Link's encrypted messages to your own
  linked phones and Meshtastic node, and only when you set it up.
- **No accounts, no cloud, no analytics.** Detections live in a local database
  excluded from cloud backup.
- **Data leaves the phone only when you export or share it.**
- **Optional permissions:** *Nearby devices → connect* only to list your paired
  Bluetooth devices when you pick your car; *Display over other apps* only for
  the floating bubble. No contacts, storage, camera or microphone access.

<details>
<summary><b>Network use: which servers see what</b></summary>

- **Network use is limited to map data:** the map (vector tiles, styles and fonts from
  OpenFreeMap, OpenStreetMap data; or from Mapbox if you choose it in Settings → Map,
  with Mapbox's own usage statistics switched off) while the map is open, the road tiles
  of your area and direction while *Only alert for reports ahead* is on (OpenFreeMap,
  kept on the phone), and the known-camera list for the area you look at, fetched once per
  area and refreshed weekly from overpass-api.de (or the mirrors
  overpass.kumi.systems and overpass.private.coffee). Camera downloads also
  fetch DeFlock's region files from cdn.deflock.me. These servers see your IP
  address and the area, never your scans. Turn off *Show known cameras
  automatically* to fetch only when you tap a download. Navigation, on the phone
  or in Android Auto, uses Photon and Nominatim (place search) and Valhalla (routes) only when you
  search, long-press the map or start a route; they see the search, your start and
  destination, and your IP address.
  Map data © OpenStreetMap contributors (ODbL), © OpenMapTiles, © Mapbox when chosen.
- **Two optional online sources, off by default:** *Police / government
  aircraft* sends a position rounded to about 1 km to the community ADS-B
  feeds adsb.fi / adsb.lol every minute while scanning. *Waze reports* use
  either your own OpenWeb Ninja API key (stored encrypted on the phone; a box
  around you at the interval you pick, 4 minutes by default) or, if you choose
  it in Settings, *Waze direct*: an anonymous Waze account made on the phone
  (stored encrypted), presented to Waze as the Waze app, that sends Waze (a
  Google service) your IP address and your position rounded to a 1 km grid, so
  never closer than about 500 m, at the interval you pick (every minute by
  default). Direct's protocol layer is vendored from
  highway-radar-sabre-plus (MIT), see
  `app/src/main/java/com/rfsentinel/app/online/wazert/LICENSE`. OpenWeb Ninja and
  Waze are third-party services RF Sentinel isn't affiliated with and doesn't
  endorse; using them is at your own risk and under their terms.

</details>

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

The most common questions. More are linked below.

<details>
<summary><b>How do I detect police with my Android phone?</b></summary>

Install RF Sentinel, tap **Start scanning**, and grant location and *Nearby
devices*. It matches the Bluetooth LE and WiFi signals of body cameras, radios,
in-car routers, printers and plate readers, and groups gear that moves together
into a possible police vehicle. [More](https://cis-c0.github.io/RFSentinel/police-detector.html)

</details>

<details>
<summary><b>Why does the scan find 0 devices?</b></summary>

Location is probably off. Android gives apps no Bluetooth or WiFi scan results
while the phone's Location is off, even with every permission granted. Turn it
on in the quick settings (the main screen says so and opens the setting when
you tap the warning), and check that RF Sentinel has location and *Nearby
devices*.

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
./gradlew assembleFossDebug
```

```bash
./gradlew testDebugUnitTest lintDebug
```

There are two flavors. **foss** contains only free software: no Mapbox SDK, the map is
always OpenFreeMap. **full** adds the optional Mapbox map (`./gradlew assembleFullDebug`).
Both have the same app ID, so either one replaces the other.
A build of only foss tasks (or `-Prfs.foss=true`) leaves the full flavor out and never
declares or contacts Mapbox's Maven server. The foss flavor also leaves out the non-free
firmware of the RT3070, MT7612U, MT7921AU and RTL8812BU / 8822BU adapters: when one is
plugged in, the app asks before downloading those exact files once from
[linux-firmware](https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git)
(pinned commit, checked against their SHA-256; see `usb/Firmware.kt`).

In the full flavor, Mapbox is optional: with `MAPBOX_ACCESS_TOKEN=pk...` (your own public token) in
`local.properties` (git-ignored) the build offers the Mapbox map; without it the app
builds and runs on OpenFreeMap only. The Mapbox SDK downloads from Mapbox's public
Maven repository without an account.

The debug APKs are written to `app/build/outputs/apk/<flavor>/debug/`. `tools/gen_assets.py`
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

`./gradlew assembleRelease` builds both flavors and signs the APKs using `keystore.properties` in the
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
- **droneID_FR** (github.com/khancyr/droneID_FR): French electronic drone ID beacon layout
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
Thank you to every project credited below.

<details>
<summary><b>Ported code, data sources, fonts and trademarks</b></summary>

Some feature ideas come from SØPHIA, [BLE Radar (MetaRadar)](https://github.com/BLE-Research-Group/MetaRadar)
(trackability from what was seen) and ESPsoup. No code from those projects is included.
[Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod inspired the antenna
check, the raw-GPS backstop and Bluetooth-scan restart, the USB adapters' empty-transfer
back-off and "not calibrated" warning, and the ambulance-gear, IP-camera setup, train WiFi
and mesh-radio detections - written anew, thank you. Drone Remote ID over Wi-Fi Aware follows
[OpenDroneID's receiver-android](https://github.com/opendroneid/receiver-android) (Apache-2.0).
The keeps-turning-up follower check follows
[Chasing Your Tail](https://github.com/ArgeliusLabs/Chasing-Your-Tail-NG) (MIT). The probe
shape is [FlipDeFlock](https://github.com/ReconGrunt/FlipDeFlock)'s IE skeleton hash, ported
so its values match, with its candidate and generic shape lists (GPL-3.0). The ADS-B decoder
is written from the published format and Junzi Sun's *The 1090 MHz Riddle*, with
[SkyPulse](https://github.com/MrJohn6774/SkyPulse) (GPL-3.0) and
[dump1090](https://github.com/antirez/dump1090) (BSD-3-Clause) as references. Two fake-cell
checks follow [AIMSICD](https://github.com/CellularPrivacy/Android-IMSI-Catcher-Detector) and
[SnoopSnitch](https://github.com/srlabs/snoopsnitch) (GPL-3.0; ideas only). European V2X (ITS-G5) frames are read the way
[V2X2MAP](https://github.com/pit711/V2X2MAP) by Peter Holzhauser does (MIT; see
`esp/V2X2MAP-LICENSE`), with the CAM / DENM messages decoded from ETSI's own ASN.1.
Fast Pair devices are named from the genuine-device list in
[Momentum-Apps](https://github.com/Next-Flip/Momentum-Apps)' BLE Spam (GPL-3.0, @Willy-JL,
@Spooks4576; `assets/vendors/fast_pair.tsv`) - thank you.
Tracker service IDs follow [AirGuard](https://github.com/seemoo-lab/AirGuard) by
SEEMOO / TU Darmstadt (Apache-2.0). The USB WiFi monitor-mode driver for RTL8811AU /
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
Android PCAP Capture as the USB reference. The MT7921AU (WiFi 6E) driver is ported from
[mt76-usb-macos](https://github.com/Network-Weather/mt76-usb-macos) by Network Weather
(BSD-3-Clause-Clear), a transcription of the MT7921 path of the Linux mt76 driver
(openwrt/mt76, MediaTek and the mt76 authors, BSD-3-Clause-Clear); its transmit path was left out. Their firmware (Ralink rt2870.bin,
MediaTek mt7662 and MT7961, open ath9k_htc) is the unmodified linux-firmware images, with
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
contributors, ODbL) - thank you. The map is drawn by [MapLibre Native](https://github.com/maplibre/maplibre-native)
(BSD-2-Clause) on [OpenFreeMap](https://openfreemap.org)'s vector tiles (OpenStreetMap data and [OpenMapTiles](https://openmaptiles.org),
ODbL / CC-BY 4.0), with the optional [Mapbox Maps SDK](https://docs.mapbox.com/android/maps/) under Mapbox's terms. The
DedSec and fsociety theme font is Share Tech
Mono (SIL Open Font License 1.1). The DedSec theme is an unofficial fan homage to
Watch Dogs; its header poster uses DedSec imagery. Watch Dogs, DedSec, ctOS and Blume
are trademarks of Ubisoft. The fsociety theme is an unofficial fan homage to Mr. Robot
and quotes short lines from the show; Mr. Robot is a trademark of Universal Content
Productions. RF Sentinel is not affiliated with or endorsed by Ubisoft, USA Network
or NBCUniversal.

</details>

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
