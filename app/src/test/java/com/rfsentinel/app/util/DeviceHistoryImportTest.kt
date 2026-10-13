package com.rfsentinel.app.util

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.KnownDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Device history import: the app's own JSON and CSV exports are read back, and merging loses nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeviceHistoryImportTest {

    private lateinit var db: AppDatabase

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }

    private val json = """[
      {"mac":"aa:bb:cc:00:00:01","name":"Tag one","vendor":"Acme","deviceType":"Tracker","firstSeen":"2026-01-02T03:04:05Z","lastSeen":"2026-02-03T04:05:06Z","sessions":4,"detectCount":9,"favorite":true,"note":"mine"},
      {"mac":"AA:BB:CC:00:00:02","name":null,"vendor":"Other","deviceType":null,"firstSeen":"2026-01-01T00:00:00Z","lastSeen":"2026-01-01T00:00:00Z","sessions":1,"detectCount":1,"favorite":false,"note":null},
      {"mac":"not a mac","name":"junk"},
      "stray"
    ]"""

    private fun run(text: String) = runBlocking { DeviceHistoryImport.importInto(db.knownDeviceDao(), text.byteInputStream()) }

    @Test
    fun theJsonExportIsReadBackWithItsDetails() {
        val r = run(json)
        assertEquals(2, r.added); assertEquals(0, r.updated); assertEquals(2, r.skipped)
        val d = runBlocking { db.knownDeviceDao().get("AA:BB:CC:00:00:01") }!!
        assertEquals("Tag one", d.name); assertEquals("Acme", d.vendor); assertEquals("Tracker", d.deviceType)
        assertEquals(4, d.sessions); assertEquals(9, d.detectCount); assertTrue(d.favorite); assertEquals("mine", d.note)
        assertTrue("the time was read (UTC)", d.firstSeen > 1_700_000_000_000L && d.lastSeen > d.firstSeen)
        assertNull(runBlocking { db.knownDeviceDao().get("AA:BB:CC:00:00:02") }!!.name)
    }

    @Test
    fun importingTheSameFileAgainChangesNothing() {
        run(json)
        val again = run(json)
        assertEquals(0, again.added); assertEquals(0, again.updated); assertEquals(2, again.unchanged)
        assertEquals(2, runBlocking { db.knownDeviceDao().count() })
    }

    @Test
    fun theEverythingExportAndTheCsvAreReadToo() {
        val everything = """{"exportedBy":"RF Sentinel 2.20.0","sessionDevices":[{"mac":"11:11:11:11:11:11"}],"deviceHistory":[
            {"mac":"DE:AD:BE:EF:00:01","name":"Cam","vendor":"V","deviceType":"Camera","firstSeen":"2026-03-01T10:00:00Z","lastSeen":"2026-03-02T10:00:00Z","sessions":2,"detectCount":3,"favorite":false,"note":"a, \"quoted\" note"}],"matchLog":[]}"""
        assertEquals(1, run(everything).added)
        assertNull("the live session list is not history", runBlocking { db.knownDeviceDao().get("11:11:11:11:11:11") })
        val csv = "mac,name,vendor,device_type,first_seen,last_seen,sessions,detect_count,favorite,note\n" +
            "AA:00:00:00:00:01,\"Name, with comma\",Vend,Phone,2026-04-01 08:00:00,2026-04-02 09:30:00,5,12,true,\"line one\nline two\"\r\n" +
            "AA:00:00:00:00:02,,,,,,,,,\n"
        val r = run(csv)
        assertEquals(2, r.added)
        val d = runBlocking { db.knownDeviceDao().get("AA:00:00:00:00:01") }!!
        assertEquals("Name, with comma", d.name); assertEquals("line one\nline two", d.note); assertTrue(d.favorite); assertEquals(12, d.detectCount)
        assertNotNull(runBlocking { db.knownDeviceDao().get("AA:00:00:00:00:02") })
        assertEquals("a, \"quoted\" note", runBlocking { db.knownDeviceDao().get("DE:AD:BE:EF:00:01") }!!.note)
    }

    @Test
    fun mergingKeepsWhatIsThereAndFillsInTheRest() {
        fun e(first: Long, last: Long, sessions: Int, detects: Int, name: String?, fav: Boolean, note: String?, vendor: String? = null) =
            KnownDeviceEntity("AA:BB:CC:DD:EE:FF", first, last, sessions, detects, name, vendor, null, fav, note)
        val mine = e(1000, 5000, 3, 8, "Mine", false, null)
        val theirs = e(500, 4000, 9, 5, "Theirs", true, "their note", "Vendor")
        val m = DeviceHistoryImport.merge(mine, theirs)
        assertEquals(500L, m.firstSeen); assertEquals(5000L, m.lastSeen)
        assertEquals(9, m.sessions); assertEquals(8, m.detectCount)
        assertEquals("my name stays", "Mine", m.name); assertEquals("a missing vendor is filled in", "Vendor", m.vendor)
        assertTrue("a star is never lost", m.favorite); assertEquals("their note fills the gap", "their note", m.note)
        assertEquals("merging again changes nothing", m, DeviceHistoryImport.merge(m, theirs))
        assertEquals(theirs, DeviceHistoryImport.merge(null, theirs))
        assertEquals("my note stays", "mine!", DeviceHistoryImport.merge(e(1, 2, 1, 1, null, false, "mine!"), theirs).note)
    }

    @Test
    fun deviceListedTwiceInOneFileMergesAndRowsAreCheckedAndCleaned() {
        val twice = """[{"mac":"AA:BB:CC:00:00:09","sessions":2,"detectCount":1,"firstSeen":"2026-01-01T00:00:00Z","lastSeen":"2026-01-02T00:00:00Z"},
                        {"mac":"aa:bb:cc:00:00:09","sessions":7,"detectCount":1,"firstSeen":"2026-01-05T00:00:00Z","lastSeen":"2026-01-06T00:00:00Z","name":"  Spaced  "}]"""
        assertEquals(1, run(twice).added)
        val d = runBlocking { db.knownDeviceDao().get("AA:BB:CC:00:00:09") }!!
        assertEquals(7, d.sessions); assertEquals("Spaced", d.name)
        assertNull(DeviceHistoryImport.row("12:34", null, null, null, null, null, null, null, null, null))
        assertNull(DeviceHistoryImport.row("AA:BB:CC:DD:EE:GG", null, null, null, null, null, null, null, null, null))
        val bare = DeviceHistoryImport.row("aa:bb:cc:dd:ee:ff", "null", "", null, null, null, "-3", "0", "no", null, now = 5_000L)!!
        assertEquals("AA:BB:CC:DD:EE:FF", bare.mac); assertNull(bare.name); assertNull(bare.vendor)
        assertEquals(1, bare.sessions); assertEquals(1, bare.detectCount); assertFalse(bare.favorite)
        assertEquals("a missing time is now", 5_000L, bare.firstSeen)
    }

    @Test
    fun otherFilesAreRefusedWithAReason() {
        fun refused(text: String): String = runCatching { run(text) }.exceptionOrNull().let { (it as DeviceHistoryImport.NotHistory).message!! }
        assertTrue(refused("""{"hello":"world"}""").contains("no device history"))
        assertTrue(refused("name,vendor\nA,B\n").contains("no \"mac\" column"))
        assertTrue(refused("").contains("empty"))
        assertTrue(refused("\"just a string\"").contains("isn't a device history"))
        assertEquals(0, runBlocking { db.knownDeviceDao().count() })
    }

    @Test
    fun aHugeFileIsStoppedNotReadToTheEnd() {
        val big = java.io.ByteArrayInputStream(ByteArray(200))
        val e = runCatching { DeviceHistoryImport.Limited(big, limit = 100).readBytes() }.exceptionOrNull()
        assertTrue(e is DeviceHistoryImport.NotHistory)
        assertTrue(DeviceHistoryImport.fileName(0L).startsWith("rf_sentinel_device_history_"))
    }
}
