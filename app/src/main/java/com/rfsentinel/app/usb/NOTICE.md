# USB WiFi monitor-mode drivers: sources and licenses

These drivers run USB WiFi adapters in receive-only monitor mode from userspace.

- `Rtl8821auMonitor.kt` and `Rtl8821auTables.kt` drive Realtek RTL8811AU / RTL8821AU adapters (e.g. ALFA AWUS036ACS).
- `Rtl8822buMonitor.kt` and `Rtl8822bTables.kt` drive Realtek RTL8812BU / RTL8822BU adapters, with the firmware and register tables in `assets/usbwifi/rtl8822b_*.bin`.
- `Rtl8187Monitor.kt` drives Realtek RTL8187L / RTL8187B adapters (e.g. ALFA AWUS036H). They need no firmware.
- `Rt3070Monitor.kt` drives Ralink RT3070 adapters (e.g. ALFA AWUS036NH / AWUS036NEH), with Ralink's firmware in `assets/usbwifi/rt2870.bin`.
- `Rtl8814auMonitor.kt` and `Rtl8814auTables.kt` drive Realtek RTL8814AU adapters (e.g. ALFA AWUS1900), 2.4 + 5 GHz.
- `Mt7612uMonitor.kt` drives MediaTek MT7612U / MT7632U adapters (e.g. ALFA AWUS036ACM), 2.4 + 5 GHz, with MediaTek's firmware in `assets/usbwifi/mt7662*.bin`.
- `Ar9271Monitor.kt` and `Ar9271Tables.kt` drive Atheros AR9271 adapters (e.g. ALFA AWUS036NHA) - experimental - with the open ath9k_htc firmware in `assets/usbwifi/htc_9271-1.4.0.fw`.
- `BulkRx.kt` is the USB receive loop of the RTL8187, RT3070, RTL8814AU, MT7612U and AR9271 drivers.
- The FREE-WiLi 2 (an ESP32-C5 WiFi board with a USB console) is read by `esp/EspReader.kt` / `esp/EspReports.kt` (FreeWiliReports), ported from Wardrive Go.
- `MonitorFrames.kt` turns the received 802.11 frames into access points and client devices for all of them.

| Part | Origin | License |
|---|---|---|
| RTL8811AU / 8821AU register tables (power-on sequence, MAC / BB / AGC / RF tables, channel tuning) | Realtek 88xxau Linux driver, as maintained in [aircrack-ng/rtl8812au](https://github.com/aircrack-ng/rtl8812au) | GPL-2.0 |
| RTL8811AU / 8821AU userspace USB driver and 802.11 frame parsing | [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod | GPL-3.0 |
| RTL8812BU / 8822BU bring-up (power sequence, HalMAC firmware download, MAC init, channel tuning, gain control, RX descriptor parsing) | [devourer](https://github.com/OpenIPC/devourer) by OpenIPC, `src/jaguar2/` | GPL-2.0 |
| RTL8822B firmware image and phydm BB / AGC / RF tables | Realtek rtl88x2bu Linux driver, as extracted by devourer | Realtek firmware / GPL-2.0 |
| RTL8187L / 8187B bring-up, RTL8225 / RTL8225Z2 radio tuning and RX descriptor handling | Linux kernel `rtl8187` driver (Michael Wu, Andrea Merello, Herton Ronaldo Krzesinski, Hin-Tak Leung, Larry Finger; register values from Realtek's r8187 driver), with Kismet's [Android PCAP Capture](https://www.kismetwireless.net/android-pcap/) (Mike Kershaw / Dragorn; signal-strength work by Jinghao Shi, via Gabriel Nyman's AndroidPCAP fork) as the USB reference | GPL-2.0 |
| RT3070 userspace USB driver (firmware load, MAC / BBP / RF init, channel tuning, RX parsing) | [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod, following the Linux `rt2800usb` / `rt2800lib` driver (rt2x00 project) | GPL-3.0 / GPL-2.0-or-later |
| RT3070 firmware `rt2870.bin` (version 36, unmodified, from [linux-firmware](https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git)) | Ralink Technology Corporation (now MediaTek) | Redistributable binary, see `assets/usbwifi/LICENCE.ralink-firmware.txt` |
| RTL8814AU, MT7612U and AR9271 userspace USB drivers, FREE-WiLi 2 console reader | [Wardrive Go](https://github.com/RocketGod-git/wardrive-go) by RocketGod, following Realtek's 8814au driver and the Linux `mt76x2u` and `ath9k_htc` drivers | GPL-3.0 |
| RTL8814AU register tables | Realtek 8814au driver, as used by Wardrive Go | GPL-2.0 |
| AR9271 register tables | Linux `ath9k` (`ar9002_initvals.h`), as used by Wardrive Go | ISC |
| MT7612U firmware `mt7662.bin` and ROM patch `mt7662_rom_patch.bin` (unmodified, from linux-firmware) | Ralink, a MediaTek company | Redistributable binary, see `assets/usbwifi/LICENCE.ralink_a_mediatek_company_firmware` |
| AR9271 firmware `htc_9271-1.4.0.fw` (unmodified, from linux-firmware; Wardrive Go ships a build that differs in 10 bytes) | [open-ath9k-htc-firmware](https://github.com/qca/open-ath9k-htc-firmware), Qualcomm Atheros and others | Free software, see `assets/usbwifi/LICENCE.open-ath9k-htc-firmware` |
| RTL-SDR driver (`sdr/RtlSdr.kt`: RTL2832U baseband, R820T / R828D tuner, RTL-SDR Blog V4 band switching) | [librtlsdr](https://github.com/osmocom/rtl-sdr) (osmocom: Steve Markgraf, Dimitri Stolnikov and others; R82xx code by Mauro Carvalho Chehab and Steve Markgraf; Blog V4 support by RTL-SDR Blog) | GPL-2.0-or-later |
| Integration into RF Sentinel; removal of handshake / PMKID capture (8821AU, RT3070) and of every transmit, beamforming and calibration-for-TX path (8822BU, RTL8187); RT3070 fixes checked against the kernel (eFuse addressing, EEPROM reads, DMA / frame-length setup, receive gain, external LNA, EEPROM BBP overrides); MT7612U: bad-checksum frames dropped, auto-responder off, kernel ROM-patch rule; AR9271: signal relative to the noise floor, FCS removed, packets spanning transfers reassembled, WMI answers matched, running firmware reused | RF Sentinel (CIS-C0) | GPL-3.0 |

RF Sentinel is distributed under the GNU GPL v3.0 (see `LICENSE`). The register
values and firmware are hardware configuration data from Realtek's driver; credit
and thanks to Realtek, the aircrack-ng rtl8812au maintainers, RocketGod for
Wardrive Go, OpenIPC for devourer, the Linux rtl8187 and rt2x00 authors, and
Kismet. The Ralink and MediaTek firmware is redistributed under its own licences
(binary only, unmodified, no reverse engineering), reproduced in
`assets/usbwifi/LICENCE.ralink-firmware.txt` and
`assets/usbwifi/LICENCE.ralink_a_mediatek_company_firmware`; the ath9k_htc firmware
is free software (`assets/usbwifi/LICENCE.open-ath9k-htc-firmware`, sources at
https://github.com/qca/open-ath9k-htc-firmware).
