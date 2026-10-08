package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

import java.util.Random;
import java.util.UUID;

/**
 * A synthetic Android device fingerprint sent in ClientInfo when registering /
 * logging in to Waze: one of 8 fixed device profiles, matching what the Waze
 * client itself reports, with a fresh random installationId.
 */
final class DeviceIdentity {
    final String manufacturer;
    final String model;
    final String osVersion;     // synthetic "<release>-SDK<api>" token, sent as ClientInfo.osVersion
    final int    screenW;
    final int    screenH;
    final String installationId;

    DeviceIdentity(String manufacturer, String model, String osVersion,
                   int screenW, int screenH, String installationId) {
        this.manufacturer = manufacturer;
        this.model = model;
        this.osVersion = osVersion;
        this.screenW = screenW;
        this.screenH = screenH;
        this.installationId = installationId;
    }

    private static final DeviceIdentity[] POOL = {
        new DeviceIdentity("samsung",  "SM-S928B",    "16-SDK36", 1440, 3088, ""),
        new DeviceIdentity("samsung",  "SM-A546B",    "15-SDK35", 1080, 2340, ""),
        new DeviceIdentity("Google",   "Pixel 9 Pro", "16-SDK36", 1280, 2856, ""),
        new DeviceIdentity("Google",   "Pixel 8",     "15-SDK35", 1080, 2400, ""),
        new DeviceIdentity("OnePlus",  "CPH2451",     "15-SDK35", 1240, 2772, ""),
        new DeviceIdentity("Xiaomi",   "2201117TG",   "14-SDK34", 1220, 2712, ""),
        new DeviceIdentity("motorola", "moto g84",    "15-SDK35", 1080, 2400, ""),
        new DeviceIdentity("Nothing",  "A065",        "15-SDK35", 1080, 2412, ""),
    };

    private static final Random RNG = new Random();

    /** Pick a random profile and assign it a fresh installation UUID. */
    static DeviceIdentity random() {
        DeviceIdentity d = POOL[RNG.nextInt(POOL.length)];
        return new DeviceIdentity(d.manufacturer, d.model, d.osVersion,
                d.screenW, d.screenH, UUID.randomUUID().toString());
    }
}
