package com.vault.srd.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Vault::class, VaultItem::class, VaultFolder::class, VaultTag::class, VaultItemTagCrossRef::class],
    version = 12,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao

    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null
        private const val DATABASE_NAME = "vault_life_database"
        private const val TARGET_VERSION = 12

        private val MIGRATION_1_12 = buildSchemaMigration(1)
        private val MIGRATION_2_12 = buildSchemaMigration(2)
        private val MIGRATION_3_12 = buildSchemaMigration(3)
        private val MIGRATION_4_12 = buildSchemaMigration(4)
        private val MIGRATION_5_12 = buildSchemaMigration(5)
        private val MIGRATION_6_12 = buildSchemaMigration(6)
        private val MIGRATION_7_12 = buildSchemaMigration(7)
        private val MIGRATION_8_12 = buildSchemaMigration(8)
        private val MIGRATION_9_12 = buildSchemaMigration(9)
        private val MIGRATION_10_12 = buildSchemaMigration(10)
        private val MIGRATION_11_12 = buildSchemaMigration(11)
        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_12,
            MIGRATION_2_12,
            MIGRATION_3_12,
            MIGRATION_4_12,
            MIGRATION_5_12,
            MIGRATION_6_12,
            MIGRATION_7_12,
            MIGRATION_8_12,
            MIGRATION_9_12,
            MIGRATION_10_12,
            MIGRATION_11_12
        )

        private fun buildSchemaMigration(fromVersion: Int): Migration {
            return object : Migration(fromVersion, TARGET_VERSION) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    ensureLatestSchema(db)
                }
            }
        }

        private fun ensureLatestSchema(db: SupportSQLiteDatabase) {
            rebuildVaultsTableWithoutTotp(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vaults` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pinHash` TEXT NOT NULL,
                    `pinSalt` TEXT NOT NULL,
                    `colorHex` TEXT,
                    `logoPath` TEXT,
                    `description` TEXT,
                    `biometricUnlockEnabled` INTEGER NOT NULL DEFAULT 0,
                    `isDecoy` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vault_items` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `vaultId` INTEGER NOT NULL,
                    `type` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `content` TEXT,
                    `username` TEXT,
                    `passCategory` TEXT,
                    `link` TEXT,
                    `logoPath` TEXT,
                    `filePath` TEXT,
                    `extension` TEXT,
                    `email` TEXT,
                    `phoneNumber` TEXT,
                    `folderId` INTEGER,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    `updatedAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `folders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `vaultId` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `description` TEXT,
                    `createdAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vault_tags` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `colorHex` TEXT NOT NULL,
                    `vaultId` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vault_item_tags` (
                    `itemId` INTEGER NOT NULL,
                    `tagId` TEXT NOT NULL,
                    PRIMARY KEY(`itemId`, `tagId`)
                )
                """.trimIndent()
            )

            ensureColumn(db, "vaults", "isDecoy", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "vaults", "createdAt", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "vaults", "biometricUnlockEnabled", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "vault_items", "email", "TEXT")
            ensureColumn(db, "vault_items", "phoneNumber", "TEXT")
            ensureColumn(db, "vault_items", "folderId", "INTEGER")
            ensureColumn(db, "vault_items", "createdAt", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "vault_items", "updatedAt", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn(db, "folders", "createdAt", "INTEGER NOT NULL DEFAULT 0")

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vaults_pinHash` ON `vaults` (`pinHash`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vaults_name` ON `vaults` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vaults_name_nocase` ON `vaults` (`name` COLLATE NOCASE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_vaultId` ON `vault_items` (`vaultId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_folderId` ON `vault_items` (`folderId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_vaultId_name` ON `vault_items` (`vaultId`, `name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_vaultId_type` ON `vault_items` (`vaultId`, `type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_vaultId_createdAt` ON `vault_items` (`vaultId`, `createdAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_vaultId` ON `folders` (`vaultId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_tags_vaultId` ON `vault_tags` (`vaultId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vault_tags_vaultId_name` ON `vault_tags` (`vaultId`, `name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_tags_vaultId_name_nocase` ON `vault_tags` (`vaultId`, `name` COLLATE NOCASE)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_item_tags_tagId` ON `vault_item_tags` (`tagId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_item_tags_itemId` ON `vault_item_tags` (`itemId`)")
        }

        private fun ensureColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            if (!hasColumn(db, table, column)) {
                db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
            }
        }

        private fun hasColumn(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && column == cursor.getString(nameIndex)) {
                        return true
                    }
                }
            }
            return false
        }

        private fun getColumns(db: SupportSQLiteDatabase, table: String): Set<String> {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0) {
                        columns.add(cursor.getString(nameIndex))
                    }
                }
            }
            return columns
        }

        private fun rebuildVaultsTableWithoutTotp(db: SupportSQLiteDatabase) {
            val columns = getColumns(db, "vaults")
            if (columns.isEmpty()) return
            val hasTotp = columns.contains("totpEnabled") || columns.contains("totpSecretEnc")
            if (!hasTotp) return

            db.execSQL("ALTER TABLE `vaults` RENAME TO `vaults_old`")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `vaults` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pinHash` TEXT NOT NULL,
                    `pinSalt` TEXT NOT NULL,
                    `colorHex` TEXT,
                    `logoPath` TEXT,
                    `description` TEXT,
                    `biometricUnlockEnabled` INTEGER NOT NULL DEFAULT 0,
                    `isDecoy` INTEGER NOT NULL DEFAULT 0,
                    `createdAt` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            val idExpr = if (columns.contains("id")) "id" else "NULL"
            val nameExpr = if (columns.contains("name")) "name" else "''"
            val pinHashExpr = if (columns.contains("pinHash")) "pinHash" else "''"
            val pinSaltExpr = if (columns.contains("pinSalt")) "pinSalt" else "''"
            val colorExpr = if (columns.contains("colorHex")) "colorHex" else "NULL"
            val logoExpr = if (columns.contains("logoPath")) "logoPath" else "NULL"
            val descExpr = if (columns.contains("description")) "description" else "NULL"
            val bioExpr = if (columns.contains("biometricUnlockEnabled")) "biometricUnlockEnabled" else "0"
            val decoyExpr = if (columns.contains("isDecoy")) "isDecoy" else "0"
            val createdExpr = if (columns.contains("createdAt")) "createdAt" else "0"
            db.execSQL(
                """
                INSERT INTO `vaults` (
                    `id`, `name`, `pinHash`, `pinSalt`, `colorHex`, `logoPath`, `description`,
                    `biometricUnlockEnabled`, `isDecoy`, `createdAt`
                )
                SELECT
                    $idExpr, $nameExpr, $pinHashExpr, $pinSaltExpr, $colorExpr, $logoExpr, $descExpr,
                    $bioExpr, $decoyExpr, $createdExpr
                FROM `vaults_old`
                """.trimIndent()
            )
            db.execSQL("DROP TABLE `vaults_old`")
        }

        fun getDatabase(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    DATABASE_NAME
                )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
