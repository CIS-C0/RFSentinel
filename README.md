<h1 align="center">📡 BLE &amp; WiFi Police Scanner 🚨</h1>

<p align="center"><b>Detect body cams, license-plate cameras, drones and trackers around you: passively, from your phone.</b></p>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/releases/latest"><img src="docs/download-button.svg" width="460" alt="Download the app for Android - free, latest version (.apk)" /></a>
</p>

<details>
<summary><b>New to installing apps outside the Play Store? Click here for step-by-step help.</b></summary>

1. On your Android phone, tap the **Download the app** button above.
2. On the page that opens, scroll to **Assets** and tap the file ending in **`-release.apk`**
   (for example `RFSentinel-v2.8.0-release.apk`). Don't pick the "Source code" files.
3. When the download finishes, tap the notification (or open **Files > Downloads**) and tap the APK.
4. If Android says the app is from an unknown source, tap **Settings**, turn on
   **Allow from this source**, then go back and tap **Install**.
5. Open **RF Sentinel**. A short setup wizard helps you pick a theme and grant the
   Bluetooth and location permissions it needs to scan.

**Updating:** download the newest APK the same way and install it over the old one.
Your history and settings are kept.

Needs Android 8.0 or newer. RF Sentinel only listens: it never transmits, and your
scans stay on your phone.

</details>

<p align="center">
  <a href="https://github.com/CIS-C0/RFSentinel/stargazers"><img src="docs/star-banner.svg" width="640" alt="Like RF Sentinel? Give it a star on GitHub - it's free, takes one click and helps others find it" /></a>
</p>
<p align="center"><sub>⭐ Tap <b>Star</b> at the top right of this page if the project is useful to you.</sub></p>

<p align="center">
  <img src="docs/screenshots/radar-dedsec.jpg" width="300" alt="Radar view with demo detections (DedSec theme)" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/list-dedsec.jpg" width="300" alt="List view with demo detections (DedSec theme)" />
</p>
<p align="center"><sub>Radar and list views in the DedSec theme, with demo detections. <a href="#themes">11 more themes below</a>.</sub></p>

---

# RF Sentinel

A non-rooted Android app that passively listens to Bluetooth LE advertisements
and WiFi beacons. It shows every device around you with its vendor, type and
signal, and flags surveillance and law-enforcement equipment:

- body cameras (Axon)
- Flock license-plate cameras and Raven audio sensors
- drones, with their Remote ID decoded
- item trackers that are following you
- camera glasses

Inspired by the nyanBOX hardware device and the defunct RF Party app, which was
itself based on Alan Meekins' DEF CON 31 talk *"Snoop Unto Them As They Snoop
Unto Us"*. Some feature ideas also come from SØPHIA and BLE Radar (MetaRadar).
No code from those projects is included.

## Scope

- **Passive, receive-only.** Nothing is transmitted, jammed, spoofed, or
  connected to. The app only reads what devices already broadcast publicly.
- **Local only.** There are no accounts, no cloud sync and no analytics. The
  only network use is OpenStreetMap map tiles while the map screen is open. Detections live in a local Room database and
  are excluded from cloud backup. Data leaves the phone only when you export it
  (CSV/GPX/KML/JSON) through the share sheet.
- **Not legal advice.** Whether passive BLE/WiFi scanning is legal depends on
  where you are. Check your local laws before using it.
- **A match is a signature, not an identification.** `00:25:DF` means "a device
  registered to Axon Enterprise", not "a police officer". Every match shows its
  evidence, its source, and a confidence tier: weak / probable / strong.
  Signatures and their provenance are listed in [docs/SIGNATURES.md](docs/SIGNATURES.md).

## Themes

Twelve looks, picked in the setup wizard or in Settings > Appearance. The styled
themes each draw their own animated header (all original artwork):

| Theme | Look |
|---|---|
| **DedSec** | Hacker street art: a pixel skull with RGB split, a title that decrypts itself, pixel-noise bursts, a yellow "RX ONLY" sticker, a self-typing terminal line and cut-corner buttons |
| **Night Drive** | Red-only cockpit HUD with a scrolling heading tape. Every colour, including category tags and radar blips, is mapped to red to protect night vision on dark roads |
| **Night Vision** | Green phosphor image intensifier with glow, grain, flicker and a round scope vignette |
| **Amber CRT** | 1980s terminal: amber glow, blinking block cursor, scanlines and a rolling refresh bar |
| **Synthwave** | Chrome gradient title, striped sunset and a wireframe grid scrolling toward you |
| **Tactical** | Olive and coyote tan, stencil callsign with a Zulu clock and a sweeping reticle |
| **Blueprint** | Drafting grid, dimension line and a title block on drafting blue |
| **Paper** | Newspaper masthead with rules and a dateline, the most readable theme in bright sunlight |
| System / Light / Dark / Material You | The standard looks; Material You takes its colours from your wallpaper |

