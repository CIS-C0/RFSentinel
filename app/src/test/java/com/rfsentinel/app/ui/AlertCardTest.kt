package com.rfsentinel.app.ui

import android.app.Application
import android.location.Location
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.rfsentinel.app.R
import com.rfsentinel.app.databinding.ActivityMapBinding
import com.rfsentinel.app.detect.Category
import com.rfsentinel.app.util.Prefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Alerts as a card over the full screen map: what it shows, when it goes, and when it stays quiet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AlertCardTest {
    private lateinit var activity: AppCompatActivity
    private lateinit var b: ActivityMapBinding
    private lateinit var card: AlertCardController
    private var looked: Pair<Double, Double>? = null

    private fun alert(
        label: String = "Axon body camera", category: Category = Category.BODY_CAM, confidence: Int = 90, following: Boolean = false,
        lat: Double? = null, lon: Double? = null, evidence: String = "BWCDEVICE tag in advert payload"
    ) = AlertCards.Card("k1", null, label, evidence, category, confidence, following, -58, lat, lon, System.currentTimeMillis())

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).create().get()
        activity.setTheme(R.style.Theme_RFSentinel)
        b = ActivityMapBinding.inflate(LayoutInflater.from(activity))
        looked = null
        Prefs.setDiscreetMode(activity, false)
        Prefs.setAlertsSnoozedUntil(activity, 0L)
        card = AlertCardController(activity, b, { Location("t").apply { latitude = 10.0; longitude = 20.0 } }) { la, lo -> looked = la to lo }
    }

    @After
    fun tearDown() {
        card.hide()
        Prefs.setDiscreetMode(activity, false)
        Prefs.setAlertsSnoozedUntil(activity, 0L)
    }

    @Test
    fun theCardShowsWhatWasDetectedAndHowSure() {
        assertEquals("hidden until an alert comes", View.GONE, b.alertCard.visibility)
        card.show(alert())
        assertEquals(View.VISIBLE, b.alertCard.visibility)
        assertEquals("NEARBY", b.alertContext.text.toString())
        assertEquals("Axon body camera", b.alertTitle.text.toString())
        assertEquals(Category.BODY_CAM.shortTag, b.alertTag.text.toString())
        assertTrue(b.alertDetail.text.toString(), b.alertDetail.text.toString().contains("BWCDEVICE"))
        assertTrue(b.alertFooter.text.toString(), b.alertFooter.text.toString().startsWith("Strong match"))
    }

    @Test
    fun somethingFollowingYouSaysSo() {
        card.show(alert(label = "Item tracker", category = Category.TRACKER, following = true))
        assertEquals("FOLLOWING YOU", b.alertContext.text.toString())
        assertEquals("Following you: Item tracker", b.alertTitle.text.toString())
    }

    @Test
    fun aPlaceOnTheMapShowsHowFarItIs() {
        // About 1.1 km north of the fix the card is given.
        card.show(alert(label = "Speed camera ahead", category = Category.ALPR, lat = 10.01, lon = 20.0, evidence = "Mapped · OSM · limit 50 km/h"))
        val detail = b.alertDetail.text.toString()
        assertTrue(detail, Regex("""\d+(\.\d+)? (km|mi|m|ft)""").containsMatchIn(detail) && detail.contains("limit 50"))
    }

    @Test
    fun aNewerAlertReplacesTheCardAndTheCloseButtonDismissesIt() {
        card.show(alert(label = "First"))
        card.show(alert(label = "Second"))
        assertEquals("Second", b.alertTitle.text.toString())
        b.alertClose.performClick()
        assertEquals(View.GONE, b.alertCard.visibility)
    }

    @Test
    fun itGoesByItselfAfterAFewSecondsAndLongerWhenStrong() {
        card.show(alert(confidence = 60))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
        assertEquals("a probable match goes after about 8 s", View.GONE, b.alertCard.visibility)
        card.show(alert(confidence = 95))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9))
        assertEquals("a strong one stays longer", View.VISIBLE, b.alertCard.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertEquals(View.GONE, b.alertCard.visibility)
    }

    @Test
    fun tappingTheCardLooksAtThePlaceWhenThereIsOne() {
        card.show(alert(lat = 10.5, lon = 20.5))
        b.alertCard.performClick()
        assertEquals(10.5 to 20.5, looked)
        looked = null
        card.show(alert(lat = null, lon = null))
        b.alertCard.performClick()
        assertNull("nothing to look at for a device with no position", looked)
    }

    @Test
    fun discreetModeShowsOnlyThatSomethingHappened() {
        Prefs.setDiscreetMode(activity, true)
        card.show(alert())
        assertEquals("New alert", b.alertTitle.text.toString())
        assertEquals("", b.alertTag.text.toString())
        assertEquals(View.GONE, b.alertDetail.visibility)
        assertEquals(View.GONE, b.alertFooter.visibility)
    }

    @Test
    fun aSnoozedAlertStaysOffTheMap() {
        Prefs.setAlertsSnoozedUntil(activity, System.currentTimeMillis() + 60_000L)
        card.show(alert())
        assertFalse(b.alertCard.visibility == View.VISIBLE)
    }

    @Test
    fun theFeedHandsEachAlertToWhoeverListensAndRemembersTheNewest() {
        val seen = ArrayList<String>()
        val stop = AlertCards.addListener { seen += it.label }
        AlertCards.publish(alert(label = "One"))
        AlertCards.publish(alert(label = "Two"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("One", "Two"), seen)
        assertEquals("Two", AlertCards.latest?.label)
        stop()
        AlertCards.publish(alert(label = "Three"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a stopped listener hears nothing more", listOf("One", "Two"), seen)
    }
}
