package org.miu.cookbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.SyncStateEntity
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.domain.meal.MealSlot
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M6 的验收：家庭共享要用的那几根地基。
 *
 * 这些用例盯的不是「功能能用」，而是**同步的语义前提**有没有被破坏：
 * 每行有没有跨设备身份、删除是不是留了墓碑、本机删除和远端删除是不是分得开。
 * 这几条一旦悄悄退化，同步会在别的地方以很难查的方式出错
 * （比如两台机器各建一个「家常菜」、或者家人删菜把我的饮食历史一起带走）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncFoundationTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CookbookRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = CookbookRepository(db, context.filesDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 跨设备身份 ----------

    @Test
    fun `新建的食谱都带着一个不重复的跨设备身份`() {
        val first = newRecipe("红烧肉")
        val second = newRecipe("土豆丝")

        val a = row(first)
        val b = row(second)
        assertEquals("标准 UUID 是 36 位", 36, a.uuid.length)
        assertNotEquals("两台设备各建一道同名菜也不该撞", a.uuid, b.uuid)
        assertEquals("按 uuid 要能找回同一行", first, rowByUuid(a.uuid)!!.id)
        assertTrue("新行默认就是待上传的", a.dirty)
    }

    @Test
    fun `分类也有身份，重复 ensure 同一个名字不会造出第二条`() {
        val id = runBlocking { repository.ensureCategory("家常菜") }
        runBlocking { repository.ensureCategory("家常菜") }

        val category = runBlocking { repository.categories.first() }.single()
        assertEquals(id, category.id)
        assertEquals(36, category.uuid.length)
        assertEquals(category.id, runBlocking { db.categoryDao().findByUuid(category.uuid) }!!.id)
    }

    // ---------- 删除：墓碑 vs 本机清理 ----------

    @Test
    fun `本机删掉的食谱留的是墓碑，界面看不到但行还在`() {
        val id = newRecipe("红烧肉")
        val uuid = row(id).uuid
        runBlocking { repository.deleteRecipes(listOf(id)) }

        assertTrue("删掉的菜不该再出现在列表里", runBlocking { repository.recipeList.first() }.isEmpty())
        val tombstone = rowByUuid(uuid)!!
        assertTrue("墓碑上要有删除时刻", tombstone.deletedAt > 0)
        assertTrue("删除本身也要能传出去", tombstone.dirty)
    }

    @Test
    fun `本机删除会带走本机的饮食记录和图片行`() {
        val id = newRecipe("红烧肉")
        runBlocking { repository.logMeal(id, today(), MealSlot.LUNCH) }
        runBlocking { repository.addImage(id, imported("local")) }

        runBlocking { repository.deleteRecipes(listOf(id)) }

        assertEquals(0, runBlocking { repository.countMealLogs(listOf(id)) })
        assertTrue(runBlocking { repository.images(id) }.isEmpty())
    }

    @Test
    fun `家人删掉了菜，我这边的饮食记录和图片还在`() {
        val id = newRecipe("红烧肉")
        val uuid = row(id).uuid
        runBlocking { repository.logMeal(id, today(), MealSlot.LUNCH) }
        runBlocking { repository.addImage(id, imported("mine")) }

        val applied = runBlocking { repository.applyRemoteTombstone(uuid, System.currentTimeMillis()) }

        assertTrue(applied)
        assertTrue("列表里同样看不到了", runBlocking { repository.recipeList.first() }.isEmpty())
        assertEquals("我吃过什么是我的历史", 1, runBlocking { repository.countMealLogs(listOf(id)) })
        assertEquals("磁盘上的图是家人删的那台机器负责清的，不是这台", 1, runBlocking { repository.images(id) }.size)
        assertFalse("墓碑是家人推来的，不该再被我原样推回去", rowByUuid(uuid)!!.dirty)
    }

    @Test
    fun `墓碑落地之后我再改这道菜，改动要重新变脏`() {
        val id = newRecipe("红烧肉")
        val uuid = row(id).uuid
        runBlocking { repository.applyRemoteTombstone(uuid, System.currentTimeMillis()) }

        runBlocking { repository.renameRecipe(id, "红烧肉（我的版本）") }

        assertTrue(rowByUuid(uuid)!!.dirty)
    }

    @Test
    fun `不认识的 uuid 和时间戳都是无害的`() {
        assertFalse("本机没有这道菜，就当没这回事", runBlocking { repository.applyRemoteTombstone(newUuid(), 123L) })

        val id = newRecipe("红烧肉")
        assertFalse(
            "非正的删除时刻不是有效墓碑，别拿它误伤一行",
            runBlocking { repository.applyRemoteTombstone(row(id).uuid, 0L) },
        )
        assertNull("这行应该还好好的活着", runBlocking { db.recipeDao().findByUuid(row(id).uuid) }!!.takeIf { it.deletedAt > 0 })
    }

    @Test
    fun `墓碑清理只清过期的`() {
        val id = newRecipe("红烧肉")
        val uuid = row(id).uuid
        val before = System.currentTimeMillis()
        runBlocking { repository.deleteRecipes(listOf(id)) }

        assertEquals("还没到期的不许动", 0, runBlocking { db.recipeDao().purgeTombstones(before) })
        assertEquals(1, runBlocking { db.recipeDao().purgeTombstones(before + 60_000) })
        assertNull("清完之后行才真的没了", rowByUuid(uuid))
    }

    // ---------- 同步游标 ----------

    @Test
    fun `本机标识只生成一次，而且不会顺手清掉同步游标`() {
        val generated = runBlocking { repository.deviceId() }
        assertEquals("第二次问还是同一个", generated, runBlocking { repository.deviceId() })

        runBlocking { repository.saveSyncState(SyncStateEntity(deviceId = generated, lastRev = 42)) }

        assertEquals(generated, runBlocking { repository.deviceId() })
        assertEquals("游标不能被 deviceId 顺手重写", 42L, runBlocking { repository.currentSyncState() }!!.lastRev)
    }

    // ---------- 小工具 ----------

    private fun newRecipe(title: String): Long = runBlocking { repository.createRecipe(title) }

    private fun row(id: Long) = runBlocking { db.recipeDao().findById(id) }!!

    /** 故意用 findByUuid：它不过滤墓碑，正是验证墓碑要用的入口。 */
    private fun rowByUuid(uuid: String) = runBlocking { db.recipeDao().findByUuid(uuid) }

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    private fun imported(name: String) = ImportedImage(
        path = "images/$name.jpg",
        thumbPath = "thumbs/$name.jpg",
        width = 100,
        height = 80,
    )
}
