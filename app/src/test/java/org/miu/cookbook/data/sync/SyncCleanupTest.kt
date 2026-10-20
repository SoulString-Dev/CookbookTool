package org.miu.cookbook.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.data.CookbookRepository
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.image.ImageStore
import org.miu.cookbook.data.image.ImportedImage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「删文件」和「清垃圾」这两件事的规矩。
 *
 * 它们都是**不可撤销**的（磁盘上的东西删了就没了），所以单独立一组测试：
 * 一张图被两道菜引用时不能误删，孤儿文件该回收，过期的墓碑该清。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncCleanupTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CookbookRepository
    private lateinit var baseDir: File
    private lateinit var store: SyncStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        baseDir = File(context.cacheDir, "files-${System.nanoTime()}").apply { mkdirs() }
        File(baseDir, ImageStore.IMAGE_DIR).mkdirs()
        File(baseDir, ImageStore.THUMB_DIR).mkdirs()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CookbookRepository(db, baseDir)
        store = SyncStore(db, baseDir)
    }

    @After
    fun tearDown() {
        db.close()
        baseDir.deleteRecursively()
    }

    /** 造一对「原图 + 缩略图」文件，返回（原图文件, 可以直接挂到食谱上的记录）。 */
    private fun writeImage(name: String): Pair<File, ImportedImage> {
        val image = File(baseDir, "${ImageStore.IMAGE_DIR}/$name.jpg")
            .apply { writeBytes(ByteArray(64) { (it * 3).toByte() }) }
        val thumb = File(baseDir, "${ImageStore.THUMB_DIR}/$name.jpg")
            .apply { writeBytes(ByteArray(16) { (it * 5).toByte() }) }
        return image to ImportedImage(
            path = "${ImageStore.IMAGE_DIR}/$name.jpg",
            thumbPath = "${ImageStore.THUMB_DIR}/$name.jpg",
            width = 40,
            height = 30,
            sha256 = ImageStore.sha256Of(image)!!,
            thumbSha256 = ImageStore.sha256Of(thumb)!!,
        )
    }

    @Test
    fun `两道菜用同一张图，删其中一张不会把文件删掉`() = runBlocking {
        val (imageFile, imported) = writeImage("shared")
        val first = repository.createRecipe("红烧肉")
        val second = repository.createRecipe("可乐鸡翅")
        repository.addImage(first, imported)
        repository.addImage(second, imported)

        repository.deleteImage(db.imageDao().listFor(first).single())
        assertTrue("另一道菜还在用这张图，文件不能删", imageFile.isFile)

        repository.deleteImage(db.imageDao().listFor(second).single())
        assertFalse("最后一份引用也没了，文件该回收", imageFile.isFile)
    }

    @Test
    fun `同步末尾会清掉过期的墓碑和没人引用的图`() = runBlocking {
        val (keptFile, imported) = writeImage("kept")
        val kept = repository.createRecipe("在的")
        repository.addImage(kept, imported)

        // 孤儿文件：没人引用，而且已经放了两天
        val orphan = File(baseDir, "${ImageStore.IMAGE_DIR}/orphan.jpg")
            .apply { writeBytes(ByteArray(8)) }
        orphan.setLastModified(System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L)

        // 两年前删掉的菜，墓碑该清了
        val longGone = repository.createRecipe("早删了")
        db.recipeDao().softDeleteByIds(
            listOf(longGone),
            System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000,
        )

        val (purged, removed) = store.cleanupOrphans()

        assertEquals("过期墓碑清掉一条", 1, purged)
        assertEquals("孤儿图回收一个", 1, removed)
        assertFalse(orphan.isFile)
        assertTrue("还在用的图不能动", keptFile.isFile)
        assertEquals(listOf("在的"), repository.recipeList.first().map { it.recipe.title })
    }

    @Test
    fun `刚导入还没写行的文件不会被当成垃圾`() = runBlocking {
        val fresh = File(baseDir, "${ImageStore.IMAGE_DIR}/fresh.jpg")
            .apply { writeBytes(ByteArray(8)) }

        val (_, removed) = store.cleanupOrphans()

        assertEquals(0, removed)
        assertTrue("一天内的文件要留着，可能正在导入", fresh.isFile)
    }
}
