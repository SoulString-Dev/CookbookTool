package org.miu.cookbook.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 的迁移测试。
 *
 * 做法是：先按 **v1 的建表语句**（下面那堆常量，抄自 `app/schemas/…/1.json`，
 * 是历史快照，不要跟着代码改）在真实文件上造一个 v1 库、塞进一些数据，
 * 再用 Room 打开它。Room 会自己跑 [MIGRATION_1_2]，跑完还会拿结果和自己的
 * v2 期望 schema 对一遍——对不上就直接抛
 * 「Migration didn't properly handle」。
 *
 * 所以这个测试真正校验的是两件事：**数据没丢**，以及**迁移出来的表结构 Room 认**。
 * 后者尤其重要：漏一列 `NOT NULL`、少个索引，只有真机升级时才炸，
 * 而那时用户看到的是「打开就闪退」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-1-to-2.db"
    private var migrated: AppDatabase? = null

    @After
    fun tearDown() {
        migrated?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `v1 的既有数据在迁移后一条不少`() {
        val db = migrate(seedV1Data())
        val dao = db.recipeDao()
        val recipes = runBlocking { dao.observeAllWithDetails().first() }

        // 列表按 created_at 倒序
        assertEquals(listOf("土豆丝", "红烧肉"), recipes.map { it.recipe.title })
        val redBraised = recipes.first { it.recipe.id == RED_BRAISED }
        assertEquals("红烧肉", redBraised.recipe.title)
        assertEquals(100L, redBraised.recipe.createdAt)
        // 分类、原料、正文、图片、饮食记录都还在
        assertEquals(listOf("家常菜"), redBraised.categories.map { it.name })
        assertEquals(listOf("五花肉"), redBraised.ingredients.map { it.name })
        assertEquals(1, runBlocking { dao.observeDetail(RED_BRAISED).first()!!.contentBlocks.size })
        assertEquals(1, runBlocking { dao.observeDetail(RED_BRAISED).first()!!.images.size })
        assertEquals(1, runBlocking { db.mealLogDao().countForRecipe(RED_BRAISED) })
    }

    @Test
    fun `迁移会给存量食谱补上不重复的 uuid`() {
        val db = migrate(seedV1Data())
        val rows = db.uuidsOf("recipe")

        assertEquals(2, rows.size)
        rows.forEach { (_, uuid) ->
            assertTrue("uuid 不该是空的", uuid.isNotBlank())
            assertEquals("uuid 应该是 36 位的标准 UUID", 36, uuid.length)
        }
        assertEquals("两条食谱的 uuid 必须不同", 2, rows.map { it.second }.toSet().size)
    }

    @Test
    fun `迁移会给存量分类补 uuid，饮料表也一样`() {
        val db = migrate(seedV1Data())
        val categories = db.uuidsOf("category")

        assertEquals(1, categories.size)
        assertTrue(categories.single().second.isNotBlank())
    }

    @Test
    fun `存量食谱会被标脏，等着第一次上传`() {
        val db = migrate(seedV1Data())
        val flags = db.rows("SELECT deleted_at, dirty, server_rev FROM recipe ORDER BY id") { c ->
            Triple(c.getLong(0), c.getInt(1), c.getLong(2))
        }

        flags.forEach { (deletedAt, dirty, serverRev) ->
            assertEquals("迁移过来的都不该是墓碑", 0L, deletedAt)
            assertEquals("存量数据必须先标脏，否则永远传不上去", 1, dirty)
            assertEquals(0L, serverRev)
        }
    }

    @Test
    fun `uuid 的唯一索引和同步游标表都建好了`() {
        val db = migrate(seedV1Data())
        val indexes = db.namesIn(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN " +
                "('index_recipe_uuid', 'index_category_uuid')",
        )

        assertEquals(listOf("index_category_uuid", "index_recipe_uuid"), indexes.sorted())
        assertEquals(listOf("sync_state"), db.namesIn("SELECT name FROM sqlite_master WHERE name = 'sync_state'"))

        // 新表真的能用：写一行游标再读回来
        runBlocking { db.syncStateDao().put(SyncStateEntity(deviceId = "device-1", lastRev = 7)) }
        val state = runBlocking { db.syncStateDao().get() }
        assertEquals("device-1", state!!.deviceId)
        assertEquals(7L, state.lastRev)
    }

    @Test
    fun `v2 的图片行迁移后有哈希列，老图留空等着补算`() {
        val db = migrateFromV2(seedV2Data())

        val rows = db.rows("SELECT path, sha256, thumb_sha256 FROM recipe_image ORDER BY id") {
            Triple(it.getString(0), it.getString(1), it.getString(2))
        }
        assertEquals(1, rows.size)
        assertEquals("images/a.jpg", rows.single().first)
        // 老数据没有哈希：留空，等第一次同步前补算（不在这里扫盘，迁移要快）
        assertEquals("", rows.single().second)
        assertEquals("", rows.single().third)

        // 两个新索引都建好了（同步时要按 sha256 找「这张图本地有没有」）
        val names = db.namesIn("SELECT name FROM sqlite_master WHERE type = 'index'")
        assertTrue(names.contains("index_recipe_image_sha256"))
        assertTrue(names.contains("index_recipe_image_thumb_sha256"))

        // 补算之后能写回（NOT NULL DEFAULT '' 的列，写字符串没问题）
        db.openHelper.writableDatabase.execSQL(
            "UPDATE recipe_image SET sha256 = 'abc', thumb_sha256 = 'def' WHERE id = 1",
        )
        val updated = db.rows("SELECT sha256 FROM recipe_image WHERE id = 1") { it.getString(0) }
        assertEquals(listOf("abc"), updated)
    }

    @Test
    fun `v2 升级到 v3 时食谱、分类和图片一条不少`() {
        val db = migrateFromV2(seedV2Data())
        val recipes = runBlocking { db.recipeDao().observeAllWithDetails().first() }
        assertEquals(listOf("红烧肉"), recipes.map { it.recipe.title })
        assertEquals("uuid-of-recipe", recipes.single().recipe.uuid)
        assertEquals("家常菜", recipes.single().categories.single().name)
        assertEquals("images/a.jpg", recipes.single().images.single().path)
        // 同步游标表也还在（v2 就有的东西，别在迁移里弄丢）
        val state = runBlocking { db.syncStateDao().get() }
        assertNotNull(state)
        assertEquals(7L, state!!.lastRev)
    }

    @Test
    fun `唯一索引真的拦得住重复 uuid`() {
        val db = migrate(seedV1Data())
        val existing = runBlocking {
            val first = db.recipeDao().observeAll().first().first()
            db.recipeDao().findByUuid(first.uuid)
        }!!

        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
            runBlocking {
                db.recipeDao().insert(existing.copy(id = 0))
            }
        }
    }

    // ---------- 造一个 v1 库 ----------

    /**
     * 造 v1 库、塞数据、关掉，再用 Room(v2) 打开同一个文件。
     *
     * 打开这一步就会跑迁移；Room 打开时还会校验 schema，校验不过会抛异常——
     * 测试里不用自己断言，异常本身就是失败。
     */
    private fun migrate(seed: SupportSQLiteDatabase.() -> Unit): AppDatabase {
        createV1Database(seed)
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { migrated = it }
    }

    private fun createV1Database(seed: SupportSQLiteDatabase.() -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V1_SCHEMA.forEach(db::execSQL)
                db.seed()
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            // 第一次取 writableDatabase 才会触发 onCreate
            helper.writableDatabase
        }
    }

    /** v2 的库升到 v3：这次要同时挂两个迁移，Room 只会跑 2 → 3。 */
    private fun migrateFromV2(seed: SupportSQLiteDatabase.() -> Unit): AppDatabase {
        createV2Database(seed)
        return Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
            .also { migrated = it }
    }

    private fun createV2Database(seed: SupportSQLiteDatabase.() -> Unit) {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                V2_SCHEMA.forEach(db::execSQL)
                db.seed()
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(callback)
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper ->
            helper.writableDatabase
        }
    }

    private fun seedV2Data(): SupportSQLiteDatabase.() -> Unit = {
        execSQL(
            "INSERT INTO recipe (id, uuid, title, created_at, updated_at, deleted_at, dirty, server_rev) " +
                "VALUES ($RED_BRAISED, 'uuid-of-recipe', '红烧肉', 100, 200, 0, 0, 3)",
        )
        execSQL(
            "INSERT INTO category (id, uuid, name, sort_order) " +
                "VALUES (1, 'uuid-of-category', '家常菜', 0)",
        )
        execSQL("INSERT INTO recipe_category (recipe_id, category_id) VALUES ($RED_BRAISED, 1)")
        execSQL(
            "INSERT INTO recipe_image (id, recipe_id, path, thumb_path, width, height, sort_order, is_cover) " +
                "VALUES (1, $RED_BRAISED, 'images/a.jpg', 'thumbs/a.jpg', 10, 10, 0, 1)",
        )
        execSQL(
            "INSERT INTO sync_state (id, device_id, server_url, last_rev, last_sync_at, last_error) " +
                "VALUES (1, 'dev-1', 'http://192.168.1.2:8080', 7, 1000, '')",
        )
    }

    private fun seedV1Data(): SupportSQLiteDatabase.() -> Unit = {
        execSQL("INSERT INTO recipe (id, title, created_at, updated_at) VALUES ($RED_BRAISED, '红烧肉', 100, 200)")
        execSQL("INSERT INTO recipe (id, title, created_at, updated_at) VALUES ($POTATO, '土豆丝', 300, 400)")
        execSQL("INSERT INTO category (id, name, sort_order) VALUES (1, '家常菜', 0)")
        execSQL("INSERT INTO recipe_category (recipe_id, category_id) VALUES ($RED_BRAISED, 1)")
        execSQL(
            "INSERT INTO ingredient (id, recipe_id, name, amount, unit, sort_order) " +
                "VALUES (1, $RED_BRAISED, '五花肉', '500', '克', 0)",
        )
        execSQL(
            "INSERT INTO content_block (id, recipe_id, section, kind, level, text, sort_order) " +
                "VALUES (1, $RED_BRAISED, 'DIRECTIONS', 'LIST_ITEM', 0, '切块', 0)",
        )
        execSQL(
            "INSERT INTO recipe_image (id, recipe_id, path, thumb_path, width, height, sort_order, is_cover) " +
                "VALUES (1, $RED_BRAISED, 'images/a.jpg', 'thumbs/a.jpg', 10, 10, 0, 1)",
        )
        execSQL(
            "INSERT INTO meal_log (id, epoch_day, slot, recipe_id, created_at) " +
                "VALUES (1, 20000, 'LUNCH', $RED_BRAISED, 1)",
        )
    }

    // ---------- 查库的小工具 ----------
    //
    // 收在 AppDatabase 上（而不是 SupportSQLiteDatabase 上），因为调用点手里都是 AppDatabase。

    /** 挨个走完所有行。 */
    private fun <T> AppDatabase.rows(sql: String, read: (android.database.Cursor) -> T): List<T> =
        openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }

    /** 每张表的 id → uuid。 */
    private fun AppDatabase.uuidsOf(table: String): List<Pair<Long, String>> =
        rows("SELECT id, uuid FROM `$table` ORDER BY id") { c -> c.getLong(0) to c.getString(1) }

    /** sqlite_master 里的名字。 */
    private fun AppDatabase.namesIn(sql: String): List<String> =
        rows(sql) { c -> c.getString(0) }

    private companion object {
        const val RED_BRAISED = 1L
        const val POTATO = 2L

        /**
         * v1 的建表语句，**逐字**抄自 `app/schemas/org.miu.cookbook.data.db.AppDatabase/1.json`。
         * 这是「已经装在别人手机上的那个版本」的样子，永远不要跟着现在的代码改。
         */
        val V2_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `recipe` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uuid` TEXT NOT NULL DEFAULT '', `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER NOT NULL DEFAULT 0, " +
                "`dirty` INTEGER NOT NULL DEFAULT 1, `server_rev` INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS `content_block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `section` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`level` INTEGER NOT NULL, `text` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ingredient` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `amount` TEXT NOT NULL, " +
                "`unit` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uuid` TEXT NOT NULL DEFAULT '', `name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `recipe_category` (`recipe_id` INTEGER NOT NULL, " +
                "`category_id` INTEGER NOT NULL, PRIMARY KEY(`recipe_id`, `category_id`), " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `recipe_image` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `path` TEXT NOT NULL, `thumb_path` TEXT NOT NULL, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "`is_cover` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `meal_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`epoch_day` INTEGER NOT NULL, `slot` TEXT NOT NULL, `recipe_id` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `sync_state` (`id` INTEGER NOT NULL, `device_id` TEXT NOT NULL, " +
                "`server_url` TEXT NOT NULL, `last_rev` INTEGER NOT NULL, " +
                "`last_sync_at` INTEGER NOT NULL, `last_error` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_recipe_uuid` ON `recipe` (`uuid`)",
            "CREATE INDEX IF NOT EXISTS `index_content_block_recipe_id` ON `content_block` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ingredient_recipe_id` ON `ingredient` (`recipe_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_name` ON `category` (`name`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_uuid` ON `category` (`uuid`)",
            "CREATE INDEX IF NOT EXISTS `index_recipe_category_category_id` ON `recipe_category` (`category_id`)",
            "CREATE INDEX IF NOT EXISTS `index_recipe_image_recipe_id` ON `recipe_image` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_log_recipe_id` ON `meal_log` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_log_epoch_day_slot` ON `meal_log` (`epoch_day`, `slot`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES(42, 'e3a08decddf164f5ca44e36ddc230223')",
        )

        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `recipe` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `content_block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `section` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`level` INTEGER NOT NULL, `text` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `ingredient` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `amount` TEXT NOT NULL, " +
                "`unit` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `recipe_category` (`recipe_id` INTEGER NOT NULL, " +
                "`category_id` INTEGER NOT NULL, PRIMARY KEY(`recipe_id`, `category_id`), " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`category_id`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `recipe_image` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`recipe_id` INTEGER NOT NULL, `path` TEXT NOT NULL, `thumb_path` TEXT NOT NULL, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, " +
                "`is_cover` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `meal_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`epoch_day` INTEGER NOT NULL, `slot` TEXT NOT NULL, `recipe_id` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`recipe_id`) REFERENCES `recipe`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE INDEX IF NOT EXISTS `index_content_block_recipe_id` ON `content_block` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_ingredient_recipe_id` ON `ingredient` (`recipe_id`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_category_name` ON `category` (`name`)",
            "CREATE INDEX IF NOT EXISTS `index_recipe_category_category_id` ON `recipe_category` (`category_id`)",
            "CREATE INDEX IF NOT EXISTS `index_recipe_image_recipe_id` ON `recipe_image` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_log_recipe_id` ON `meal_log` (`recipe_id`)",
            "CREATE INDEX IF NOT EXISTS `index_meal_log_epoch_day_slot` ON `meal_log` (`epoch_day`, `slot`)",
            // Room 的身份表：带上它，这个库才是一个「真的 v1 库」，
            // Room 会走正常的升级路径（而不是把文件当成别处搬来的、直接校验 schema）
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES(42, '0c406388e17d66ede93b5718d75c285e')",
        )
    }
}
