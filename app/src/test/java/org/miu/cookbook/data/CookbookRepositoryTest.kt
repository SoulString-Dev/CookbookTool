package org.miu.cookbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.MealLogEntity
import org.miu.cookbook.data.db.MealLogRow
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.data.backup.BackupArchive
import org.miu.cookbook.domain.backup.BackupCodec
import org.miu.cookbook.domain.backup.BackupFile
import org.miu.cookbook.domain.backup.BackupImage
import org.miu.cookbook.domain.backup.BackupMealLog
import org.miu.cookbook.domain.backup.BackupRecipe
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.IngredientLine
import org.miu.cookbook.domain.recipe.MAX_RECIPE_IMAGES
import org.miu.cookbook.ui.recipes.contentLines
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 仓库层的用例，跑在真 Room（内存库）上。
 *
 * 分类、改名、级联删除这些逻辑都收在仓库里，界面只是薄薄一层胶水——
 * 而 Robolectric 下「弹窗里带输入框」的界面测试跑不动（见 RecipeListScreenTest 的说明），
 * 所以真正要保证的正确性放在这里测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookbookRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CookbookRepository
    private lateinit var baseDir: java.io.File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        baseDir = context.filesDir
        repository = CookbookRepository(db, baseDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun titles(): List<String> = runBlocking {
        repository.recipeList.first().map { it.recipe.title }
    }

    private fun categoryNames(): List<String> = runBlocking {
        repository.categories.first().map { it.name }
    }

    private fun linksOf(recipeId: Long): Set<Long> = runBlocking {
        repository.linkedCategoryIds(recipeId)
    }

    private fun detailOf(recipeId: Long) = runBlocking {
        repository.recipeDetail(recipeId).first()!!
    }

    private fun history(): List<MealLogRow> = runBlocking { repository.mealHistory.first() }

    /** 从今天往前数第 n 天。 */
    private fun day(n: Long): Long = java.time.LocalDate.now().toEpochDay() - n

    @Test
    fun `新建食谱会去掉首尾空格`() {
        runBlocking { repository.createRecipe("  番茄炒蛋 ") }
        assertEquals(listOf("番茄炒蛋"), titles())
    }

    @Test
    fun `改名会写进去，空名字则忽略`() {
        val id = runBlocking { repository.createRecipe("番茄炒蛋") }

        runBlocking { repository.renameRecipe(id, "  西红柿炒鸡蛋  ") }
        assertEquals(listOf("西红柿炒鸡蛋"), titles())

        runBlocking { repository.renameRecipe(id, "   ") }
        assertEquals(listOf("西红柿炒鸡蛋"), titles())
    }

    @Test
    fun `同名分类只会建一个`() {
        val first = runBlocking { repository.ensureCategory("家常菜") }
        val second = runBlocking { repository.ensureCategory(" 家常菜 ") }

        assertEquals(first, second)
        assertEquals(listOf("家常菜"), categoryNames())
    }

    @Test
    fun `空分类名报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.ensureCategory("  ") }
        }
    }

    @Test
    fun `单个食谱设分类是多退少补`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }
        val home = runBlocking { repository.ensureCategory("家常菜") }
        val quick = runBlocking { repository.ensureCategory("快手菜") }
        val soup = runBlocking { repository.ensureCategory("汤") }

        runBlocking { repository.setCategories(recipe, setOf(home, quick)) }
        assertEquals(setOf(home, quick), linksOf(recipe))

        // 去掉家常菜、添上汤：快手菜不能被顺手删掉
        runBlocking { repository.setCategories(recipe, setOf(quick, soup)) }
        assertEquals(setOf(quick, soup), linksOf(recipe))
    }

    @Test
    fun `批量加分类只加不减`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        val b = runBlocking { repository.createRecipe("土豆丝") }
        val home = runBlocking { repository.ensureCategory("家常菜") }
        val quick = runBlocking { repository.ensureCategory("快手菜") }

        runBlocking { repository.setCategories(a, setOf(home)) }
        runBlocking { repository.addCategories(listOf(a, b), setOf(quick)) }

        // a 既保留原来的家常菜，又加上了快手菜；b 只拿到快手菜
        assertEquals(setOf(home, quick), linksOf(a))
        assertEquals(setOf(quick), linksOf(b))
    }

    @Test
    fun `删食谱会级联删掉饮食记录`() {
        val keep = runBlocking { repository.createRecipe("土豆丝") }
        val drop = runBlocking { repository.createRecipe("番茄炒蛋") }
        val dao = db.mealLogDao()
        runBlocking {
            dao.insert(MealLogEntity(epochDay = 20_000, slot = MealSlot.LUNCH, recipeId = drop, createdAt = 1L))
            dao.insert(MealLogEntity(epochDay = 20_001, slot = MealSlot.DINNER, recipeId = drop, createdAt = 2L))
            dao.insert(MealLogEntity(epochDay = 20_001, slot = MealSlot.DINNER, recipeId = keep, createdAt = 3L))
        }
        assertEquals(2, runBlocking { repository.countMealLogs(listOf(drop)) })
        assertEquals(3, runBlocking { repository.countMealLogs(listOf(drop, keep)) })

        runBlocking { repository.deleteRecipes(listOf(drop)) }

        assertEquals(listOf("土豆丝"), titles())
        assertEquals(0, runBlocking { repository.countMealLogs(listOf(drop)) })
        assertEquals(1, runBlocking { repository.countMealLogs(listOf(keep)) })
    }

    @Test
    fun `列表按最近添加倒序`() {
        val first = runBlocking { repository.createRecipe("第一道") }
        Thread.sleep(5)
        val second = runBlocking { repository.createRecipe("第二道") }

        assertNotEquals(first, second)
        assertEquals(listOf("第二道", "第一道"), titles())
    }

    @Test
    fun `原料整份替换后按顺序读回来`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }
        runBlocking {
            repository.setIngredients(
                recipe,
                listOf(
                    IngredientLine("鸡蛋", "3", "个"),
                    IngredientLine("番茄", "2", "个"),
                    IngredientLine(), // 空行要被丢掉
                    IngredientLine("盐", "适量", ""),
                ),
            )
        }

        val stored = runBlocking { detailOf(recipe) }
        assertEquals(
            listOf(
                IngredientLine("鸡蛋", "3", "个"),
                IngredientLine("番茄", "2", "个"),
                IngredientLine("盐", "适量", ""),
            ),
            stored.ingredients.sortedBy { it.sortOrder }
                .map { IngredientLine(it.name, it.amount, it.unit) },
        )

        // 再存一次只有一行，之前的三行不能剩下来
        runBlocking { repository.setIngredients(recipe, listOf(IngredientLine("番茄", "1", "个"))) }
        assertEquals(
            listOf(IngredientLine("番茄", "1", "个")),
            runBlocking { detailOf(recipe) }.ingredients.map { IngredientLine(it.name, it.amount, it.unit) },
        )
    }

    @Test
    fun `做法带层级和三级标题存读一致`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }
        val directions = listOf(
            ContentLine("打散鸡蛋"),
            ContentLine("热锅下油"),
            ContentLine("下蛋液", level = 1),
            ContentLine("火候", isHeading = true),
            ContentLine("中小火"),
        )

        runBlocking { repository.setContent(recipe, ContentSection.DIRECTIONS, directions) }

        val stored = runBlocking { detailOf(recipe) }
        val lines = stored.contentLines(ContentSection.DIRECTIONS)
        assertEquals(directions, lines)
        // 做法不会跑到备注里去
        assertEquals(emptyList<ContentLine>(), stored.contentLines(ContentSection.NOTE))

        // 备注是另一段，互不干扰
        runBlocking {
            repository.setContent(recipe, ContentSection.NOTE, listOf(ContentLine("剩下的汤别倒")))
        }
        val after = runBlocking { detailOf(recipe) }
        assertEquals(directions, after.contentLines(ContentSection.DIRECTIONS))
        assertEquals(listOf(ContentLine("剩下的汤别倒")), after.contentLines(ContentSection.NOTE))
    }

    @Test
    fun `第一张图自动成为列表封面`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }

        val first = runBlocking { repository.addImage(recipe, imported("a")) }!!
        val second = runBlocking { repository.addImage(recipe, imported("b")) }!!

        val images = runBlocking { repository.images(recipe) }
        assertEquals(listOf("images/a.jpg", "images/b.jpg"), images.map { it.path })
        assertEquals(listOf(0, 1), images.map { it.sortOrder })
        assertEquals(first, images.single { it.isCover }.id)

        // 手动改成第二张
        runBlocking { repository.setCover(recipe, second) }
        val afterCover = runBlocking { repository.images(recipe) }
        assertEquals(second, afterCover.single { it.isCover }.id)

        // 删掉当前封面，封面要自动落到剩下那张上，顺序也要重排成 0..n
        runBlocking { repository.deleteImage(afterCover.single { it.id == second }) }
        val afterDelete = runBlocking { repository.images(recipe) }
        assertEquals(listOf("images/a.jpg"), afterDelete.map { it.path })
        assertEquals(listOf(0), afterDelete.map { it.sortOrder })
        assertEquals(first, afterDelete.single().id)
        assertEquals(true, afterDelete.single().isCover)
    }

    @Test
    fun `拖动图片会换顺序且封面跟着图走`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }
        runBlocking {
            repository.addImage(recipe, imported("a"))
            repository.addImage(recipe, imported("b"))
            repository.addImage(recipe, imported("c"))
        }

        runBlocking { repository.moveImage(recipe, from = 2, to = 0) }

        val images = runBlocking { repository.images(recipe) }
        assertEquals(listOf("images/c.jpg", "images/a.jpg", "images/b.jpg"), images.map { it.path })
        assertEquals(listOf(0, 1, 2), images.map { it.sortOrder })
        // 封面还是原来那一张（第一张 a），不会因为换了位置就跑到别处
        assertEquals("images/a.jpg", images.single { it.isCover }.path)
    }

    @Test
    fun `记录一顿会带上菜名和封面`() {
        val recipe = runBlocking { repository.createRecipe("番茄炒蛋") }
        runBlocking {
            repository.addImage(recipe, imported("a"))
            repository.logMeal(recipe, day(0), MealSlot.DINNER)
        }

        val row = history().single()
        assertEquals(recipe, row.recipeId)
        assertEquals("番茄炒蛋", row.title)
        assertEquals("images/a.jpg", row.coverPath)
        assertEquals(MealSlot.DINNER, row.slot)
        assertEquals(day(0), row.epochDay)
    }

    @Test
    fun `同一天同一餐可以记多道菜`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        val b = runBlocking { repository.createRecipe("土豆丝") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.LUNCH)
            repository.logMeal(b, day(0), MealSlot.LUNCH)
        }

        val rows = history()
        assertEquals(2, rows.size)
        assertEquals(setOf(a, b), rows.map { it.recipeId }.toSet())
        assertEquals(listOf(MealSlot.LUNCH, MealSlot.LUNCH), rows.map { it.slot })
    }

    @Test
    fun `改到已经有记录的那一餐时两餐整体交换`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        val b = runBlocking { repository.createRecipe("面条") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.BREAKFAST)
            repository.logMeal(b, day(0), MealSlot.DINNER)
        }

        val swapped = runBlocking {
            repository.moveMealSlotTo(day(0), MealSlot.BREAKFAST, day(0), MealSlot.DINNER)
        }

        assertTrue(swapped)
        val rows = history().associateBy { it.title }
        // 豆浆去了晚餐，面条换到早餐 —— 谁都没丢
        assertEquals(MealSlot.DINNER, rows.getValue("豆浆").slot)
        assertEquals(MealSlot.BREAKFAST, rows.getValue("面条").slot)
        assertEquals(2, history().size)
    }

    @Test
    fun `跨天改餐时目标那天有记录也是交换`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        val b = runBlocking { repository.createRecipe("面条") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.LUNCH)
            repository.logMeal(b, day(5), MealSlot.DINNER)
        }

        val swapped = runBlocking {
            repository.moveMealSlotTo(day(0), MealSlot.LUNCH, day(5), MealSlot.DINNER)
        }

        assertTrue(swapped)
        val rows = history().associateBy { it.title }
        assertEquals(day(5), rows.getValue("豆浆").epochDay)
        assertEquals(MealSlot.DINNER, rows.getValue("豆浆").slot)
        assertEquals(day(0), rows.getValue("面条").epochDay)
        assertEquals(MealSlot.LUNCH, rows.getValue("面条").slot)
    }

    @Test
    fun `改到空着的那一餐时只是搬过去`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        runBlocking { repository.logMeal(a, day(0), MealSlot.BREAKFAST) }

        val swapped = runBlocking {
            repository.moveMealSlotTo(day(0), MealSlot.BREAKFAST, day(0), MealSlot.LUNCH)
        }

        assertFalse(swapped)
        assertEquals(MealSlot.LUNCH, history().single().slot)
    }

    @Test
    fun `同一餐里有多道菜时整餐一起交换`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        val b = runBlocking { repository.createRecipe("油条") }
        val c = runBlocking { repository.createRecipe("面条") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.BREAKFAST)
            repository.logMeal(b, day(0), MealSlot.BREAKFAST)
            repository.logMeal(c, day(0), MealSlot.DINNER)
        }

        runBlocking { repository.moveMealSlotTo(day(0), MealSlot.BREAKFAST, day(0), MealSlot.DINNER) }

        val rows = history().associate { it.title to it.slot }
        assertEquals(MealSlot.DINNER, rows.getValue("豆浆"))
        assertEquals(MealSlot.DINNER, rows.getValue("油条"))
        assertEquals(MealSlot.BREAKFAST, rows.getValue("面条"))
        assertEquals(3, history().size)
    }

    @Test
    fun `记错日期可以把某一餐改到别的日子`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        runBlocking { repository.logMeal(a, day(0), MealSlot.BREAKFAST) }

        runBlocking { repository.moveMealSlotTo(day(0), MealSlot.BREAKFAST, day(5), MealSlot.DINNER) }

        val row = history().single()
        assertEquals(day(5), row.epochDay)
        assertEquals(MealSlot.DINNER, row.slot)
    }

    @Test
    fun `删掉某一天不影响别的天`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.DINNER)
            repository.logMeal(a, day(1), MealSlot.LUNCH)
        }

        runBlocking { repository.deleteMealDay(day(0)) }

        val rows = history()
        assertEquals(listOf(day(1)), rows.map { it.epochDay })
        assertEquals(1, runBlocking { repository.countMealLogsForDay(day(1)) })
        assertEquals(0, runBlocking { repository.countMealLogsForDay(day(0)) })
    }

    @Test
    fun `可以按条删记录`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.DINNER)
            repository.logMeal(a, day(1), MealSlot.DINNER)
        }

        runBlocking { repository.deleteMealLogs(listOf(history().first().logId)) }

        assertEquals(listOf(day(0)), history().map { it.epochDay })
    }

    @Test
    fun `某道菜自己的历史按时间正序`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        val b = runBlocking { repository.createRecipe("土豆丝") }
        runBlocking {
            repository.logMeal(a, day(3), MealSlot.DINNER)
            repository.logMeal(a, day(0), MealSlot.LUNCH)
            repository.logMeal(b, day(1), MealSlot.LUNCH)
        }

        val rows = runBlocking { repository.mealHistoryOf(a).first() }
        // 正序：早的在前（详情页打开时会自己滚到底，最新那条照样一眼看到）
        assertEquals(listOf(day(3), day(0)), rows.map { it.epochDay })
        assertEquals(listOf(a, a), rows.map { it.recipeId })
    }

    @Test
    fun `删掉食谱会连带删掉它的记录`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        val b = runBlocking { repository.createRecipe("土豆丝") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.DINNER)
            repository.logMeal(b, day(0), MealSlot.LUNCH)
        }

        runBlocking { repository.deleteRecipes(listOf(a)) }

        assertEquals(listOf(b), history().map { it.recipeId })
    }


    // ---------- 备份 ----------

    /** 造一份「什么都有」的数据：分类、原料、正文、真图、饮食记录。 */
    private suspend fun seedEverything(): Long {
        val recipeId = repository.createRecipe("番茄炒蛋")
        repository.setCategories(recipeId, setOf(repository.ensureCategory("家常菜")))
        repository.setIngredients(
            recipeId,
            listOf(IngredientLine("鸡蛋", "2", "个"), IngredientLine("番茄", "3", "个")),
        )
        repository.setContent(
            recipeId,
            ContentSection.DIRECTIONS,
            listOf(ContentLine("打蛋", 0), ContentLine("下锅", 1)),
        )
        repository.setContent(
            recipeId,
            ContentSection.NOTE,
            listOf(ContentLine("火候", 0, isHeading = true)),
        )
        // 图片要真的落在磁盘上，导入时才不会被当成「文件丢了」丢掉
        File(baseDir, "images").mkdirs()
        File(baseDir, "thumbs").mkdirs()
        File(baseDir, "images/real.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(baseDir, "thumbs/real.jpg").writeBytes(byteArrayOf(9))
        repository.addImage(recipeId, imported("real"))
        repository.logMeal(recipeId, day(0), MealSlot.DINNER)
        repository.logMeal(recipeId, day(1), MealSlot.BREAKFAST)
        return recipeId
    }

    @Test
    fun `导出再导入，菜名分类原料正文图片和饮食记录都回来了`() = runBlocking {
        val recipeId = seedEverything()
        val expectedDetail = detailOf(recipeId)

        val bytes = ByteArrayOutputStream()
            .also { repository.exportBackup(it) }
            .toByteArray()

        // 先清空，证明后面看到的东西都是导入带回来的
        repository.deleteRecipes(listOf(recipeId))
        assertTrue(repository.recipeList.first().isEmpty())

        repository.importBackup(ByteArrayInputStream(bytes))

        val list = repository.recipeList.first()
        assertEquals(1, list.size)
        val row = list.single()
        assertEquals(recipeId, row.recipe.id)          // id 原样保留
        assertEquals("番茄炒蛋", row.recipe.title)
        assertEquals(listOf("家常菜"), row.categories.map { it.name })
        assertEquals(listOf("鸡蛋", "番茄"), row.ingredients.map { it.name })

        val detail = detailOf(recipeId)
        assertEquals(
            listOf(ContentLine("打蛋", 0), ContentLine("下锅", 1)),
            detail.contentLines(ContentSection.DIRECTIONS),
        )
        assertEquals(
            listOf(ContentLine("火候", 0, isHeading = true)),
            detail.contentLines(ContentSection.NOTE),
        )

        assertEquals(1, detail.images.size)
        assertEquals("images/real.jpg", detail.images.single().path)
        assertTrue(detail.images.single().isCover)
        assertTrue(File(baseDir, "images/real.jpg").isFile)

        assertEquals(2, history().size)
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.DINNER), history().map { it.slot })
        assertEquals(expectedDetail.recipe.title, history().first().title)
    }

    @Test
    fun `导入是整库替换，旧数据不会留下来`() = runBlocking {
        val keep = seedEverything()
        val bytes = ByteArrayOutputStream()
            .also { repository.exportBackup(it) }
            .toByteArray()

        // 导出之后再添一道菜，导入后它应该消失
        val extra = repository.createRecipe("导出之后才加的菜")
        assertEquals(2, repository.recipeList.first().size)

        repository.importBackup(ByteArrayInputStream(bytes))

        assertEquals(listOf(keep), repository.recipeList.first().map { it.recipe.id })
    }

    @Test
    fun `导入会把图片目录换成备份里的那一份`() = runBlocking {
        seedEverything()
        val bytes = ByteArrayOutputStream()
            .also { repository.exportBackup(it) }
            .toByteArray()

        // 模拟「换了台设备」：本地图片全没了
        File(baseDir, "images").deleteRecursively()
        File(baseDir, "thumbs").deleteRecursively()
        assertFalse(File(baseDir, "images/real.jpg").isFile)

        repository.importBackup(ByteArrayInputStream(bytes))

        assertTrue(File(baseDir, "images/real.jpg").isFile)
        assertTrue(File(baseDir, "thumbs/real.jpg").isFile)
        assertFalse(File(baseDir, "restore-tmp").exists())
    }

    @Test
    fun `备份里没有的图片文件，导入时那条记录会被丢掉而不是报错`() = runBlocking {
        val recipeId = repository.createRecipe("有记录没图")
        repository.addImage(recipeId, imported("ghost"))
        val bytes = ByteArrayOutputStream()
            .also { repository.exportBackup(it) }
            .toByteArray()

        repository.importBackup(ByteArrayInputStream(bytes))

        assertTrue(repository.images(recipeId).isEmpty())
        assertEquals("有记录没图", repository.recipeList.first().single().recipe.title)
    }

    @Test
    fun `不是备份文件时导入失败，且不会把原来的数据弄丢`() = runBlocking {
        seedEverything()
        val before = repository.recipeList.first().size

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.importBackup(ByteArrayInputStream("乱七八糟".toByteArray())) }
        }

        assertEquals(before, repository.recipeList.first().size)
    }

    // ---------- 审查后的加固 ----------

    @Test
    fun `删除食谱会把磁盘上的图片一起删掉`() = runBlocking {
        val recipeId = repository.createRecipe("带图")
        File(baseDir, "images").mkdirs()
        File(baseDir, "thumbs").mkdirs()
        File(baseDir, "images/gone.jpg").writeBytes(byteArrayOf(1))
        File(baseDir, "thumbs/gone.jpg").writeBytes(byteArrayOf(2))
        repository.addImage(recipeId, imported("gone"))

        repository.deleteRecipes(listOf(recipeId))

        assertFalse(File(baseDir, "images/gone.jpg").isFile)
        assertFalse(File(baseDir, "thumbs/gone.jpg").isFile)
    }

    @Test
    fun `图片张数到了上限就挂不上去`() = runBlocking {
        val recipeId = repository.createRecipe("图多")
        repeat(MAX_RECIPE_IMAGES) { index ->
            assertNotNull(repository.addImage(recipeId, imported("limit$index")))
        }

        assertNull(repository.addImage(recipeId, imported("overflow")))
        assertEquals(MAX_RECIPE_IMAGES, repository.images(recipeId).size)
    }

    @Test
    fun `备份里越界的图片路径会被丢掉`() = runBlocking {
        val recipeId = repository.createRecipe("路径不对")
        val backup = BackupFile.create(
            recipes = listOf(
                BackupRecipe(
                    id = recipeId,
                    title = "路径不对",
                    createdAt = 1,
                    updatedAt = 1,
                    images = listOf(
                        BackupImage(
                            file = "/data/data/org.miu.cookbook/evil.jpg",
                            thumbFile = "thumbs/evil.jpg",
                            width = 1,
                            height = 1,
                            sortOrder = 0,
                            isCover = true,
                        ),
                        BackupImage(
                            file = "images/../../evil.jpg",
                            thumbFile = "thumbs/evil.jpg",
                            width = 1,
                            height = 1,
                            sortOrder = 1,
                            isCover = false,
                        ),
                    ),
                ),
            ),
        )

        repository.importBackup(ByteArrayInputStream(zipOf(backup)))

        assertTrue(repository.images(recipeId).isEmpty())
    }

    @Test
    fun `备份里越界的日期会被丢掉`() = runBlocking {
        val recipeId = repository.createRecipe("日期不对")
        val backup = BackupFile.create(
            recipes = listOf(
                BackupRecipe(id = recipeId, title = "日期不对", createdAt = 1, updatedAt = 1),
            ),
            mealLogs = listOf(
                BackupMealLog(recipeId = recipeId, epochDay = Long.MIN_VALUE, slot = MealSlot.DINNER.name, createdAt = 1),
                BackupMealLog(recipeId = recipeId, epochDay = 9_999_999, slot = MealSlot.LUNCH.name, createdAt = 1),
                BackupMealLog(recipeId = recipeId, epochDay = day(0), slot = MealSlot.BREAKFAST.name, createdAt = 1),
            ),
        )

        repository.importBackup(ByteArrayInputStream(zipOf(backup)))

        assertEquals(listOf(MealSlot.BREAKFAST), history().map { it.slot })
    }

    @Test
    fun `原料的空行不会让顺序跳号`() = runBlocking {
        val recipeId = repository.createRecipe("顺序")
        repository.setIngredients(
            recipeId,
            listOf(
                IngredientLine("鸡蛋", "2", "个"),
                IngredientLine(),
                IngredientLine("番茄", "3", "个"),
            ),
        )

        assertEquals(
            listOf("鸡蛋" to 0, "番茄" to 1),
            detailOf(recipeId).ingredients.map { it.name to it.sortOrder },
        )
    }

    /** 交给 importBackup 的必须是 zip（和界面导出的格式一致），不是裸 JSON。 */
    private fun zipOf(backup: BackupFile): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(backup, baseDir, it) }.toByteArray()

    private fun imported(name: String) = ImportedImage(
        path = "images/$name.jpg",
        thumbPath = "thumbs/$name.jpg",
        width = 100,
        height = 80,
    )
}
