package com.rfsentinel.app.online.wazert;

// Vendored unmodified from highway-radar-sabre-plus (MIT) except for the
// package declaration. See LICENSE in this directory.

/**
 * A Waze alert decoded from a RealtimeAlert protobuf message (fetch-relevant
 * fields only).
 */
public final class WazeAlert {
    public final String uuid;
    public final long   id;
    public final String type;
    public final String subtype;
    public final double lon;
    public final double lat;
    public final int    magvar;
    public final long   pubMillis;
    public final Integer nThumbsUp;
    public final String street;
    public final String city;

    public WazeAlert(String uuid, long id, String type, String subtype,
                     double lon, double lat, int magvar, long pubMillis,
                     Integer nThumbsUp, String street, String city) {
        this.uuid = uuid;
        this.id = id;
        this.type = type;
        this.subtype = subtype;
        this.lon = lon;
        this.lat = lat;
        this.magvar = magvar;
        this.pubMillis = pubMillis;
        this.nThumbsUp = nThumbsUp;
        this.street = street;
        this.city = city;
    }
}
