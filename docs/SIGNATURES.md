# RF Sentinel detection signatures

Every signature has a **confidence** from 0 to 100, which maps to a tier:

| Tier | Confidence | What it means |
|---|---|---|
| Weak (verify) | below 50 | The identifier is shared with many unrelated products. It shows as an amber highlight. By default it does not sound. |
| Probable | 50–79 | A vendor-specific identifier. It alerts at the default threshold. |
| Strong | 80 and up | A payload tag, or a network name the vendor gives its own devices. |

The confidence ladder, and most of the non-registry signatures, come from the
[all-cameras-are-beacons signature reference](https://github.com/soyboi1312/all-cameras-are-beacons/blob/main/docs/signatures.md)
(Apache-2.0). That document explains in detail why each row exists and why
some tempting rows were rejected. The rules were written from its values; none
of its code is used.

Before shipping, the identifiers were checked against these registries:

- **Bluetooth company IDs and 16-bit UUIDs:** the Bluetooth SIG assigned-numbers
  YAML files at bitbucket.org/bluetooth-SIG/public.
- **MAC prefixes:** the IEEE registry, through Wireshark's weekly copy.
  `tools/gen_assets.py` fails if any prefix used in code stops matching its
  expected registrant.

## Payload / name / UUID signatures (`detect/SignatureEngine.kt`)

| Category | Signature | Confidence | Source |
|---|---|---|---|
| Body cam | `BWCDEVICE` ASCII in the service data, manufacturer data or raw advert, matched in either byte order | 90 | ACAB, field-validated against cameras confirmed by eye |
| Body cam | Company ID `0x034D` (TASER International) | 60 | SIG |
| Body cam | Service UUID `0xFC81` (Axon), `0xFE6B` or `0xFE6C` (TASER) | 60 | SIG |
| Body cam | Name contains `BodyWorn Remote` (Utility Inc.) | 80 | ACAB / nite-oui-collection |
| Public safety | Company ID `0x04EC`, or UUID `0xFD8E` / `0xFE04` (Motorola Solutions) | 45 | SIG; mostly two-way radios |
| Public safety | UUID `0xFE79` / `0xFD66` (Zebra) with a factory-serial name (`^[A-Z0-9]{2}[A-Z]{3}\d{9}$`): unrenamed fleet mobile printer, e.g. e-ticket printers | 50 | SIG; Zebra Link-OS BLE app note; field capture |
| Public safety | UUID `0xFE79` / `0xFD66` (Zebra) with any other name | 30 | SIG; mostly warehouse/retail printers |
| Public safety | Company ID `0x01F1` (Zebra Technologies) without a Zebra printer UUID | 25 | SIG; printers, but also store scanners and handhelds |
| Public safety | Company ID `0x0755` (Brother Industries) | 20 | SIG; RuggedJet / PocketJet in-car printers, mostly home and office printers |
| Public safety | UUID `0xFDE1` / `0xFDCA` (Fortin Electronic Systems) | 20 | SIG; vehicle interface modules used in police upfits, also civilian remote starters |
| Public safety | Company ID `0x04BC` or UUID `0xFCDA` (Dräger) | 35 | SIG; roadside breath / drug screening devices, also hospital and gas-detection gear |
| ALPR | WiFi SSID starting with `Flock-` | 88 | ACAB (ryanohoro, GainSec) |
| ALPR | BLE name `FS Ext Battery` | 80 | ACAB |
| ALPR | BLE name `Penguin-<digits>` or `FS-<hex>` | 70, or 80 with company ID `0x09C8` | ACAB |
| ALPR | BLE name starting with `Flock` | 55 | ACAB (loose brand string) |
| ALPR | Company ID `0x09C8` alone (XUNTONG, shared silicon) | 45 | SIG / ACAB |
| Audio sensor | 128-bit service UUIDs with short IDs `0x3100` to `0x3500` (Flock Raven) | 80 | ACAB field capture |
| Drone | Service data `0xFFFA` with ASTM F3411 app code `0x0D`; decoded | 95 | ASTM F3411 / opendroneid |
| Drone | WiFi vendor IE with OUI `FA:0B:BC`, type `0x0D` | 95 | ASTM F3411 |
| Drone | Drone makers' own IEEE blocks (DJI, Parrot, Skydio, Autel /28, Yuneec /28, and others) | 60 | IEEE / ACAB |
| Tracker | Apple company ID `0x004C`, type `0x12`, length `0x19` (separated from owner only) | 70 | ACAB; arXiv 2501.17452 |
| Tracker | Google `0xFEAA` service data, frame `0x41` (separated; frame `0x40` = near owner, ignored) | 70 | Find Hub Network spec |
| Tracker | Samsung `0xFD5A` / Tile `0xFEED` service data | 55 | SIG |
| Glasses | Company ID `0x0D53` (Luxottica), `0x03C2` (Snapchat), `0x060C` (Vuzix); UUID `0xFE45` | 70 | SIG |
| Glasses | `0x058E` only together with the `META_RB_GLASS` token | 72 | ACAB |
| Glasses | Company ID `0x01AB` or UUID `0xFEB7` / `0xFEB8` (Meta corporate; also Quest headsets) | 45 | SIG / ACAB |
| Glasses | HeyCyan SDK UUID `7905FFF0-B5CE-4E99-A40F-4B1E122D00D0`, full 16-byte match only | 68 | ACAB |
| Network camera (off by default) | SSID starting with `ARLO_VMB_` or `NTGR_VMB_` | 88 | ACAB |
| Network camera (off by default) | Arlo, Blink, Ezviz, Uniview, Amcrest, Wyze, Swann, Night Owl, SkyBell or WUUK blocks (WiFi only) | 65 | IEEE / ACAB |

Deliberately **not** matched, following ACAB:

- A bare 10-digit BLE name. It false-positived on a phone.
- The `-FALCON` SSID suffix. The evidence behind it turned out to be circular.
- Liteon module blocks. Millions of laptops use them.
- Motorola Mobility blocks, which belong to consumer phones.
- Sierra Wireless and Cradlepoint router blocks.
- Company ID `0x058E` on its own. Quest headsets share it.

## Combining evidence (`detect/EvidenceFusion.kt`, `detect/PatrolCluster.kt`)

Single rules are only the first pass. Every 2 s per device the scanner also:

1. **Fuses agreeing rules.** When different rules of the same category match one
   device, the scores combine by noisy-OR, with each supporting hit discounted by
   half because rules often share evidence. Hits under 30 never corroborate.
   Fusion alone never goes past 90. Example: watchlisted address 70 plus Zebra
   serial name 50 gives 78. The patrol-vehicle hit (below) is never fused: it is
   derived from the device's own matches, so fusing would count them twice.
2. **Detects patrol-vehicle kits.** Every device gets a *role* when its matches
   or IEEE registrant say it is police-type gear: body camera, plate reader,
   two-way radio, vehicle cellular router, mobile printer, in-car computer,
   rugged laptop or police camera. Consumer brands that also make car audio or
   office gear (Kenwood, Panasonic Connect, Havis) get no role from their vendor
   name alone, and whitelisted devices never count. Peplink and Inseego routers count
   as vehicle cellular routers. Fortin interface modules and Dräger devices are
   *support* roles: they add to a group but a group needs at least one other role,
   so a driveway of remote starters never reads as a police vehicle. At least two *different* roles
   count as one vehicle when either condition holds:
   - their RSSI rises and falls together (Pearson r ≥ 0.6 over at least 8
     aligned seconds);
   - both signals are flat (parked) and they appeared within 30 s of each other.

   Each member then gets "Possible police vehicle" at 24 + 12 × roles,
   +10 when the signals move together, capped at 88. So two roles score 48 and
   stay weak; three moving together score 70. Never grouped: signals that don't
   move together (r < 0.6), one moving device next to a parked one, or fewer than
   8 shared seconds. The group hit is recomputed every 2 s, so it disappears when
   the group breaks up.

   A device's own match is held for 2 minutes after its evidence was last seen
   (some body cams only include their tag in some packets), then fades.
3. **Links rotated addresses.** A structural advert fingerprint survives address
   rotation: names, service UUIDs, service-data and company-ID payload sizes,
   TX power and connectable flag. When a new private address appears with the
   same fingerprint as exactly one device that went silent 1.5–30 s earlier, it
   inherits that device's matches at −5. Adverts that carry only Apple Continuity
   data have no fingerprint, so phones are never linked. Two identical
   candidates means no link.

Ordinary devices are identified in order of evidence strength, and the detail
screen shows "Identified by":
1. decoded vendor protocols, including the exact AirPods/Beats model
2. the advertised GAP appearance
3. standard SIG services (heart rate, HID, LE Audio, medical...)
4. name patterns
5. member-service owner
6. IEEE registrant (vehicle routers, IoT modules, radios...)
7. company ID

### WiFi access points, including hidden networks (`detect/WifiFingerprint.kt`)

A hidden network still sends beacons with information elements (Android 11+).
The access point is identified from the first of these that answers:

1. **WPS model.** The WPS element (vendor IE `00:50:F2` type 4, possibly split
   over several IEs) carries these attributes:
   - Manufacturer `0x1021`
   - Model Name `0x1023` and Model Number `0x1024`
   - Device Name `0x1011`
   - Primary Device Type `0x1054`: category 6 means router, access point or gateway

   Placeholder values like "0000" or "Wireless Router" are ignored.
2. **Cisco AP name.** Cisco's CCX element (IE 133) holds the configured AP name
   at bytes 10–25.
3. **BSSID registrant.** The IEEE owner of a fixed BSSID, unless it is only a
   chipset maker.
4. **Vendor IE owners.** Each vendor-specific IE's OUI, looked up in the IEEE
   registry and split into equipment makers (Apple, Cisco, Ubiquiti...) and
   chipset makers (Broadcom, Qualcomm, MediaTek...).

**Same access point.** Two BSSIDs belong to the same access point when
octets 2–5 are equal, the last octet is within 16, and the first octet is equal
or locally administered (virtual BSSID). The detail screen then links a hidden
network to the visible network on the same router and borrows its make/model.

### Known plate cameras (`alpr/`)

Downloaded on request from OpenStreetMap through the Overpass API: every element
tagged `surveillance:type=ALPR` in the area on screen (DeFlock maps Flock cameras this
way), with `manufacturer`/`brand`, `operator` and `direction`/`camera:direction`. They
are cached in the app's private storage. While scanning, the app warns once per camera
per 30 minutes when one is within ~20 s of travel (150 m minimum, 600 m maximum) and
the distance is shrinking. Data © OpenStreetMap contributors, ODbL.

### Drone overhead (`detect/DroneProximity.kt`)

Remote ID broadcasts the aircraft's own GPS position, so the app can say a drone is
within 200 m of you (horizontal) with confidence 95, once per drone per 5 minutes.

### Fake cell tower signs (`detect/CellAnalyzer.kt`)

Read every 15 s from the cells Android reports (no root, location permission only):

| Sign | Confidence |
|---|---|
| Serving cell uses a test / reserved MCC (001, 002, 999) | 85 |
| Dropped to 2G/CDMA after 4G/5G while 4G/5G cells are still visible (40 if none are) | 65 |
| Serving cell's MCC differs from the SIM's while not roaming (US 310-316 and India 404/405 are treated as one country) | 55 |
| Location / tracking area changed while GPS shows you standing still for 2+ minutes | 40 |
| Switched to a cell with no neighbours where several were visible moments ago | 35 |

Signs at or above the alert threshold alert; the rest go to the match history.

## MAC-prefix watchlist presets (`assets/oui_presets/*.json`)

These are editable and can be switched on or off in Settings.

| Prefix | Registrant | Category | Confidence |
|---|---|---|---|
| `00:25:DF` | Axon Enterprise, Inc. | Body cam | 75 |
| `D8:1F:65` | Private (field-attributed to Axon by ACAB) | Body cam | 75 |
| `B4:1E:52` | Flock Safety | ALPR | 75 |
| `D4:2D:C5` | i-PRO Co., Ltd. | Public safety | 55 |
| `00:09:BC`, `00:16:ED` | Utility, Inc. | Body cam | 45 |
| `48:46:8D` | Zepcam B.V. (dedicated body-worn camera maker) | Body cam | 75 |
| `00:1D:96` | WatchGuard Video (police in-car and body cameras) | Body cam | 70 |
| `00:23:BD` | Digital Ally (police body and in-car cameras) | Body cam | 70 |
| `9C:83:BF` | PRO-VISION (body and fleet cameras; also school buses) | Body cam | 55 |
| `58:E8:76:C` (28-bit) | Kustom Signals (police radar, in-car video) | Public safety | 70 |
| `00:22:AF`, `E4:1E:0A:B` (28-bit) | Safety Vision (police and transit mobile video) | Public safety | 50 |
| `D4:11:D6` | ShotSpotter / SoundThinking (gunshot-detection sensor) | Audio sensor | 75 |
| `00:16:00` | Cellebrite (phone forensic extraction) | Public safety | 60 |
| `70:B3:D5:71:F` (36-bit) | Grayshift / GrayKey (phone unlocking) | Public safety | 70 |
| `00:1E:96` | Sepura (TETRA police radios) | Public safety | 45 |
| `C4:7C:8D:9` (28-bit) | Airbus Secure Land Communications (TETRA) | Public safety | 45 |
| `54:02:37`, `00:04:18` | Teltronic (TETRA radios) | Public safety | 40 |
| `64:69:BC`, `9C:06:6E` | Hytera (radios, body cams; mostly commercial) | Public safety | 40 |
| `8C:1F:64:A7:8` (36-bit), `00:0D:CA` | Tait (two-way radios) | Public safety | 40 |
| `00:18:29` | Gatsometer (speed / traffic enforcement cameras) | ALPR | 60 |
| `00:30:7E` | Redflex (red-light / speed cameras) | ALPR | 60 |
| `00:22:9F` | Sensys Traffic (traffic enforcement cameras) | ALPR | 55 |
| `00:1D:4D` | Adaptive Recognition (ANPR / ALPR cameras) | ALPR | 55 |
| `00:17:3D` | Neology (ALPR, mostly RFID tolling) | ALPR | 40 |
| `00:24:AE` | Idemia Public Security (French Mesta Fusion speed cameras, police biometric terminals) | ALPR | 50 |
| `00:0A:3E` | EADS Telecom (TETRAPOL: French police ACROPOL and gendarmerie RUBIS networks) | Public safety | 45 |
| `00:23:B9` | Airbus Defence and Space Deutschland (TETRA / TETRAPOL radios) | Public safety | 40 |

### North America and Europe sweep (2026-09)

A systematic sweep of the IEEE registry (MA-L, MA-M, MA-S) for makers of law-enforcement
and surveillance equipment. Prefixes are pulled from the registry by exact registrant name,
and a unit test re-checks every block against its registrant. Ambiguous or consumer
registrants are left out on purpose.

| Registrant(s) | Blocks | What they make | Category | Score |
|---|---|---|---|---|
| Vievu | 1 | Police body cameras (Axon since 2018) | Body cam | 75 |
| International Police Technologies | 1 | Police in-car video (VisionHawk) | Body cam | 75 |
| ICOP Digital | 1 | Police in-car video | Body cam | 70 |
| L-3 Communications Mobile-Vision | 3 | Police in-car and body video | Body cam | 70 |
| Applied Concepts | 1 | Stalker police radar / lidar | Public safety | 70 |
| Decatur Electronics | 1 | Police speed radar | Public safety | 65 |
| Patrol Products Consortium | 1 | Police vehicle upfit (tablets, control systems) | Public safety | 60 |
| Federal Signal SSG | 1 | Emergency-vehicle lightbars and sirens | Public safety | 55 |
| Havis | 2 | Vehicle consoles and computer docks | Public safety | 45 |
| Gamber-Johnson | 10 | Vehicle computer mounts and docks | Public safety | 40 |
| Global Traffic Technologies | 1 | Opticom emergency-vehicle signal priority | Public safety | 45 |
| Fusus | 2 | Real-time crime center hubs (Axon) | Public safety | 70 |
| 3SI Security Systems | 1 | Covert GPS bait trackers (police and bank stings) | Public safety | 55 |
| Digital Receiver Technology | 1 | Cell-site simulators ("DRTbox") | Public safety | 70 |
| Septier Communication | 1 | Cellular interception and location | Public safety | 55 |
| Amesys Defense | 1 | Lawful interception | Public safety | 40 |
| Domo Tactical Communications | 1 | Covert / body-worn COFDM video | Public safety | 55 |
| Persistent Systems, Silvus, TrellisWare | 3 | Tactical mesh radio (SWAT, robots, drones) | Public safety | 40-45 |
| Harris, E.F. Johnson, Daniels (Codan) | 6 | P25 public-safety radio | Public safety | 40-45 |
| Airbus DS Oy, Selex, Simoco, Damm, Thales Communications | 9 | TETRA / public-safety radio | Public safety | 35-45 |
| Perceptics | 2 | Plate readers (border and police) | ALPR | 65 |
| Quercus Technologies | 11 | ANPR cameras (SmartLPR) | ALPR | 60 |
| Robot Visual Systems | 1 | Speed / red-light cameras (Jenoptik) | ALPR | 60 |
| Vitronic | 1 | PoliScan lidar speed enforcement | ALPR | 55 |
| Tattile | 48 | ANPR and traffic cameras (also machine vision) | ALPR | 50 |
| Jenoptik | 1 | Traffic enforcement (also optics) | ALPR | 45 |
| Q-Free | 3 | Tolling and ANPR | ALPR | 40 |
| Genetec | 4 | AutoVu ALPR (also servers, access control) | ALPR | 40 |
| Ubicquia | 1 | UbiHub smart streetlights powering police ALPR / video | ALPR | 55 |
| Cradlepoint | 2 | Vehicle routers (police cars, buses, stores) | Public safety | 35 |

Left out on purpose:
- **Consumer or general-purpose makers:** Sierra Wireless and Inseego routers (ACAB), Panasonic Connect laptops, GoPro and Transcend cameras, Axis / Hanwha / Verkada / Avigilon CCTV, Zebra printers (covered by the Zebra service rule instead), Icom and Kenwood radios, FLIR thermal.
- **Offender-monitoring devices** (SCRAM and other ankle monitors, breath interlocks): these identify private people on probation, not police.
- **Fixed installations with nothing to detect on the move:** Zetron and Frequentis dispatch consoles, Positron 911 systems, Rohde & Schwarz test gear, Verint and NICE recorders.
- **Look-alike names:** Teltronics Inc. (telecom, not Teltronic TETRA), Axon Networks, WatchGuard Technologies, Codan Argus (invalid address block).

France: the national police and gendarmerie body cameras (2021 contract) are Motorola
Solutions VB400s (WiFi, Bluetooth LE 4.2 for holster sensors and peer-assisted
recording). They are covered only by the generic Motorola identifiers above, because
no public source documents their Bluetooth name. The earlier Hikvision cameras aren't
matched: Hikvision's 84 blocks are ordinary CCTV everywhere. The new Réseau Radio du
Futur uses Samsung rugged phones, indistinguishable from consumer ones.

Same-name registrants that are *not* public-safety vendors are deliberately left out:
Axon Networks (unrelated to Axon Enterprise), WatchGuard Technologies (firewalls),
Coban Srl (not the US Coban), and general CCTV makers (Axis, Verkada). A unit test
checks that every preset block belongs, per the IEEE table, to the company its
label names.
| `00:1F:92`, `4C:CC:34`, `00:18:85`, `00:04:7D`, `10:74:6F`, `B8:E2:8C`, `9C:86:2B` | Motorola Solutions | Public safety | 45 |

The Motorola entries are weak on purpose. In ACAB's field capture, all 27 of 27
hits on Motorola WiFi prefixes turned out *not* to be body cams.

### Community lists compared (2026-10)

The prefix lists of five open projects were compared with ours, and every
candidate's IEEE registrant was checked against the maker the project names.

| Project | What was added |
|---|---|
| [Flock You](https://github.com/colonelpanichacks/flock-you) (list by @NitekryDPaul, [nite-oui-collection](https://github.com/nitekry/nite-oui-collection); 82:6B:F2 by DeflockJoplin) | 31 radio-module prefixes seen on Flock cameras, as **weak** ALPR entries (score 35) |
| [OUI-Spy](https://github.com/colonelpanichacks/oui-spy-unified-blue) | 11 Ring (Amazon) blocks, as home cameras (off by default) |
| [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) | Same Flock list; its traffic-camera and drone blocks were already here |
| [Flock-You-Android](https://github.com/MaxwellDPS/Flock-You-Android) | Hikvision, Dahua, Axis, Vivotek, Amcrest, Reolink, Wyze, TVT, Reecam camera blocks; Sierra Wireless and Digi fleet gateways |
| [Fieldwatch](https://github.com/OffGridPete/Fieldwatch) | BlueTOAD (Iteris) and BlipTrack roadside travel-time sensors; Flock-pole battery radios (weak); Hanwha, Uniview, Rhombus cameras; Sierra AirLink, Compex and Inseego (Novatel) vehicle radios |

The Flock radio list is mostly Liteon, Silicon Labs, Espressif and USI module
blocks that are also in many laptops, smart plugs and IoT devices, so those
entries stay weak: they show up amber and only alert when the threshold is set
to "every match". The Flock SSID, Flock's own block (B4:1E:52) and Raven UUIDs
remain the strong signals.

Left out on purpose: 42 prefixes whose IEEE registrant doesn't match the claim -
most of Flock-You-Android's DJI, Autel, Yuneec, Skydio, 3DR and extra Parrot
blocks are registered to Texas Instruments, Intel, ASUS, Google, Espressif and
others, and would flag ordinary phones and laptops as drones; several more aren't
IEEE blocks at all. Generic chip makers listed as "body cam" or "spy camera"
sources (Nordic, Texas Instruments, Raspberry Pi, Ralink, Apple) and DJI's Osmo
/ Ronin gimbal blocks were also left out.

### Police-vehicle vendor sweep (2026-10)

Added after checking which in-car equipment makers named in public police procurement
records were missing. All weak on their own (they mostly matter for patrol-vehicle
grouping), and every block checked against the IEEE registry by `tools/gen_assets.py`.

| Vendor | Blocks | Score | Why weak |
|---|---|---|---|
| Zebra Technologies | 17 MA-L | 25 | Store scanners, handhelds and label printers |
| Brother Industries | 7 MA-L | 20 | Home and office printers (no patrol role from the registrant) |
| Peplink | 4 MA-L | 30 | Also buses, boats, RVs |
| Inseego | 4 more MA-L | 30 | Consumer MiFi hotspots |
| Panasonic Connect (Toughbook) | 3 MA-L | 30 | Projectors and business gear; a laptop is usually a WiFi client (no patrol role) |
| Sierra Wireless AirLink | 6 MA-L, now also in the Canada preset | 40 | Buses, utility trucks, kiosks |

No IEEE block or Bluetooth ID exists for Getac (its blocks are shared with consumer
laptops), Whelen, Feniex, SoundOff or Laser Technology, so they can only be matched by
names captured in the field.

## Canada preset (`assets/oui_presets/canada.json`), researched 2026-09

Built only from public records: police pilot reports, public procurement
records, federal contract announcements and news coverage. Nothing was taken
from memory. Every prefix was checked against the IEEE registry by
`tools/gen_assets.py`.

| Key | Score | Why |
|---|---|---|
| `00:25:DF`, `D8:1F:65` | 75 | Axon body cams and TASERs, the dominant vendor for Canadian police (including the RCMP's Axon Body 4 contract). |
| `8C:1F:64:DF:0` (36-bit MA-S) | 65 | **Cyberkar Systems inc.**, a Canadian police-vehicle computer / in-car systems integrator. A narrow police-vehicle registrant, so a better signal than a big vendor block. It must match all 36 bits, because the neighbouring blocks belong to unrelated companies. |
| `name:BC-02`, `name:BC-03`, `name:HS-01` | 45 / 45 / 40 | Getac cameras and holster sensor used in Canadian police body-cam pilots. Getac has no IEEE block of its own (its blocks are shared with laptops), so only names can match. These names are capture candidates per ACAB, so verify. |
| `name:BC-04` | 40 | Newer Getac model. |
| Motorola Solutions ×7 | 45 | P25 radio networks. Weak: the same blocks are used by commercial radios. |
| Genetec `00:BF:15`, `0C:BF:15`, `00:50:C2:BE:7` | 40 | Canadian plate-reader (AutoVu / Cloudrunner) vendor. Weak: registry-only, the cameras probably use cellular, and the blocks cover other Genetec products. |
| Cradlepoint `00:30:44`, `00:E0:1C` | 35 | Routers installed in police cars. Weak: common on buses and in stores. |
| i-PRO `D4:2D:C5` | 50 | Body cams / surveillance cameras, kept as a candidate. |
| `F4:60:77` | 20 | Texas Instruments chip block seen on Zebra ZQ-series printers. Highlight only: TI chips are everywhere. |
| `name:RJ-4230B`, `name:RJ-4250WB`, `name:RJ-4040`, `name:PJ-763`, `name:PJ-883` | 40 / 40 / 35 / 35 / 35 | Brother RuggedJet / PocketJet in-car e-citation and report printers. Name forms need a field capture to confirm. |

**Not added:**
- **Sierra Wireless** routers, for the reasons ACAB gives.
- **Getac/MiTAC blocks**: shared with laptops.
