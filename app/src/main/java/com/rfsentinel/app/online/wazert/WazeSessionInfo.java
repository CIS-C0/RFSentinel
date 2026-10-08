package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

/** Authenticated session state returned by a successful login. */
final class WazeSessionInfo {
    final long   serverSessionId;
    final String secretKey;
    final String globalUserId;

    WazeSessionInfo(long serverSessionId, String secretKey, String globalUserId) {
        this.serverSessionId = serverSessionId;
        this.secretKey = secretKey;
        this.globalUserId = globalUserId;
    }
}
