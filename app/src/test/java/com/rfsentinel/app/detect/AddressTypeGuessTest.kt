package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The address type when the phone doesn't report it (before Android 15): worked out from the address itself. */
class AddressTypeGuessTest {

    @Test
    fun aRegisteredMakersAddressIsProbablyPublic() {
        assertEquals(AddressType.LIKELY_PUBLIC, AddressType.guess("5C:1B:2E:88:40:11", registered = true, alwaysRandom = false))
    }

    @Test
    fun appleAndFindMyAreNeverPublicEvenWhenTheAddressLooksRegistered() {
        assertEquals(AddressType.LIKELY_RPA, AddressType.guess("5C:1B:2E:88:40:11", registered = true, alwaysRandom = true))
        assertEquals(AddressType.LIKELY_STATIC, AddressType.guess("D2:5E:91:3A:07:BC", registered = true, alwaysRandom = true))
    }

    @Test
    fun otherwiseTheRandomSubTypeFromTheTopBits() {
        assertEquals(AddressType.LIKELY_STATIC, AddressType.guess("D2:5E:91:3A:07:BC", registered = false, alwaysRandom = false))
        assertEquals(AddressType.LIKELY_RPA, AddressType.guess("6E:0A:77:12:C3:95", registered = false, alwaysRandom = false))
        assertEquals(AddressType.LIKELY_NON_RESOLVABLE, AddressType.guess("1A:31:C4:0B:9E:22", registered = false, alwaysRandom = false))
        assertEquals("reserved bits", AddressType.UNKNOWN, AddressType.guess("8A:31:C4:0B:9E:22", registered = false, alwaysRandom = false))
    }

    @Test
    fun noTrackabilityTextBlamesTheOtherDevicesAndroidVersion() {
        for (t in AddressType.entries) assertFalse(t.name, "Android 15" in t.trackable)
    }
}
