package com.austinlinux.pulsemetronome.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

const val DB_NAME = "pulse_metronome.db"

/** Production 1: the first released schema; no upgrade code exists below it. */
const val DB_BASELINE_VERSION = 1

/**
 * Every upgrade step from DB_BASELINE_VERSION to AppDatabase.VERSION. Bumping the version
 * requires: a Migration here, the new exported schema committed, and MigrationTest covering
 * it (MigrationGuardTest fails otherwise). There is deliberately no destructive fallback:
 * DatabaseOpener moves an un-upgradable database into a backup instead of wiping it.
 */
val ALL_MIGRATIONS: Array<Migration> = arrayOf()

/** Session history. Version bumps need a Migration in ALL_MIGRATIONS, the exported schema, and MigrationTest coverage (see ALL_MIGRATIONS). */
@Database(entities = [SessionEntity::class, PulseSampleEntity::class], version = AppDatabase.VERSION, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun pulseSampleDao(): PulseSampleDao

    companion object {
        const val VERSION = 1
        @Volatile private var INSTANCE: AppDatabase? = null

        /** App-scoped singleton, shared by the app and SessionForegroundService. */
        fun instance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DatabaseOpener(context).open().also { INSTANCE = it }
            }
    }
}

/** Room builder with the migration list and no fallback of any kind. */
fun buildRoom(context: Context, name: String): AppDatabase =
    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
        .addMigrations(*ALL_MIGRATIONS)
        .build()
