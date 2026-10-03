# USB WiFi monitor-mode drivers: sources and licenses

These drivers run USB WiFi adapters in receive-only monitor mode from userspace.

- `Rtl8821auMonitor.kt` and `Rtl8821auTables.kt` drive Realtek RTL8811AU / RTL8821AU adapters (e.g. ALFA AWUS036ACS).
- `Rtl8822buMonitor.kt` and `Rtl8822bTables.kt` drive Realtek RTL8812BU / RTL8822BU adapters, with the firmware and register tables in `assets/usbwifi/rtl8822b_*.bin`.
- `MonitorFrames.kt` turns the received 802.11 frames into access points and client devices for both drivers.

| Part | Origin | License |
|---|---|---|
| RTL8811AU / 8821AU register tables (power-on sequence, MAC / BB / AGC / RF tables, channel tuning) | Realtek 88xxau Linux driver, as maintained in [aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au) | GPL-2.0 |
| RTL8811AU / 8821AU userspace USB driver and 802.11 frame parsing | [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod | GPL-3.0 |
| RTL8812BU / 8822BU bring-up (power sequence, HalMAC firmware download, MAC init, channel tuning, gain control, RX descriptor parsing) | [devourer](https://github.com/OpenIPC/devourer) by OpenIPC, `src/jaguar2/` | GPL-2.0 |
| RTL8822B firmware image and phydm BB / AGC / RF tables | Realtek rtl88x2bu Linux driver, as extracted by devourer | Realtek firmware / GPL-2.0 |
| Integration into RF Sentinel; removal of handshake / PMKID capture (8821AU) and of every transmit, beamforming and calibration-for-TX path (8822BU) | RF Sentinel (CIS-C0) | GPL-3.0 |

RF Sentinel is distributed under the GNU GPL v3.0 (see `LICENSE`). The register
values and firmware are hardware configuration data from Realtek's driver; credit
and thanks to Realtek, the aircrack-ng rtl8812au maintainers, RocketGod for
Wardrive Go, and OpenIPC for devourer.
