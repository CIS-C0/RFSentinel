package com.rfsentinel.app.detect

/**
 * Decoder for ASTM F3411 drone Remote ID broadcasts (the FAA / EU standard),
 * written from the message layout in opendroneid-core-c (Apache-2.0,
 * github.com/opendroneid/opendroneid-core-c). Remote ID is a legally required
 * public broadcast; reading it is purely passive.
 *
 * Transports handled:
 *  - BLE legacy/extended adverts: service data UUID 0xFFFA, payload
 *    [0x0D app code][counter][25-byte message or message pack]
 *  - WiFi beacons: vendor IE (221) with OUI FA:0B:BC, type 0x0D, then
 *    [counter][message pack]
 *  - The French "signalement electronique" (decree / arrêté of 27 Dec 2019, required in
 *    France since June 2020 for drones of 800 g and more): vendor IE with OUI 6A:5C:35,
 *    type 0x01, then TLVs (layout from the reference beacon github.com/khancyr/droneID_FR).
 */
object RemoteId {

    const val MESSAGE_SIZE = 25
    private const val APP_CODE = 0x0D

    /** Everything decoded so far for one aircraft; messages arrive one type at a time. */
    data class Info(
        val uasId: String? = null,
        val idType: String? = null,
        val uaType: String? = null,
        val status: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeGeoM: Double? = null,
        val heightM: Double? = null,
        val speedMs: Double? = null,
        val verticalSpeedMs: Double? = null,
        val directionDeg: Int? = null,
        val operatorLatitude: Double? = null,
        val operatorLongitude: Double? = null,
        val selfIdDescription: String? = null,
        val operatorId: String? = null
    ) {
        val hasPosition get() = latitude != null && longitude != null
        val hasOperatorPosition get() = operatorLatitude != null && operatorLongitude != null
    }

    private val UA_TYPES = arrayOf(
        "Not declared", "Aeroplane", "Helicopter / multirotor", "Gyroplane", "Hybrid lift (VTOL)",
        "Ornithopter", "Glider", "Kite", "Free balloon", "Captive balloon", "Airship",
        "Parachute", "Rocket", "Tethered powered aircraft", "Ground obstacle", "Other"
    )
    private val ID_TYPES = arrayOf("None", "Serial number (ANSI/CTA-2063-A)", "CAA registration", "UTM UUID", "Specific session ID")
    private val STATUS = arrayOf("Undeclared", "Ground", "Airborne", "Emergency", "Remote ID system failure")

    /** Decodes BLE service data for UUID 0xFFFA, merging into [prev]. Null if not Remote ID. */
    fun decodeBle(serviceData: ByteArray, prev: Info?): Info? {
        if (serviceData.size < 2 + MESSAGE_SIZE || Bytes.u8(serviceData, 0) != APP_CODE) return null
        return decodeMessage(serviceData, 2, prev ?: Info())
    }

    /** Decodes a WiFi vendor IE payload (starting at the OUI), merging into [prev]. */
    fun decodeWifiIe(ie: ByteArray, prev: Info?): Info? {
        if (isFrenchIe(ie)) return decodeFrench(ie, prev ?: Info())
        if (!isAstmWifiIe(ie) || ie.size < 5 + 3) return null
        return decodeMessage(ie, 5, prev ?: Info())
    }

    /** ASTM F3411 or French drone ID vendor IE. */
    fun isWifiIe(ie: ByteArray): Boolean = isAstmWifiIe(ie) || isFrenchIe(ie)

    fun isAstmWifiIe(ie: ByteArray): Boolean =
        ie.size >= 4 && Bytes.u8(ie, 0) == 0xFA && Bytes.u8(ie, 1) == 0x0B &&
            Bytes.u8(ie, 2) == 0xBC && Bytes.u8(ie, 3) == APP_CODE

    fun isFrenchIe(ie: ByteArray): Boolean =
        ie.size >= 4 && Bytes.u8(ie, 0) == 0x6A && Bytes.u8(ie, 1) == 0x5C &&
            Bytes.u8(ie, 2) == 0x35 && Bytes.u8(ie, 3) == 0x01

    /**
     * French TLVs: 2 = French ID (30 chars), 3 = ANSI/CTA-2063 serial, 4/5 = latitude /
     * longitude (int32 big-endian, degrees x 1e5), 6 = altitude MSL (int16 m), 7 = height
     * above take-off (int16 m), 8/9 = take-off point, 10 = ground speed (m/s), 11 = heading (deg).
     */
    private fun decodeFrench(b: ByteArray, info: Info): Info {
        var acc = info.copy(uaType = info.uaType ?: "Drone (French electronic ID)")
        var i = 4
        while (i + 2 <= b.size) {
            val type = Bytes.u8(b, i)
            val len = Bytes.u8(b, i + 1)
            val v = i + 2
            if (v + len > b.size) break
            fun int(): Int {
                var x = 0
                for (k in 0 until len) x = (x shl 8) or Bytes.u8(b, v + k)
                val bits = len * 8
                return if (bits in 1..31 && x and (1 shl (bits - 1)) != 0) x - (1 shl bits) else x
            }
            acc = when (type) {
                2 -> Bytes.ascii(b, v, len)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { acc.copy(uasId = it, idType = "French ID (signalement électronique)") } ?: acc
                3 -> if (acc.idType?.startsWith("French") == true) acc else Bytes.ascii(b, v, len)?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { acc.copy(uasId = it, idType = ID_TYPES[1]) } ?: acc
                4 -> if (len == 4) acc.copy(latitude = int() * 1e-5) else acc
                5 -> if (len == 4) acc.copy(longitude = int() * 1e-5) else acc
                6 -> if (len == 2) acc.copy(altitudeGeoM = int().toDouble()) else acc
                7 -> if (len == 2) acc.copy(heightM = int().toDouble()) else acc
                8 -> if (len == 4) acc.copy(operatorLatitude = int() * 1e-5) else acc
                9 -> if (len == 4) acc.copy(operatorLongitude = int() * 1e-5) else acc
                10 -> if (len == 1) acc.copy(speedMs = Bytes.u8(b, v).toDouble()) else acc
                11 -> if (len == 2) acc.copy(directionDeg = int().takeIf { it in 0..359 } ?: acc.directionDeg) else acc
                else -> acc
            }
            i = v + len
        }
        // (0, 0) means no GPS fix yet.
        if (acc.latitude == 0.0 && acc.longitude == 0.0) acc = acc.copy(latitude = info.latitude, longitude = info.longitude)
        if (acc.operatorLatitude == 0.0 && acc.operatorLongitude == 0.0)
            acc = acc.copy(operatorLatitude = info.operatorLatitude, operatorLongitude = info.operatorLongitude)
        return acc
    }

