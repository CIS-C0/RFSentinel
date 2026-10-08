package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

/**
 * Constants and host resolution for the Waze mobile-app "RT" protocol.
 */
final class WazeConstants {
    static final int    PROTOCOL_VERSION       = 234;
    static final String APP_VERSION            = "5.17.1.0";

    static final String PATH_LOGIN             = "/rtserver/distrib/login";
    static final String PATH_STATIC            = "/rtserver/distrib/static";
    static final String PATH_COMMAND           = "/rtserver/distrib/command";

    static final String WAIT_TIMEOUT_LOGIN     = "8500";
    static final String WAIT_TIMEOUT_COMMAND   = "10500";

    static final long   SESSION_IDLE_TIMEOUT_MS = 100_000L;
    static final long   KEEPALIVE_INTERVAL_MS   = 60_000L;
    static final int    MAX_CONSECUTIVE_REJECTIONS = 10;
    static final int    MAX_ACCOUNTS_PER_DAY    = 10;
    static final int    NUM_QUERY_SLOTS         = 5;
    static final int    TILE_NUM_ROWS           = 18000;
    static final double M_PER_DEG_LAT           = 110574.0;

    private WazeConstants() {}

    static double mPerDegLon(double lat) {
        return Math.cos(Math.toRadians(lat)) * 111320.0;
    }

    /** RT server host for a region ("na" covers all of California). */
    static String rtHost(String region) {
        if ("na".equals(region)) return "rt-xlb-am.waze.com";
        if ("il".equals(region)) return "rt-xlb-il.waze.com";
        return "rt-xlb-row.waze.com";
    }

    static String tileHost(String region) {
        if ("na".equals(region)) return "ctilesgcs-am.waze.com";
        if ("il".equals(region)) return "ctilesgcs-il.waze.com";
        return "ctilesgcs-row.waze.com";
    }
}
