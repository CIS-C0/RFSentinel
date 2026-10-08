package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

/**
 * Anonymous Waze account credentials, minted by the /rtserver/distrib/static
 * register endpoint. {@code community} is the username, {@code secret} the password.
 */
final class WazeCredentials {
    final String community;
    final String secret;

    WazeCredentials(String community, String secret) {
        this.community = community;
        this.secret = secret;
    }
}
