package com.rfsentinel.app.util

import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.detect.Hit
import org.junit.Assert.assertEquals
import org.junit.Test

class CarMessagesTest {

    @Test
    fun spokenRepliesMapToCommands() {
        assertEquals(CarMessages.Command.MUTE, CarMessages.command("Mute"))
        assertEquals(CarMessages.Command.MUTE, CarMessages.command("please be quiet"))
        assertEquals(CarMessages.Command.MUTE, CarMessages.command("Snooze alerts."))
        assertEquals(CarMessages.Command.IGNORE, CarMessages.command("ignore it"))
        assertEquals(CarMessages.Command.NONE, CarMessages.command("ok thanks"))
        assertEquals(CarMessages.Command.NONE, CarMessages.command(null))
        assertEquals(CarMessages.Command.NONE, CarMessages.command("muted")) // whole words only
    }

    @Test
    fun carMessageReadsAsOneSentence() {
        val hit = Hit(Category.BODY_CAM, "Axon body camera", 85, "OUI", "test")
        assertEquals(
            "Nearby: Axon body camera. Strong match, about 40 m, signal -58 dBm.",
            NotificationHelper.carMessageText("Nearby: Axon body camera", hit, -58, 41.0)
        )
        val weak = Hit(Category.ALPR, "Plate camera", 30, "OUI", "test")
        assertEquals(
            "Nearby: Plate camera. Weak match, signal -90 dBm.",
            NotificationHelper.carMessageText("Nearby: Plate camera", weak, -90, null)
        )
    }
}
