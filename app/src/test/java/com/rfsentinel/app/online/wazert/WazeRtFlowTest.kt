package com.rfsentinel.app.online.wazert

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The whole Waze protocol flow against a fake server: register, log in, handshake and the query boxes.
 * It decodes what comes back, and checks every request that goes out: the exact position never does,
 * only the rounded one. (The fake speaks the field numbers of waze.proto; it is not recorded Waze traffic.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WazeRtFlowTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    // A made-up place: nothing here is anyone's real location.
    private val exactLat = 10.1234567
    private val exactLon = -20.6543211

    private class FakeServer : WazeHttpClient() {
        /** Every request: the URL path and the body as text. */
        val requests = ArrayList<Pair<String, String>>()
        var registrations = 0
        var handshakeAlerts: List<WazeProto.Element> = emptyList()
        /** Elements added to the answer of the very next query box, once. */
        var nextQueryExtras: List<WazeProto.Element> = emptyList()

        private fun batch(vararg els: WazeProto.Element): ByteArray =
            WazeProto.Batch.newBuilder().apply { els.forEach { addElement(it) } }.build().toByteArray()

        private fun filler() = WazeProto.Element.newBuilder().setRequestId(1).build()

        override fun clearCookies() {}

        override fun post(url: String, body: ByteArray, headers: MutableMap<String, String>?): WazeHttpClient.HttpResult {
            val path = url.substringAfter("waze.com")
            requests += path to String(body, Charsets.UTF_8)
            return when (path) {
                WazeConstants.PATH_STATIC -> {
                    registrations++
                    WazeHttpClient.HttpResult(200, batch(WazeProto.Element.newBuilder().setRegisterSuccessful(
                        WazeProto.RegisterSuccessful.newBuilder().setUsername("anon").setPassword("secret")).build()))
                }
                WazeConstants.PATH_LOGIN -> WazeHttpClient.HttpResult(200, batch(WazeProto.Element.newBuilder().setLoginResponse(
                    WazeProto.LoginResponse.newBuilder().setLoginSuccess(
                        WazeProto.LoginSuccess.newBuilder().setServerSessionId(42).setSecretKey("key").setGlobalUserId("7"))).build()))
                else -> {
                    val text = String(body, Charsets.UTF_8)
                    if (text.startsWith("SeeMe")) WazeHttpClient.HttpResult(200, batch(filler(), *handshakeAlerts.toTypedArray()))
                    else {
                        val extras = nextQueryExtras
                        nextQueryExtras = emptyList()
                        WazeHttpClient.HttpResult(200, batch(filler(), *extras.toTypedArray()))
                    }
                }
            }
        }
    }

    private fun alert(uuid: String, type: WazeProto.AlertType, sub: WazeProto.AlertSubType, lat: Double, lon: Double,
                      thumbs: Int = 0, street: String = "") =
        WazeProto.Element.newBuilder().setAddAlertAction(
            WazeProto.AddAlertAction.newBuilder().setRealtimeAlert(
                WazeProto.RealtimeAlert.newBuilder().setId(1).setAlertUuid(uuid)
                    .setAlertInfo(WazeProto.AlertInfo.newBuilder().setType(type).setSubType(sub)
                        .setPosition(WazeProto.Coordinate.newBuilder()
                            .setLatTimes1000000((lat * 1e6).roundToInt()).setLonTimes1000000((lon * 1e6).roundToInt())))
                    .setAlertReportingInfo(WazeProto.AlertReportingInfo.newBuilder()
                        .setReportTime(1_700_000_000L).setThumbsUpCount(thumbs)
                        .setAlertAddress(WazeProto.AlertAddress.newBuilder().setStreet(street).setCity("Sampleville")))
            )).build()

    private fun police(uuid: String = "p1") =
        alert(uuid, WazeProto.AlertType.POLICE, WazeProto.AlertSubType.POLICE_HIDING, 10.13, -20.65, 3, "Example St")

    private fun removal(uuid: String) = WazeProto.Element.newBuilder().setOldCommand("RmAlert,$uuid").build()

    @Test
    fun theWholeFlowDecodesAlertsAndNeverSendsTheExactPosition() {
        val server = FakeServer().apply {
            handshakeAlerts = listOf(police(), alert("h1", WazeProto.AlertType.HAZARD, WazeProto.AlertSubType.HAZARD_ON_ROAD_POT_HOLE, 10.13, -20.66))
        }
        val fetcher = WazeRtFetcher(ctx, "na", server)

        val alerts = fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE", "HAZARD"))

        // What came back, decoded.
        assertEquals(listOf("p1", "h1"), alerts.map { it.uuid }.sortedByDescending { it == "p1" })
        val p = alerts.single { it.uuid == "p1" }
        assertEquals("POLICE", p.type)
        assertEquals("POLICE_HIDING", p.subtype)
        assertEquals(3, p.thumbsUp)
        assertEquals("Example St", p.street)
        assertEquals("Sampleville", p.city)
        assertEquals(10.13, p.lat, 1e-6)
        assertEquals(-20.65, p.lon, 1e-6)
        assertEquals(1_700_000_000_000L, p.pubMillis)

        // The shape of the exchange: one account, one login, one handshake, four boxes.
        assertEquals(1, server.registrations)
        assertEquals(1, server.requests.count { it.first == WazeConstants.PATH_LOGIN })
        assertEquals(1, server.requests.count { it.second.startsWith("SeeMe") })
        assertEquals(WazeRtFetcher.boxSteps(8_000.0), server.requests.count { it.second.startsWith("MapDisplayed") })

        // What went out: the rounded position only.
        val snapped = WazeRtFetcher.snapToGrid(exactLat, exactLon)
        assertTrue(abs(snapped[0] - exactLat) > 1e-6 && abs(snapped[1] - exactLon) > 1e-6) // it really was rounded
        for ((_, body) in server.requests) {
            assertFalse("exact latitude in a request", body.contains("10.12345"))
            assertFalse("exact longitude in a request", body.contains("20.65432"))
            for (line in body.lines()) {
                val parts = line.split(",")
                when {
                    line.startsWith("Location,") -> {
                        assertEquals(snapped[1], parts[1].toDouble(), 1e-9)
                        assertEquals(snapped[0], parts[2].toDouble(), 1e-9)
                    }
                    line.startsWith("MapDisplayed,") -> { // the centre of the box is field 9 (lon) and 10 (lat)
                        assertEquals(snapped[1], parts[9].toDouble(), 1e-5)
                        assertEquals(snapped[0], parts[10].toDouble(), 1e-5)
                    }
                    line.startsWith("ProtoBase64,") -> {
                        val batch = WazeProto.Batch.parseFrom(Base64.getDecoder().decode(line.removePrefix("ProtoBase64,")))
                        for (el in batch.elementList) if (el.hasClientInfo()) {
                            val pos = el.clientInfo.lastPosition
                            // Rounded to the grid, then blurred by up to 500 m more: never the exact position.
                            assertTrue(abs(pos.latTimes1000000 / 1e6 - snapped[0]) * 110_574 <= 501)
                            assertTrue(abs(pos.lonTimes1000000 / 1e6 - snapped[1]) * WazeConstants.mPerDegLon(snapped[0]) <= 501)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun onlyTheWantedTypesComeBack() {
        val server = FakeServer().apply {
            handshakeAlerts = listOf(police(), alert("h1", WazeProto.AlertType.HAZARD, WazeProto.AlertSubType.HAZARD_ON_ROAD, 10.13, -20.66))
        }
        val alerts = WazeRtFetcher(ctx, "na", server).fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE"))
        assertEquals(listOf("p1"), alerts.map { it.uuid })
    }

    @Test
    fun aClearedAlertDisappearsOnTheNextCheck() {
        val server = FakeServer().apply { handshakeAlerts = listOf(police("p1"), police("p2")) }
        val fetcher = WazeRtFetcher(ctx, "na", server)
        assertEquals(setOf("p1", "p2"), fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE")).map { it.uuid }.toSet())
        server.nextQueryExtras = listOf(removal("p1"))
        assertEquals(listOf("p2"), fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE")).map { it.uuid })
    }

    @Test
    fun forgettingTheAccountMakesTheNextCheckRegisterAgain() {
        val server = FakeServer().apply { handshakeAlerts = listOf(police()) }
        val fetcher = WazeRtFetcher(ctx, "na", server)
        fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE"))
        fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE"))
        assertEquals("the account is reused", 1, server.registrations)
        WazeRtFetcher.forgetStoredAccount(ctx)
        fetcher.fetchAlertsNear(exactLat, exactLon, 5_000.0, setOf("POLICE"))
        assertEquals("forgotten: a new one is made", 2, server.registrations)
    }

    @Test
    fun theLoginBlurStaysWithinFiveHundredMetres() {
        val device = DeviceIdentity.random()
        repeat(500) {
            val line = WazeRtCodec.buildClientInfoLine(device, exactLon, exactLat)
            val batch = WazeProto.Batch.parseFrom(Base64.getDecoder().decode(line.removePrefix("ProtoBase64,")))
            val pos = batch.getElement(0).clientInfo.lastPosition
            assertTrue(abs(pos.latTimes1000000 / 1e6 - exactLat) * 110_574 <= 501)
            assertTrue(abs(pos.lonTimes1000000 / 1e6 - exactLon) * WazeConstants.mPerDegLon(exactLat) <= 501)
        }
    }

    /**
     * A response encoded by hand from the field numbers in waze.proto (tools: a few lines of Python, not the
     * generated classes), so renumbering a field breaks this test instead of silently losing alerts.
     * One police alert (west of the prime meridian, so a negative longitude) and one removal.
     */
    @Test
    fun handEncodedBytesDecodeToTheExpectedAlert() {
        val hex = "ca3e53a2a9014f0a4d08b960121b080210ca011a12a8068fae93f6ffffffffff01b006c0f1e904305a1a232080e2cfaa064219120a" +
            "4578616d706c652053741a0b53616d706c6576696c6c6548033206757569642d31ca3e118a7d0e526d416c6572742c757569642d30"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val batch = WazeProto.Batch.parseFrom(bytes)

        val alert = WazeRtCodec.parseAlerts(batch).single()
        assertEquals("uuid-1", alert.uuid)
        assertEquals("POLICE", alert.type)
        assertEquals("POLICE_HIDING", alert.subtype)
        assertEquals(10.123456, alert.lat, 1e-9)
        assertEquals(-20.654321, alert.lon, 1e-9)
        assertEquals(3, alert.nThumbsUp)
        assertEquals("Example St", alert.street)
        assertEquals("Sampleville", alert.city)
        assertEquals(1_700_000_000_000L, alert.pubMillis)
        assertEquals(listOf("uuid-0"), WazeRtCodec.parseRemovedAlertIds(batch))
    }
}