The screenshots use **made-up demo devices** (debug builds only, started with
`adb shell am start -n com.rfsentinel.app/.MainActivity --ez demo true`). They run
through the real detection pipeline, so the groupings, scores and labels are what
the app would show.

**List view**

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-list.jpg" width="190" alt="DedSec theme, list view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-list.jpg" width="190" alt="Night Drive theme, list view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-list.jpg" width="190" alt="Night Vision theme, list view" /><br/><sub><b>Night Vision</b></sub></td><td align="center"><img src="docs/screenshots/themes/amber-list.jpg" width="190" alt="Amber CRT theme, list view" /><br/><sub><b>Amber CRT</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/synthwave-list.jpg" width="190" alt="Synthwave theme, list view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-list.jpg" width="190" alt="Tactical theme, list view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-list.jpg" width="190" alt="Blueprint theme, list view" /><br/><sub><b>Blueprint</b></sub></td><td align="center"><img src="docs/screenshots/themes/paper-list.jpg" width="190" alt="Paper theme, list view" /><br/><sub><b>Paper</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/system-list.jpg" width="190" alt="System (teal) theme, list view" /><br/><sub><b>System (teal)</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-list.jpg" width="190" alt="Light theme, list view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-list.jpg" width="190" alt="Dark theme, list view" /><br/><sub><b>Dark</b></sub></td><td align="center"><img src="docs/screenshots/themes/material-you-list.jpg" width="190" alt="Material You theme, list view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

<details>
<summary><b>Radar view</b> (click to expand)</summary>

<table>
<tr><td align="center"><img src="docs/screenshots/themes/dedsec-radar.jpg" width="190" alt="DedSec theme, radar view" /><br/><sub><b>DedSec</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-drive-radar.jpg" width="190" alt="Night Drive theme, radar view" /><br/><sub><b>Night Drive</b></sub></td><td align="center"><img src="docs/screenshots/themes/night-vision-radar.jpg" width="190" alt="Night Vision theme, radar view" /><br/><sub><b>Night Vision</b></sub></td><td align="center"><img src="docs/screenshots/themes/amber-radar.jpg" width="190" alt="Amber CRT theme, radar view" /><br/><sub><b>Amber CRT</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/synthwave-radar.jpg" width="190" alt="Synthwave theme, radar view" /><br/><sub><b>Synthwave</b></sub></td><td align="center"><img src="docs/screenshots/themes/tactical-radar.jpg" width="190" alt="Tactical theme, radar view" /><br/><sub><b>Tactical</b></sub></td><td align="center"><img src="docs/screenshots/themes/blueprint-radar.jpg" width="190" alt="Blueprint theme, radar view" /><br/><sub><b>Blueprint</b></sub></td><td align="center"><img src="docs/screenshots/themes/paper-radar.jpg" width="190" alt="Paper theme, radar view" /><br/><sub><b>Paper</b></sub></td></tr>
<tr><td align="center"><img src="docs/screenshots/themes/system-radar.jpg" width="190" alt="System (teal) theme, radar view" /><br/><sub><b>System (teal)</b></sub></td><td align="center"><img src="docs/screenshots/themes/light-radar.jpg" width="190" alt="Light theme, radar view" /><br/><sub><b>Light</b></sub></td><td align="center"><img src="docs/screenshots/themes/dark-radar.jpg" width="190" alt="Dark theme, radar view" /><br/><sub><b>Dark</b></sub></td><td align="center"><img src="docs/screenshots/themes/material-you-radar.jpg" width="190" alt="Material You theme, radar view" /><br/><sub><b>Material You</b></sub></td></tr>
</table>

</details>

## Building

