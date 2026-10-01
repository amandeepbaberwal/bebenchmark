package com.bluebenchmark.cpu.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [BenchmarkRecord::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): BenchmarkDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bluebench.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { inst = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmarks ADD COLUMN partial INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE benchmarks ADD COLUMN rawMetricsJson TEXT NOT NULL DEFAULT '{}' ")
                db.execSQL("ALTER TABLE benchmarks ADD COLUMN suitePreset TEXT NOT NULL DEFAULT 'NORMAL'")
                db.execSQL("ALTER TABLE benchmarks ADD COLUMN deviceName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE benchmarks ADD COLUMN scoreVersion TEXT NOT NULL DEFAULT 'cpu-suite-v1'")
            }
        }
    }
}
