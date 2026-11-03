package com.rfsentinel.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoliceSsidTest {

    @Test
    fun axonFleetNamesMatch() {
        val axon = SignatureEngine.policeVehicleSsid("Axon12-5g")!!
        assertEquals(Category.PUBLIC_SAFETY, axon.category)
        assertTrue(axon.confidence >= 80)
        assertTrue(SignatureEngine.policeVehicleSsid("AXON 304 5G")!!.confidence >= 80)
        val number = SignatureEngine.policeVehicleSsid("8554-5g")!!
        assertTrue(number.confidence < 50) // weak alone
    }

    @Test
    fun cradlepointDefaultsMatch() {
        assertTrue(SignatureEngine.policeVehicleSsid("IBR900-3ab") != null)
        assertTrue(SignatureEngine.policeVehicleSsid("IBR1700-1F2-5g") != null)
        assertTrue(SignatureEngine.policeVehicleSsid("R1900-c0d") != null)
    }

    @Test
    fun emergencyNamesMatchAsWholeWords() {
        for (name in listOf("SHERIFF-12", "PD_Unit4", "K9-12", "Patrol 7"))
            assertTrue(name, SignatureEngine.policeVehicleSsid(name)!!.confidence < 50) // weak alone
        // Fire and EMS vehicles, and words they share with police, aren't flagged.
        for (name in listOf("Medic 7", "Engine12 FD", "FIRE-3", "EMS_Unit2", "Ambulance 4", "Rescue1", "MDT-4", "Dispatch 2"))
            assertNull(name, SignatureEngine.policeVehicleSsid(name))
        // A familiar router named for an agency still alerts: the name match isn't a router-only match.
        assertTrue(!FixedRouter.routerOnly(listOf(SignatureEngine.policeVehicleSsid("SHERIFF-12")!!)))
    }

    @Test
    fun ordinaryNetworksDont() {
        for (name in listOf("NETGEAR42-5G", "Home-5G", "papillon", "Axon", "IBR900", "MBR1400-3e2", "5g", "1-5g",
                "Firefly", "Spdnet", "Remedics", "Patrolla"))
            assertNull(name, SignatureEngine.policeVehicleSsid(name))
    }

    @Test
    fun ignoredNamesMatchWhereAWordStartsAndNeverHidePolice() {
        val words = listOf("ATT", "Rogers", "Virgin")
        for (name in listOf("ATT4ia9c2", "Rogers-5G", "MyRogers", "rogers home", "Virgin Hub 7"))
            assertTrue(name, SignatureEngine.ignoredSsid(name, words))
        // Inside another word: not ignored, as a plain contains would.
        for (name in listOf("SEATTLE-FLEET", "Seattle12", "Matt's iPhone", "PATTERSON"))
            assertTrue(name, !SignatureEngine.ignoredSsid(name, words))
        // A name that also says police stays flagged.
        for (name in listOf("SEATTLE-PD", "ATT-PD-12", "Virginia State Police", "Rogers Sheriff 3"))
            assertTrue(name, !SignatureEngine.ignoredSsid(name, words))
    }
}