    fun isBleRemoteId(serviceData: ByteArray): Boolean =
        serviceData.size >= 2 + MESSAGE_SIZE && Bytes.u8(serviceData, 0) == APP_CODE

    private fun decodeMessage(b: ByteArray, off: Int, info: Info): Info {
        if (off >= b.size) return info
        val type = Bytes.u8(b, off) ushr 4
        if (type == 0xF) return decodePack(b, off, info)
        if (off + MESSAGE_SIZE > b.size) return info
        return when (type) {
            0 -> decodeBasicId(b, off, info)
            1 -> decodeLocation(b, off, info)
            3 -> info.copy(selfIdDescription = Bytes.ascii(b, off + 2, 23) ?: info.selfIdDescription)
            4 -> decodeSystem(b, off, info)
            5 -> info.copy(operatorId = Bytes.ascii(b, off + 2, 20) ?: info.operatorId)
            else -> info // 2 = authentication: nothing user-facing
        }
    }

    private fun decodePack(b: ByteArray, off: Int, info: Info): Info {
        if (off + 3 > b.size) return info
        val size = Bytes.u8(b, off + 1)
        val count = Bytes.u8(b, off + 2)
        if (size != MESSAGE_SIZE || count == 0 || count > 9) return info
        var acc = info
        for (i in 0 until count) {
            val msgOff = off + 3 + i * MESSAGE_SIZE
            if (msgOff + MESSAGE_SIZE > b.size) break
            if (Bytes.u8(b, msgOff) ushr 4 == 0xF) continue // no nested packs
            acc = decodeMessage(b, msgOff, acc)
        }
        return acc
    }

    private fun decodeBasicId(b: ByteArray, off: Int, info: Info): Info {
        val idType = Bytes.u8(b, off + 1) ushr 4
        val uaType = Bytes.u8(b, off + 1) and 0x0F
        val id = Bytes.ascii(b, off + 2, 20)
        return info.copy(
            uasId = id ?: info.uasId,
            idType = ID_TYPES.getOrNull(idType) ?: info.idType,
            uaType = UA_TYPES.getOrNull(uaType) ?: info.uaType
        )
    }

    private fun decodeLocation(b: ByteArray, off: Int, info: Info): Info {
        val flags = Bytes.u8(b, off + 1)
        val speedMult = flags and 0x01
        val ewDirection = (flags ushr 1) and 0x01
        val status = flags ushr 4
        val dirEnc = Bytes.u8(b, off + 2)
        val speedEnc = Bytes.u8(b, off + 3)
        val vSpeedEnc = b[off + 4].toInt() // signed
        val lat = Bytes.i32le(b, off + 5)
        val lon = Bytes.i32le(b, off + 9)
        val altGeo = Bytes.u16le(b, off + 15)
        val height = Bytes.u16le(b, off + 17)

        val direction = dirEnc + if (ewDirection == 1) 180 else 0
        val speed = when {
            speedEnc == 255 -> null
            speedMult == 1 -> speedEnc * 0.75 + 255 * 0.25
            else -> speedEnc * 0.25
        }
        return info.copy(
            status = STATUS.getOrNull(status) ?: info.status,
            latitude = if (lat != 0) lat * 1e-7 else info.latitude,
            longitude = if (lon != 0) lon * 1e-7 else info.longitude,
            altitudeGeoM = decodeAltitude(altGeo) ?: info.altitudeGeoM,
            heightM = decodeAltitude(height) ?: info.heightM,
            speedMs = speed ?: info.speedMs,
            verticalSpeedMs = if (vSpeedEnc == 63) info.verticalSpeedMs else vSpeedEnc * 0.5,
            directionDeg = if (direction in 0..359) direction else info.directionDeg
        )
    }

    private fun decodeSystem(b: ByteArray, off: Int, info: Info): Info {
        val lat = Bytes.i32le(b, off + 2)
        val lon = Bytes.i32le(b, off + 6)
        return info.copy(
            operatorLatitude = if (lat != 0) lat * 1e-7 else info.operatorLatitude,
            operatorLongitude = if (lon != 0) lon * 1e-7 else info.operatorLongitude
        )
    }

    /** Encoded 0 means "unknown" (-1000 m). */
    private fun decodeAltitude(enc: Int): Double? = if (enc == 0) null else enc * 0.5 - 1000.0
}
