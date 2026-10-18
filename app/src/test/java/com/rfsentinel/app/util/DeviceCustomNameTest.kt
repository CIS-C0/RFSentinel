package com.rfsentinel.app.util

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rfsentinel.app.data.AppDatabase
import com.rfsentinel.app.data.KnownDeviceDao
import com.rfsentinel.app.data.KnownDeviceEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Your own device names and notes: saved, searchable, kept through pruning, exported and imported back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeviceCustomNameTest {

    private lateinit var db: AppDatabase
    private val dao get() = db.knownDeviceDao()

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }

    private fun row(mac: String, last: Long) = KnownDeviceEntity(mac, last, last, 1, 1, "Heard", null, null)

    @Test
    fun namesAndNotesAreSavedAndSearchable() = runBlocking {
        dao.upsertAll(listOf(row("AA:00:00:00:00:01", 1000), row("AA:00:00:00:00:02", 1000)))
        dao.setAlias("AA:00:00:00:00:01", "Neighbour's car")
        dao.setNote("AA:00:00:00:00:02", "Seen at the market")
        assertEquals(listOf("AA:00:00:00:00:01" to "Neighbour's car"), dao.aliases().map { it.mac to it.alias })
        fun find(q: String) = runBlocking { dao.search(q, KnownDeviceDao.like(q), KnownDeviceDao.likeHex(q), false, false, 0, 10).map { it.mac } }
        assertEquals(listOf("AA:00:00:00:00:01"), find("neighbour"))
        assertEquals(listOf("AA:00:00:00:00:02"), find("market"))
        dao.setAlias("AA:00:00:00:00:01", null)
        assertEquals(0, dao.aliases().size)
    }

    @Test
    fun pruningKeepsDevicesYouNamedOrWroteOn() = runBlocking {
        dao.upsertAll(listOf(row("AA:00:00:00:00:01", 1000), row("AA:00:00:00:00:02", 1000), row("AA:00:00:00:00:03", 1000)))
        dao.setAlias("AA:00:00:00:00:01", "Mine")
        dao.setNote("AA:00:00:00:00:02", "A note")
        assertEquals(1, dao.prune(5000))
        assertNotNull(dao.get("AA:00:00:00:00:01")); assertNotNull(dao.get("AA:00:00:00:00:02"))
        assertNull(dao.get("AA:00:00:00:00:03"))
    }

    @Test
    fun theCustomNameGoesThroughExportAndImport() {
        val named = row("AA:00:00:00:00:01", 1000).copy(alias = "Doorbell", note = "front door")
        for (text in listOf(DeviceExport.historyCsv(listOf(named)), DeviceExport.historyJson(listOf(named)).toString())) {
            val back = DeviceHistoryImport.rows(text.byteInputStream()).filterNotNull().single()
            assertEquals("Doorbell", back.alias); assertEquals("front door", back.note)
        }
        val merged = DeviceHistoryImport.merge(row("AA:00:00:00:00:01", 2000), named)
        assertEquals("a missing name is filled in", "Doorbell", merged.alias)
        assertEquals("your own name stays", "Mine", DeviceHistoryImport.merge(named.copy(alias = "Mine"), named.copy(alias = "Other")).alias)
    }
}
