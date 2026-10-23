package com.rfsentinel.app.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WigleTest {

    @Test
    fun recognisesAccessPointAddresses() {
        assertTrue(Wigle.isBssid("aa:bb:cc:dd:ee:ff"))
        assertTrue(Wigle.isBssid("AA-BB-CC-DD-EE-FF"))
        assertTrue(Wigle.isBssid(" aabbccddeeff "))
        assertFalse(Wigle.isBssid("aa:bb:cc:dd:ee"))
        assertFalse(Wigle.isBssid("PD-MDT"))
        assertFalse(Wigle.isBssid("deadbeefcafe1"))
        assertEquals("AA:BB:CC:DD:EE:FF", Wigle.bssid("aa-bb-cc-dd-ee-ff"))
        assertEquals("AA:BB:CC:DD:EE:FF", Wigle.bssid("aabbccddeeff"))
    }

    @Test
    fun searchesANameExactlyOrWithAWildcardAndAnAddressByItself() {
        assertTrue(Wigle.url("PD MDT", null).contains("&ssid=PD+MDT"))
        assertFalse(Wigle.url("PD MDT", null).contains("latrange"))
        assertTrue(Wigle.url("PD*", null).contains("&ssidlike=PD%25"))
        val byAddress = Wigle.url("aa-bb-cc-dd-ee-ff", null)
        assertTrue(byAddress.contains("&netid=AA%3ABB%3ACC%3ADD%3AEE%3AFF"))
        assertFalse(byAddress.contains("ssid="))
    }

    @Test
    fun nearSearchCoversABoxAroundThePosition() {
        val u = Wigle.url("Home", 45.0 to -73.0)
        fun v(k: String) = Regex("[?&]$k=([-0-9.]+)").find(u)!!.groupValues[1].toDouble()
        assertEquals(45.0 - 50 / 111.0, v("latrange1"), 0.001)
        assertEquals(45.0 + 50 / 111.0, v("latrange2"), 0.001)
        assertTrue(v("longrange1") < -73.5 && v("longrange2") > -72.5)
        assertEquals(45.1 to -73.2, Wigle.snap(45.123, -73.249))
    }

    @Test
    fun readsResultsAndLeavesOutThoseWithoutAPosition() {
        val json = """{"success":true,"totalResults":40,"results":[
            {"trilat":45.5,"trilong":-73.6,"ssid":"PD-MDT","netid":"aa:bb:cc:dd:ee:ff","encryption":"wpa2","channel":6,
             "lastupdt":"2025-06-01T12:00:00.000Z","road":"Main St","city":"Town","region":"QC","country":"CA"},
            {"trilat":0.0,"trilong":0.0,"ssid":"Nowhere","netid":"11:22:33:44:55:66"},
            {"ssid":"NoPos","netid":"11:22:33:44:55:77"},
            {"trilat":10.0,"trilong":20.0,"ssid":null,"netid":"11:22:33:44:55:88"}]}"""
        val (total, nets) = Wigle.parse(json)
        assertEquals(40, total)
        assertEquals(2, nets.size)
        val n = nets[0]
        assertEquals("PD-MDT", n.ssid)
        assertEquals("AA:BB:CC:DD:EE:FF", n.bssid)
        assertEquals(6, n.channel)
        assertEquals("Main St, Town, QC, CA", n.place)
        assertNull(nets[1].ssid)
        assertNull(nets[1].place)
    }

    @Test(expected = Wigle.Failure::class)
    fun aRefusedSearchIsAFailure() {
        Wigle.parse("""{"success":false,"message":"too many queries today"}""")
    }
}
