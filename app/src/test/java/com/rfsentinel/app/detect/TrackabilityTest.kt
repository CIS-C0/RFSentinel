package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackabilityTest {

    private val day = 24 * 60 * 60_000L

    @Test
    fun theSameAddressOnSeveralDaysIsTrackableWhateverItsType() {
        assertEquals("Trackable: the same address was heard over 3 days, in 4 scans",
            AddressType.LIKELY_RPA.trackability(0, 2 * day + 5, 4, false))
    }

    @Test
    fun aLinkedRotationCanStillBeFollowed() {
        assertTrue(AddressType.RESOLVABLE_PRIVATE.trackability(null, null, 0, true).contains("can still be followed"))
    }

    @Test
    fun withoutHistoryItIsTheAddressType() {
        assertEquals(AddressType.PUBLIC.trackable, AddressType.PUBLIC.trackability(null, null, 0, false))
        assertTrue(AddressType.RANDOM_STATIC.trackability(0, 3_600_000, 2, false).endsWith("2 scans within a day"))
    }
}
