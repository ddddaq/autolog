package com.autolog.app.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun purposeToString(purpose: Purpose): String = purpose.name

    @TypeConverter
    fun stringToPurpose(name: String): Purpose = runCatching { Purpose.valueOf(name) }.getOrDefault(Purpose.UNCLASSIFIED)
}

@Database(entities = [Trip::class], version = 7, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        /** 2: 운행에 운전자(성명, 부서)를 남긴다. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN driverName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE trips ADD COLUMN driverDept TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 3: 운행에 출발지와 도착지 이름을 남긴다. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN startPlace TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE trips ADD COLUMN endPlace TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 4: 지운 운행을 표시한다. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 5: 출발·도착 좌표와 도착지가 내비 목적지인지를 남긴다. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN startLat REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN startLng REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN endLat REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN endLng REAL")
                db.execSQL("ALTER TABLE trips ADD COLUMN endFromNavi INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 6: 합치거나 나눠서 생긴 운행에 원래 운행을 적어 둔다. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN replaces TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 7: 지운 표시가 붙은 까닭을 남긴다. 사용자가 지운 운행만 되살리기 목록에 내놓기 위해서다. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trips ADD COLUMN deletedReason TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}
