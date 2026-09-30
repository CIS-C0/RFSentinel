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
   name alone, and whitelisted devices never count. At least two *different* roles
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

Same-name registrants that are *not* public-safety vendors are deliberately left out:
Axon Networks (unrelated to Axon Enterprise), WatchGuard Technologies (firewalls),
Coban Srl (not the US Coban), and general CCTV makers (Axis, Verkada). A unit test
checks that every preset block belongs, per the IEEE table, to the company its
label names.
| `00:1F:92`, `4C:CC:34`, `00:18:85`, `00:04:7D`, `10:74:6F`, `B8:E2:8C`, `9C:86:2B` | Motorola Solutions | Public safety | 45 |

The Motorola entries are weak on purpose. In ACAB's field capture, all 27 of 27
hits on Motorola WiFi prefixes turned out *not* to be body cams.

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
