package com.akshayc.appgate.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * On-device only. Nothing here is exported, backed up, or transmitted — the
 * gate list is the user's full list of apps they struggle with (Privacy,
 * CLAUDE.md).
 */
@Database(entities = [GateEntity::class, SessionEntity::class], version = 4, exportSchema = false)
internal abstract class AppGateDatabase : RoomDatabase() {
    abstract fun gateDao(): GateDao

    abstract fun sessionDao(): SessionDao
}

/**
 * Adds the sessions table. Written by hand rather than destroying and
 * recreating the database, because v1 already holds the user's configured
 * Gates on any device that has run an earlier build.
 */
internal val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`packageName` TEXT NOT NULL, " +
                    "`userId` INTEGER NOT NULL, " +
                    "`startedAtMillis` INTEGER NOT NULL, " +
                    "`endsAtMillis` INTEGER NOT NULL, " +
                    "`endedAtMillis` INTEGER, " +
                    "`intentText` TEXT)",
            )
        }
    }

/**
 * Two changes to `gates`: the grace window is dropped on existing rows, because
 * a Gate is now raised on every open and re-entry is never waved through; and
 * the daily allowance needs somewhere to keep the hour it refills at.
 */
internal val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE gates SET graceSeconds = 0")
            db.execSQL("ALTER TABLE gates ADD COLUMN budgetResetHour INTEGER")
        }
    }

/**
 * Escalating re-entry becomes a per-Gate setting. Existing rows are left null,
 * which reads as off: escalation used to apply to every Gate unasked, and a
 * setting the user has never seen should not arrive already switched on.
 */
internal val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE gates ADD COLUMN escalation INTEGER")
        }
    }