Requirements: Android Studio (or JDK 17+ and the Android SDK with platform 37).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/RFSentinel-v<version>-debug.apk`.
To rebuild the offline vendor databases, see `tools/gen_assets.py`.

| Component | Version |
|---|---|
| Gradle | 9.6.0 (wrapper included) |
| Android Gradle Plugin | 9.4.0 (built-in Kotlin) |
| KSP / Room | 2.3.12 / 2.8.5 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

Checks: `./gradlew testDebugUnitTest lintDebug`

### Release builds

```bash
./gradlew assembleRelease
```

This writes `app/build/outputs/apk/release/RFSentinel-v<versionName>-release.apk`.
Published builds are copied into `releases/`. To cut a new version, bump
`appVersionCode` and `appVersionName` at the top of `app/build.gradle.kts`.

Signing reads `keystore.properties` in the project root, which is git-ignored:

```properties
storeFile=keystore/rfsentinel-release.jks
storePassword=...
keyAlias=rfsentinel
keyPassword=...
```

Without that file the release APK is built unsigned. **Back up the keystore and
its passwords.** Updates to an installed app must be signed with the same key.

## Using it

Tap **Start scanning** and grant location and "nearby devices". The notification
permission is optional: without it, alerts still sound and show in the app.

### Main screen

- **Threat banner:** all clear, weak, probable, strong, or "may be following you".
- **Live list:** every device heard in the last 3 minutes, with vendor (from the
  IEEE and Bluetooth SIG databases), device type, radio, signal band, rough
  distance, and NEW / ★ / FOLLOWING badges. Flagged devices sort first, show a
  category tag with confidence, and flash in their category colour. Matches that
  reach the alert threshold also sound, with a vibration pattern per tier
  (1 pulse weak, 2 probable, 3 strong).
- **Radar view:** closer to the centre means a stronger signal. The angle is not
  a direction; a phone can't measure one.
- **Filter chips:** All, Flagged, Trackers, Drones, New, Favorites, Bluetooth,
  WiFi. **Search** matches name, address, vendor and type.
- **Tap** a device for its details. **Long-press** for quick actions: add to
  watchlist, whitelist, favorite, copy the address.

### Device details

Everything here is passive; the app never connects to the device.

- why the device is flagged: each signature, with its evidence and source
- identity: type, name, IEEE registrant, Bluetooth company, address type and
  whether it's trackable, connectable, PHY, TX power
- decoded Apple Continuity, iBeacon, Eddystone, Fast Pair and appearance data
- WiFi band, channel, standard and security
- drone Remote ID: serial, aircraft type, position, altitude, speed, heading, and
  the operator's position, with buttons that open the points in your maps app
- a live signal graph with trend, and a **Locate** mode: the beeps speed up as
  you get closer
- history: first seen, sessions, detect count, past logged matches with GPS, and
  how far the device travelled with you
- the full raw advertisement, split into AD structures
- actions: add to or remove from the watchlist, whitelist, favorite, copy, and
  share a text report

### Everything else

- **Follower alerts:** a warning when a tracker or flagged device stays with you
  (default 10 minutes and 800 m of travel). Needs location.
- **Screen-off detection:** a second, filtered Bluetooth scan on the key
  signature identifiers keeps running in your pocket.
- **Watchlist & rules:** add exact devices, vendor prefixes, "name contains" or
  "maker contains" rules. Import and export them as JSON.
- **Match history:** every logged match. Export as CSV, or as GPX / KML for
  GPS-tagged matches.
- **Settings:**
  - Bluetooth scan intensity
  - WiFi interval
  - which categories to detect (network cameras are off by default)
  - regional presets
  - alert threshold, sound, vibration, spoken alerts, discreet mode
  - follower thresholds
  - GPS tagging
  - data retention
  - auto-start on boot
- **Notification:** tap "Passive scan active" to return to the app, or use its
  **Stop** button. It shows live device and flagged counts.
- **Quick Settings tile** and a **home-screen widget**, both with one-tap
  start/stop.

## Map and recorded traces

- **Map** (toolbar map button) shows an OpenStreetMap view with:
  - your position
  - the trace being recorded
  - devices, placed **where your phone was when their signal was strongest**.
    This approximates the device's position; it isn't a fix.

  Flagged devices use their category colour. **All devices** adds ordinary
  ones. Tap a device for what it is, then *Details*.
- **Record trace** saves your GPS route plus every device heard along it as a
  *trace*. Recording runs in the scanner service, so it continues with the screen
  off. GPS points are saved every ~10 m / 30 s, and flushed to storage every
  10 s. You can also record automatically whenever scanning starts: Settings →
  Location → *Record a GPS trace automatically*.
- **Recorded traces** (map → Traces, or the main menu) lists every trace with its
  duration, distance and device counts. Tap one to view it on the map;
  long-press to export, rename or delete.
- **Trace export:**
  - **GPX**: track plus device waypoints, for any GPS or map app
  - **KML**: Google Earth / My Maps
  - **GeoJSON**: web maps, QGIS
  - **CSV**: every device heard on the trace

  For the map formats, you can include all devices or flagged ones only.
- **Network use:** map tiles come from OpenStreetMap's tile servers only while
  the map is open, and are cached in the app's private cache. The tile server
  sees your IP address and the area you view, never your scans or detections.
  Map data © OpenStreetMap contributors.

## Export all devices

Menu → **Export all devices...** or **Export...**, also under Settings → Data:

- **All nearby devices from this scan, matched or not**: CSV, JSON (full detail
  including raw advertisement data, signal history and positions), or KML
- **All devices ever seen** (history): CSV or JSON
- **Match log**: CSV, JSON, GPX or KML
- **Your watchlist entries**: JSON
- **Everything** in one JSON file

## Android Auto

RF Sentinel shows up in Android Auto as a driver-safe, template-based app. It has:

- **Home screen:**
  - the threat headline and live counts
  - a **Start/Stop** button and a **speaker button** that mutes or unmutes all alert sound
  - navigation to *Flagged nearby*, *Drones & trackers* and *All nearby devices*
  - a toggle for spoken announcements in the car
- **Device lists:** the most important devices first, limited to the rows the car
  allows while driving. Tap a row to open the device.
- **Device details:**
  - why it's flagged, signal (getting closer / moving away), rough distance and identity
  - **Whitelist** and **Watch** buttons, and a favorite star
  - **Navigate** for a drone whose Remote ID position is known; it opens your
    car's navigation app
- **Alerts:** a heads-up on the car screen; tap it to open that device.
- **Sound in the car:** while Android Auto is connected, the alert tone and voice
  play as *navigation-guidance* audio. That's the audio type turn-by-turn prompts
  use: it comes out of the car speakers and briefly lowers your music. Spoken
  alerts are on by default in the car. The master mute (car speaker button, or
  **Mute alerts** in the phone's menu) silences tone and voice everywhere;
  vibration is unaffected.

**Sideloaded builds** need Android Auto's developer setting before the app appears:

1. Open **Android Auto settings** on your phone.
2. Tap **Version** 10 times to unlock developer mode.
3. In the ⋮ menu, open **Developer settings** and enable **Unknown sources**.
4. Reconnect to the car.

Android Auto on Google Play only accepts certain app categories (media,
messaging, navigation, points of interest, IoT). RF Sentinel declares IoT, but
for sideloading that doesn't matter.



- **Phone radios are weaker than dedicated hardware.** A phone's BLE radio
  listens on fewer advertising channels and has less gain than purpose-built
  gear such as nyanBOX's triple-NRF24 setup. Expect fewer catches and shorter
  range.
- **WiFi scanning is throttled.** Android allows about 4 `startScan()` calls per
  2 minutes per app. The default interval is 30s so that scans aren't wasted.
  Setting it lower just gets requests throttled. The foreground service helps,
  but it doesn't remove the limit.
- **WiFi only sees access points.** Client devices are invisible, so a laptop or
  MDT won't show up unless it is running a hotspot. Most in-car laptops connect
  out over cellular.
- **RSSI is not distance.** The metre estimates are rough (often 2-3x off), and
  the radar angle is not a direction.
- **Screen-off coverage is partial.** With the screen off, Android keeps only the
  filtered scan running. That covers payload signatures, trackers, drones, glasses,
  and your exact-address watchlist entries. It does not cover MAC-prefix-only
  matches, because Android filters can't express a prefix.
- **Not everything is detectable.** Most license-plate cameras other than Flock
  (Motorola/Vigilant, ELSAG, Genetec...) and cellular GPS trackers only use LTE,
  which a phone can't passively scan.
- **Auto-start at boot is reduced.** Android doesn't grant while-in-use location
  at boot, so the service starts with the `connectedDevice` type only. BLE
  works, but WiFi results can be limited until you open the app once.
- **Random MAC addresses.** Devices that use randomized BLE addresses won't
  match any OUI.

## Sources

Every bundled prefix, company ID and UUID was checked against the IEEE registry
or the Bluetooth SIG assigned numbers. Each preset entry in
`app/src/main/assets/oui_presets/` carries a `source`, a `score` and a
`category`. If you add signatures, cite a registry or published research, and
don't guess. See [docs/SIGNATURES.md](docs/SIGNATURES.md).

Third-party references:

- **all-cameras-are-beacons signature reference (Apache-2.0):** signature values
  and confidence ladder.
- **opendroneid-core-c (Apache-2.0):** Remote ID message layout.
- **IEEE Registration Authority:** vendor database, via Wireshark's weekly `manuf`.
- **Bluetooth SIG assigned numbers:** company IDs, UUIDs, appearance values.

## Version control and releases

The source lives on GitHub: https://github.com/CIS-C0/RFSentinel.
APKs are not committed. Each version is published as a GitHub Release with the
signed APK attached.

To cut a new version:

1. Bump `appVersionCode` and `appVersionName` in `app/build.gradle.kts`.
2. Build the release APK:

   ```bash
   ./gradlew testDebugUnitTest assembleRelease
   ```

3. Commit, tag and push:

   ```bash
   git commit -am "Release vX.Y.Z" && git tag -a vX.Y.Z -m "vX.Y.Z" && git push --follow-tags
   ```

4. Publish the release with the APK attached:

   ```bash
   gh release create vX.Y.Z app/build/outputs/apk/release/RFSentinel-vX.Y.Z-release.apk --title "RF Sentinel X.Y.Z" --generate-notes
   ```

The signing key (`keystore/`, `keystore.properties`) is deliberately **not** in
git. Back it up separately: without it you can't ship updates that install over
existing versions.
