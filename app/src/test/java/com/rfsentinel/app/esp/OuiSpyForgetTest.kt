package com.rfsentinel.app.esp

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.util.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class OuiSpyForgetTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val board = "AA:BB:CC:DD:EE:01"

    @Test
    fun forgettingAnOfflineBoardStopsItsReconnectsAndKeepsOtherRadios() {
        Prefs.setOuiSpyBoard(app, board)
        Prefs.setMeshRadio(app, "AA:BB:CC:DD:EE:02")
        // Bluetooth is off here, so the board can't be reached: it waits to retry.
        OuiSpyBle.start(app, board, relayEverything = true) {}
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(OuiSpyBle.connected)
        assertTrue(OuiSpyBle.status.isNotBlank())

        OuiSpyBle.forget(app)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(Prefs.ouiSpyBoard(app))
        assertEquals("AA:BB:CC:DD:EE:02", Prefs.meshRadio(app))
        assertFalse(OuiSpyBle.connected)
        assertEquals("", OuiSpyBle.status)

        // The pending reconnect never fires, even if the board comes back.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(OuiSpyBle.connected)
        assertEquals("", OuiSpyBle.status)
    }
}
