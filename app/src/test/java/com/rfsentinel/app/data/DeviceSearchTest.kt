package com.rfsentinel.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Device history search: what's typed is matched literally, and a MAC is found with or without colons. */
class DeviceSearchTest {

    @Test
    fun typedTextIsMatchedLiterally() {
        assertEquals("%tile%", KnownDeviceDao.like("tile"))
        assertEquals("%50\\%\\_x%", KnownDeviceDao.like("50%_x"))
        assertEquals("%a\\\\b%", KnownDeviceDao.like("a\\b"))
    }

    @Test
    fun aMacWithoutColonsOrWithDashes() {
        assertEquals("%aabbcc%", KnownDeviceDao.likeHex("aa:bb:cc"))
        assertEquals("%aabbcc%", KnownDeviceDao.likeHex("aa-bb-cc"))
    }
}
