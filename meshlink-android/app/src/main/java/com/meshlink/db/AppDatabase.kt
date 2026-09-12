package com.meshlink.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class, CustodyEntity::class, BlockedNodeEntity::class, EmergencyUsageEntity::class, GroupEntity::class, GroupMemberEntity::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun custodyDao(): CustodyDao
    abstract fun blockedNodeDao(): BlockedNodeDao
    abstract fun emergencyUsageDao(): EmergencyUsageDao
    abstract fun groupDao(): GroupDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the store of messages carried on other nodes' behalf. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custody` (
                        `messageId` TEXT NOT NULL,
                        `recipientId` INTEGER NOT NULL,
                        `envelopeData` BLOB NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `isSos` INTEGER NOT NULL,
                        PRIMARY KEY(`messageId`)
                    )
                    """
                )
            }
        }

        /** Adds read-state, so the unread badge can be cleared independently of delivery. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN isRead INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the per-device block list. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `blocked_nodes` (
                        `beaconRow` INTEGER NOT NULL,
                        `name` TEXT,
                        `blockedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`beaconRow`)
                    )
                    """
                )
            }
        }

        /** Adds attachment support: message kind and media bookkeeping. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE messages ADD COLUMN messageType TEXT NOT NULL DEFAULT 'TEXT'")
                database.execSQL("ALTER TABLE messages ADD COLUMN mediaPath TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN mediaMime TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN mediaSize INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE messages ADD COLUMN mediaState TEXT")
            }
        }

        /** Adds the emergency allowance ledgers. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `emergency_usage` (
                        `id` INTEGER NOT NULL,
                        `nodeRow` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `bytes` INTEGER NOT NULL,
                        `usedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """
                )
            }
        }

        /** Adds group chats, their membership, and message-level deletion. */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `groups` (
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `groupKey` BLOB NOT NULL,
                        `keyVersion` INTEGER NOT NULL,
                        `rosterVersion` INTEGER NOT NULL,
                        `createdBy` INTEGER NOT NULL,
                        `joinedAt` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`)
                    )
                    """
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `group_members` (
                        `groupId` TEXT NOT NULL,
                        `beaconRow` INTEGER NOT NULL,
                        `name` TEXT,
                        `isAdmin` INTEGER NOT NULL,
                        PRIMARY KEY(`groupId`, `beaconRow`)
                    )
                    """
                )
                database.execSQL("ALTER TABLE messages ADD COLUMN groupId TEXT")
                database.execSQL("ALTER TABLE messages ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Purges control payloads that were stored as chat messages.
         *
         * Before payload types were whitelisted, a group invite decoded under an
         * older enum ordering fell through to the text path and was saved - and
         * rendered - verbatim, group key included. Deleting those rows removes
         * the key material from disk rather than merely hiding it.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "DELETE FROM messages WHERE plaintext LIKE '{\"gid\":%' " +
                        "OR plaintext LIKE '%\"key\":\"%' OR plaintext LIKE '{\"k\":\"%'"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "meshlink_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
