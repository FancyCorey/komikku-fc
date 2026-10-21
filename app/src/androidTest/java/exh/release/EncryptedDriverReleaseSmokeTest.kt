package exh.release

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.DefaultAndroidxSqliteConnectionFactory
import com.eygraber.sqldelight.androidx.driver.FileProvider
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import java.util.UUID

/** Scratch-only native proof: never changes application preferences, keys, or the user's database. */
@RunWith(AndroidJUnit4::class)
class EncryptedDriverReleaseSmokeTest {
    @Test
    fun encryptedDriverReopensAndMigratesCompatible75SchemaWithoutLosingValidRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "release-sqlcipher-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name)
        // This random, test-local key is unrelated to the app's encryption credentials.
        val key = UUID.randomUUID().toString().toByteArray()
        System.loadLibrary("sqlcipher")
        assertEquals(76L, Database.Schema.version)
        // Migration 75 is cleanup-only, with no DDL: the current schema is structurally compatible.
        // This tests its actual migration on SQLCipher, not an old-APK or user-backup restoration.
        val compatible75 = object : SqlSchema<QueryResult.Value<Unit>> by Database.Schema {
            override val version = 75L
        }
        fun open(schema: SqlSchema<QueryResult.Value<Unit>>, foreignKeys: Boolean): SqlDriver = AndroidxSqliteDriver(
            connectionFactory = DefaultAndroidxSqliteConnectionFactory(SQLCipherDriver(key, null, null)),
            databaseType = AndroidxSqliteDatabaseType.FileProvider(context, name),
            schema = schema,
            configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = foreignKeys, cacheSize = 0),
        )
        fun SqlDriver.scalar(sql: String): Long = executeQuery(null, sql, { cursor ->
            check(cursor.next().value)
            QueryResult.Value(checkNotNull(cursor.getLong(0)))
        }, 0).value

        try {
            open(compatible75, false).use { driver ->
                driver.execute(
                    null,
                    "INSERT INTO categories (_id, name, sort, flags, manga_order) VALUES (900001, 'release sentinel', 0, 0, '')",
                    0,
                )
                driver.execute(
                    null,
                    "INSERT INTO history (_id, chapter_id, last_read, time_read) VALUES (900001, 900001, 1, 1)",
                    0,
                )
                assertEquals(1L, driver.scalar("SELECT count(*) FROM history WHERE _id = 900001"))
                assertEquals(75L, driver.scalar("PRAGMA user_version"))
            }
            assertFalse(
                "Database must be encrypted on disk",
                file.inputStream().use {
                    val header = ByteArray(16)
                    assertEquals(16, it.read(header))
                    String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
                },
            )
            open(Database.Schema, true).use { driver ->
                assertEquals(76L, driver.scalar("PRAGMA user_version"))
                assertEquals(0L, driver.scalar("SELECT count(*) FROM history WHERE _id = 900001"))
                assertEquals(1L, driver.scalar("SELECT count(*) FROM categories WHERE _id = 900001"))
                assertEquals(0L, driver.scalar("SELECT count(*) FROM pragma_foreign_key_check"))
                driver.execute(null, "UPDATE categories SET name = 'retained' WHERE _id = 900001", 0)
            }
            open(Database.Schema, true).use { driver ->
                assertEquals(
                    1L,
                    driver.scalar("SELECT count(*) FROM categories WHERE _id = 900001 AND name = 'retained'"),
                )
            }
        } finally {
            key.fill(0)
            // Exact UUID-owned scratch database only; no application database or profile is removed.
            context.deleteDatabase(name)
        }
    }
}
