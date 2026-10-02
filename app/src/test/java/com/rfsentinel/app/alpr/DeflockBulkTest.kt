package com.rfsentinel.app.alpr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up positions and ids, shaped like cdn.deflock.me/regions files. */
class DeflockBulkTest {

    private val index = DeflockBulk.parseIndex(
        """{"expiration_utc": 1, "regions": ["40/-80", "20/-100", "40/0", "60/-60", "-40/140", "0/-80"],
            "tile_url": "https://cdn.deflock.me/regions/{lat}/{lon}.json?v=7", "tile_size_degrees": 20}"""
    )

    @Test
    fun picksOnlyNorthAmericanTiles() {
        assertEquals(listOf("40/-80", "20/-100", "60/-60"), DeflockBulk.tilesFor(index, DeflockBulk.NORTH_AMERICA))
        assertEquals("https://cdn.deflock.me/regions/40/-80.json?v=7", DeflockBulk.tileUrl(index, "40/-80"))
    }

    @Test
    fun nearbyAreaIsAbout100kmAndPicksItsTiles() {
        val box = DeflockBulk.boxAround(39.0, -98.0, 100.0)
        assertEquals(0.898, box[2] - 39.0, 0.01)          // ~100 km of latitude
        assertEquals(1.156, -98.0 - box[1], 0.02)         // wider in degrees of longitude at 39°N
        assertEquals(listOf("20/-100"), DeflockBulk.tilesFor(index, box))
        assertTrue(DeflockBulk.inBox(KnownCamera("node/1", 39.5, -97.5, null, null, null), box))
        assertTrue(!DeflockBulk.inBox(KnownCamera("node/2", 40.5, -97.5, null, null, null), box))
    }

    @Test
    fun flockCamerasMappedAsOrdinaryCamerasCount() {
        val json = """{"elements":[
            {"type":"node","id":7,"lat":41.0,"lon":-79.0,"tags":{"man_made":"surveillance","surveillance:type":"camera","manufacturer":"Flock Safety"}},
            {"type":"node","id":8,"lat":41.0,"lon":-79.0,"tags":{"man_made":"surveillance","surveillance:type":"camera","brand":"Axis"}},
            {"type":"way","id":9,"center":{"lat":41.1,"lon":-79.1},"tags":{"surveillance:type":"ALPR"}}]}"""
        val cams = KnownCameras.parse(json)
        assertEquals(listOf("node/7", "way/9"), cams.map { it.osmId })
        assertEquals("Flock Safety camera (probably a plate reader)", cams[0].label)
        assertEquals("Plate reader (ALPR)", cams[1].label)
    }

    @Test
    fun parsesTileNodes() {
        val cams = DeflockBulk.parseTile(
            """[{"id": 101, "lat": 41.5, "lon": -79.5, "tags": {"surveillance:brand": "Flock Safety", "direction": "SE"}},
                {"id": 102, "lat": 42.0, "lon": -78.0, "tags": {}}]"""
        )
        assertEquals(2, cams.size)
        assertEquals("node/101", cams[0].osmId)
        assertEquals("Flock Safety", cams[0].brand)
        assertEquals(135, cams[0].direction)
        assertTrue(cams.all { it.type == KnownCamera.Kind.ALPR })
    }

    @Test
    fun mergeAddsAndUpdatesButNeverDrops() {
        fun cam(id: String, lat: Double, lon: Double, kind: KnownCamera.Kind = KnownCamera.Kind.ALPR) =
            KnownCamera(id, lat, lon, null, null, null, kind)
        val current = listOf(
            cam("node/1", 41.0, -79.0),                              // not in DeFlock's snapshot: kept
            cam("way/2", 41.0, -79.0),                               // plate reader as a way: kept
            cam("node/3", 41.0, -79.0, KnownCamera.Kind.SPEED),      // speed camera: kept
            cam("node/4", 10.0, 10.0),                               // outside the tiles: kept
            cam("node/5", 41.2, -79.2)                               // also in the new set: not duplicated
        )
        val found = listOf(cam("node/5", 41.2, -79.2), cam("node/6", 42.0, -78.0))
        val merged = AlprStore.mergeCameras(current, listOf(DeflockBulk.tileBox("40/-80", 20)), found)
        assertEquals(listOf("node/1", "way/2", "node/3", "node/4", "node/5", "node/6"), merged.map { it.osmId })
    }
}
