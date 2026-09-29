package com.rfsentinel.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DetectionEntity::class, WhitelistEntity::class, KnownDeviceEntity::class,
        TripEntity::class, TripPointEntity::class, TripDeviceEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun detectionDao(): DetectionDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun knownDeviceDao(): KnownDeviceDao
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /** v1.x -> v2.0: richer detections + persistent device history. Keeps existing logs. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE detections ADD COLUMN category TEXT NOT NULL DEFAULT 'CUSTOM'")
                db.execSQL("ALTER TABLE detections ADD COLUMN confidence INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE detections ADD COLUMN evidence TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE detections ADD COLUMN vendor TEXT")
                db.execSQL("ALTER TABLE detections ADD COLUMN deviceName TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_timestamp ON detections(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_detections_mac ON detections(mac)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS known_devices (" +
                        "mac TEXT NOT NULL PRIMARY KEY, firstSeen INTEGER NOT NULL, lastSeen INTEGER NOT NULL, " +
                        "sessions INTEGER NOT NULL, detectCount INTEGER NOT NULL, name TEXT, vendor TEXT, " +
                        "deviceType TEXT, favorite INTEGER NOT NULL DEFAULT 0, note TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_known_devices_lastSeen ON known_devices(lastSeen)")
            }
        }

        /** v2.2 -> v2.3: recorded scan traces (trips). Keeps everything else. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trips (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, startTime INTEGER NOT NULL, endTime INTEGER, distanceM REAL NOT NULL, " +
                        "pointCount INTEGER NOT NULL, deviceCount INTEGER NOT NULL, flaggedCount INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "tripId INTEGER NOT NULL, time INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, " +
                        "accuracyM REAL, speedMs REAL, " +
                        "FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_points_tripId ON trip_points(tripId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_devices (tripId INTEGER NOT NULL, mac TEXT NOT NULL, " +
                        "label TEXT NOT NULL, name TEXT, vendor TEXT, deviceType TEXT, source TEXT NOT NULL, " +
                        "category TEXT, confidence INTEGER NOT NULL, evidence TEXT, firstSeen INTEGER NOT NULL, " +
                        "lastSeen INTEGER NOT NULL, bestRssi INTEGER NOT NULL, lat REAL, lon REAL, " +
                        "PRIMARY KEY(tripId, mac), " +
                        "FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_devices_tripId ON trip_devices(tripId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "rfsentinel.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
