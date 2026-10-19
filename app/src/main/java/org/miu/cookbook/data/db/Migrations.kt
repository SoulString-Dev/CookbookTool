package org.miu.cookbook.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.miu.cookbook.data.newUuid

/**
 * v1 → v2：为家庭共享打底。
 *
 * 这一版只改数据结构，还不能同步：给食谱加上跨设备身份（uuid）、删除墓碑（deleted_at）、
 * 待上传标记（dirty）、服务端版本号（server_rev），给分类加上 uuid，再加一张同步游标表。
 *
 * 顺序上有个**必须遵守**的点：`uuid` 的唯一索引要等所有存量行都回填完再建。
 * 反过来写（先建索引再回填）的话，存量行全是 `''`，第二条就撞唯一约束——
 * 迁移直接失败，用户的开机第一眼就是闪退。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipe ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recipe ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0")
        // 存量食谱一律标脏：它们都还没上传过，不标脏就永远不会被推上去
        db.execSQL("ALTER TABLE recipe ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE recipe ADD COLUMN server_rev INTEGER NOT NULL DEFAULT 0")

        backfillUuids(db, "recipe")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recipe_uuid ON recipe (uuid)")

        db.execSQL("ALTER TABLE category ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
        backfillUuids(db, "category")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_category_uuid ON category (uuid)")

        db.execSQL(CREATE_SYNC_STATE)
    }

    /**
     * 逐行回填 uuid。SQLite 没有 `uuid()` 之类的函数，只能查出来一条条 UPDATE。
     *
     * 之所以敢一行一条 UPDATE：这张表是家庭食谱，量级是几百行，
     * 一次性包在 Room 的迁移事务里，慢一点也无所谓。
     */
    private fun backfillUuids(db: SupportSQLiteDatabase, table: String) {
        val ids = mutableListOf<Long>()
        db.query("SELECT id FROM `$table` WHERE uuid = ''").use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        }
        ids.forEach { id ->
            db.execSQL("UPDATE `$table` SET uuid = ? WHERE id = ?", arrayOf<Any>(newUuid(), id))
        }
    }
}

/**
 * v2 → v3：图片改按内容寻址，为同步做准备。
 *
 * 只加两列和两个索引，不改任何既有行的语义：存量行的 `sha256` 是空串，
 * 等同步时惰性补算（要读一遍文件才算得出来，放在迁移里做会让升级卡住）。
 * **空哈希的图片不上传**，宁可少一张图也不要传一个服务端对不上的引用。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recipe_image ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE recipe_image ADD COLUMN thumb_sha256 TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_image_sha256 ON recipe_image (sha256)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_recipe_image_thumb_sha256 ON recipe_image (thumb_sha256)")
    }
}

/**
 * 这张表的 DDL 必须和 Room 为 v2 生成的**逐字一致**（列名、顺序、NOT NULL），
 * 否则 Room 打开库时校验 schema 会直接抛「Migration didn't properly handle」。
 * 内容抄自 `app/schemas/…/2.json` 的 createSql。
 */
private const val CREATE_SYNC_STATE = """
    CREATE TABLE IF NOT EXISTS `sync_state` (
        `id` INTEGER NOT NULL,
        `device_id` TEXT NOT NULL,
        `server_url` TEXT NOT NULL,
        `last_rev` INTEGER NOT NULL,
        `last_sync_at` INTEGER NOT NULL,
        `last_error` TEXT NOT NULL,
        PRIMARY KEY(`id`)
    )
"""
