package com.rfsentinel.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import com.rfsentinel.app.service.DeviceRegistry
import com.rfsentinel.app.ui.DeviceSections
import org.json.JSONArray
import org.json.JSONObject

/**
 * A device's page as it was when last heard ([LastHeard]): kept apart from [KnownDeviceEntity] so the
 * device history stays small and quick to search.
 */
@Entity(tableName = "device_details")
data class DeviceDetailsEntity(
    @PrimaryKey val mac: String,
    val savedAt: Long,
    val json: String
)

@Dao
interface DeviceDetailsDao {
    @Query("SELECT * FROM device_details WHERE mac = :mac")
    suspend fun get(mac: String): DeviceDetailsEntity?

    @Upsert
    suspend fun upsertAll(rows: List<DeviceDetailsEntity>)

    /** Details of devices the history no longer has (pruned or forgotten). */
    @Query("DELETE FROM device_details WHERE mac NOT IN (SELECT mac FROM known_devices)")
    suspend fun pruneOrphans(): Int

    /** Keeps the newest [keep] pages and those of your favourite, named or noted devices: every device heard gets one. */
    @Query("DELETE FROM device_details WHERE mac NOT IN (SELECT mac FROM device_details ORDER BY savedAt DESC LIMIT :keep) " +
        "AND mac NOT IN (SELECT mac FROM known_devices WHERE favorite = 1 OR alias IS NOT NULL OR note IS NOT NULL)")
    suspend fun keepNewest(keep: Int): Int
}

/**
 * Everything a device's page showed the last time it was heard - why it was flagged, identity, Remote ID, raw
 * advertisement, signal - so its page from the device history shows the same once it's out of range.
 */
object LastHeard {

    /** The last ones saved for a while; at most this many signal readings. */
    private const val MAX_SAMPLES = 120

    class Saved(
        val at: Long,
        val firstSeen: Long,
        val rssi: Int,
        val bestRssi: Int,
        val distanceM: Double,
        val sightings: Int,
        val following: Boolean,
        val hits: List<Hit>,
        val sections: List<com.rfsentinel.app.ui.DeviceSection>,
        val samples: List<DeviceRegistry.Sample>,
        /** Where the drone and its operator were, for the map buttons. */
        val drone: Pair<Double, Double>?,
        val operator: Pair<Double, Double>?,
        val uasId: String?
    ) {
        val best: Hit? get() = hits.firstOrNull()
    }

    fun of(context: Context, s: DeviceRegistry.Snapshot): Saved {
        val r = s.remoteId
        return Saved(
            at = s.lastSeen, firstSeen = s.firstSeen, rssi = s.rssi, bestRssi = s.bestRssi, distanceM = s.distanceM,
            sightings = s.sightings, following = s.following, hits = s.hits,
            sections = DeviceSections.build(context, s.mac, s, s.known, emptyList(), emptyList(), saving = true),
            samples = s.history.takeLast(MAX_SAMPLES),
            drone = r?.takeIf { it.hasPosition }?.let { it.latitude!! to it.longitude!! },
            operator = r?.takeIf { it.hasOperatorPosition }?.let { it.operatorLatitude!! to it.operatorLongitude!! },
            uasId = r?.uasId
        )
    }

    fun toJson(v: Saved): String = JSONObject().apply {
        put("at", v.at); put("firstSeen", v.firstSeen); put("rssi", v.rssi); put("bestRssi", v.bestRssi)
        put("distanceM", v.distanceM); put("sightings", v.sightings); put("following", v.following)
        put("hits", JSONArray().apply {
            v.hits.forEach { h ->
                put(JSONObject().put("category", h.category.name).put("label", h.label).put("confidence", h.confidence)
                    .put("evidence", h.evidence).put("source", h.source))
            }
        })
        put("sections", JSONArray().apply {
            v.sections.forEach { (title, rows) ->
                put(JSONObject().put("title", title).put("rows", JSONArray().apply {
                    rows.forEach { (k, value) -> put(JSONArray().put(k).put(value)) }
                }))
            }
        })
        // Times relative to the last reading, to keep it small.
        put("samples", JSONArray().apply { v.samples.forEach { put(JSONArray().put(v.at - it.time).put(it.rssi)) } })
        v.drone?.let { put("drone", JSONArray().put(it.first).put(it.second)) }
        v.operator?.let { put("operator", JSONArray().put(it.first).put(it.second)) }
        v.uasId?.let { put("uasId", it) }
    }.toString()

    fun fromJson(json: String): Saved? = runCatching {
        val o = JSONObject(json)
        fun point(key: String) = o.optJSONArray(key)?.let { it.getDouble(0) to it.getDouble(1) }
        val at = o.getLong("at")
        Saved(
            at = at, firstSeen = o.optLong("firstSeen", at), rssi = o.optInt("rssi", -127), bestRssi = o.optInt("bestRssi", -127),
            distanceM = o.optDouble("distanceM", 0.0), sightings = o.optInt("sightings"), following = o.optBoolean("following"),
            hits = o.optJSONArray("hits")?.let { a ->
                (0 until a.length()).map { i ->
                    val h = a.getJSONObject(i)
                    Hit(Category.parse(h.optString("category")) ?: Category.CUSTOM, h.getString("label"),
                        h.optInt("confidence"), h.optString("evidence"), h.optString("source"))
                }
            }.orEmpty(),
            sections = o.optJSONArray("sections")?.let { a ->
                (0 until a.length()).map { i ->
                    val sec = a.getJSONObject(i)
                    val rows = sec.getJSONArray("rows")
                    sec.getString("title") to (0 until rows.length()).map { j ->
                        rows.getJSONArray(j).let { it.getString(0) to it.getString(1) }
                    }
                }
            }.orEmpty(),
            samples = o.optJSONArray("samples")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONArray(i).let { DeviceRegistry.Sample(at - it.getLong(0), it.getInt(1)) } }
            }.orEmpty(),
            drone = point("drone"), operator = point("operator"), uasId = o.optString("uasId").takeIf { o.has("uasId") }
        )
    }.getOrNull()
}
