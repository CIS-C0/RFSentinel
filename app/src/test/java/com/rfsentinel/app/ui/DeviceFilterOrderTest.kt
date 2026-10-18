package com.rfsentinel.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFilterOrderTest {
    @Test
    fun theMainScreenOrderListsEveryFilterOnce() {
        assertEquals(DeviceFilter.entries.toSet(), DeviceFilter.MAIN_ORDER.toSet())
        assertEquals(DeviceFilter.entries.size, DeviceFilter.MAIN_ORDER.size)
    }
}
