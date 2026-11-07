package com.rfsentinel.app.ui

import com.rfsentinel.app.detect.Advert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRowsSsidTest {
    private val wifi = setOf(Advert.Source.WIFI)
    private val router = "Vehicle / cellular router"

    @Test fun `a flagged wifi router shows its network name before the vendor`() {
        val ssid = DeviceRows.routerSsid(true, wifi, "IBR900-abc", router)
        assertEquals("IBR900-abc", ssid)
        assertEquals("00:11:22:33:44:55 · \"IBR900-abc\" · Cradlepoint", DeviceRows.subtitle("00:11:22:33:44:55", ssid, "Cradlepoint"))
    }

    @Test fun `other rows keep the address and vendor`() {
        assertNull(DeviceRows.routerSsid(false, wifi, "IBR900-abc", router))
        assertNull(DeviceRows.routerSsid(true, setOf(Advert.Source.BLE), "IBR900-abc", router))
        assertNull(DeviceRows.routerSsid(true, wifi, "Pixel", "Phone"))
        assertNull(DeviceRows.routerSsid(true, wifi, null, router))
        assertEquals("00:11:22:33:44:55 · Cradlepoint", DeviceRows.subtitle("00:11:22:33:44:55", null, "Cradlepoint"))
        assertEquals("00:11:22:33:44:55", DeviceRows.subtitle("00:11:22:33:44:55", null, null))
    }
}
