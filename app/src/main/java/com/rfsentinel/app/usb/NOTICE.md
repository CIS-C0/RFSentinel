# USB WiFi monitor-mode driver: sources and licenses

`Rtl8821auMonitor.kt` and `Rtl8821auTables.kt` drive Realtek RTL8811AU / RTL8821AU
USB WiFi adapters (e.g. ALFA AWUS036ACS) in receive-only monitor mode from userspace.

| Part | Origin | License |
|---|---|---|
| Register tables (power-on sequence, MAC / BB / AGC / RF tables, channel tuning) | Realtek 88xxau Linux driver, as maintained in [aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au) | GPL-2.0 |
| Userspace USB driver and 802.11 frame parsing | [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod | GPL-3.0 |
| Integration into RF Sentinel, removal of handshake / PMKID capture | RF Sentinel (CIS-C0) | GPL-3.0 |

RF Sentinel is distributed under the GNU GPL v3.0 (see `LICENSE`). The register
values are hardware configuration data taken from Realtek's GPL driver; credit
and thanks to Realtek and the aircrack-ng rtl8812au maintainers, and to RocketGod
for Wardrive Go.
