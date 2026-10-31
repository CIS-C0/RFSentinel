package com.rfsentinel.app.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedRouterTest {

    private fun hit(label: String) = Hit(Category.PUBLIC_SAFETY, label, 40, "", "")
    private val router = hit("Cradlepoint vehicle router - police cars, buses, stores, traffic-signal cabinets")

    @Test
    fun familiarRouterOnlyIsQuiet() {
        FixedRouter.load(listOf("00:30:44:aa:bb:cc"))
        assertTrue(FixedRouter.quiet("00:30:44:AA:BB:CC", listOf(router), 50))
        // On the Weak threshold every match alerts, familiar routers too.
        assertFalse(FixedRouter.quiet("00:30:44:AA:BB:CC", listOf(router), 0))
        // Something else backs it: it still alerts.
        assertFalse(FixedRouter.quiet("00:30:44:AA:BB:CC", listOf(router, hit("Police car network - Axon Fleet in-car video")), 50))
        // A router not heard often: alerts.
        assertFalse(FixedRouter.quiet("00:30:44:11:22:33", listOf(router), 50))
        assertFalse(FixedRouter.routerOnly(emptyList()))
    }
}
