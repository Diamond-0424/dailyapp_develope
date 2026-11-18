package com.dailyapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Database(
    entities = [ChatGroup::class, RawMessage::class, Todo::class, Role::class, GroupRole::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun groups(): GroupDao
    abstract fun messages(): MessageDao
    abstract fun todos(): TodoDao
    abstract fun roles(): RoleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(ctx.applicationContext).also { instance = it }
        }

        private fun build(ctx: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbKey.passphrase(ctx))
            return Room.databaseBuilder(ctx, AppDatabase::class.java, "dailyapp.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        /** v2：todos.groupId 改為可為 null（手動新增、分享進來的待辦沒有來源群組）。保留既有資料。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cols = "`id`, `kind`, `title`, `groupId`, `requester`, `dueAt`, `priority`, `status`, " +
                    "`excerpt`, `sourceMessageIds`, `hasAttachment`, `createdAt`, `updatedAt`, `snoozedUntil`"
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `todos_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `groupId` TEXT, `requester` TEXT, " +
                        "`dueAt` INTEGER, `priority` TEXT NOT NULL, `status` TEXT NOT NULL, `excerpt` TEXT NOT NULL, " +
                        "`sourceMessageIds` TEXT NOT NULL, `hasAttachment` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `snoozedUntil` INTEGER)"
                )
                db.execSQL("INSERT INTO `todos_new` ($cols) SELECT $cols FROM `todos`")
                db.execSQL("DROP TABLE `todos`")
                db.execSQL("ALTER TABLE `todos_new` RENAME TO `todos`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_groupId` ON `todos` (`groupId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todos_status` ON `todos` (`status`)")
            }
        }

        /** v3：身分設定檔（roles、group_roles）與群組附加關鍵字。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `chat_groups` ADD COLUMN `extraKeywords` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `roles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `keywords` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_roles` (`groupId` TEXT NOT NULL, `roleId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `roleId`), " +
                        "FOREIGN KEY(`groupId`) REFERENCES `chat_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`roleId`) REFERENCES `roles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_group_roles_roleId` ON `group_roles` (`roleId`)")
            }
        }
    }
}

/**
 * 資料庫密碼：隨機產生 32 位元組，以 Android Keystore 中的 AES-GCM 金鑰加密後存放。
 * Keystore 金鑰無法匯出，所以即使有人複製了 App 的資料檔，也無法解開資料庫。
 */
object DbKey {
    private const val ALIAS = "dailyapp_db_key"
    private const val PREFS = "dbkey"
    private const val PREF_BLOB = "wrapped"

    fun passphrase(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_BLOB, null)
        if (stored != null) return unwrap(Base64.decode(stored, Base64.NO_WRAP))
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_BLOB, Base64.encodeToString(wrap(raw), Base64.NO_WRAP)).commit()
        return raw
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun wrap(raw: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(raw) // 前 12 位元組為 IV
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}
