package org.miu.cookbook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.data.db.AppDatabase
import org.miu.cookbook.data.db.ContentSection
import org.miu.cookbook.data.db.MealLogEntity
import org.miu.cookbook.data.db.MealLogRow
import org.miu.cookbook.data.image.ImportedImage
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.IngredientLine
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
        repository = CookbookRepository(
            db.recipeDao(),
            db.categoryDao(),
            db.mealLogDao(),
            db.imageDao(),
            baseDir,
        )
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

        val first = runBlocking { repository.addImage(recipe, imported("a")) }
        val second = runBlocking { repository.addImage(recipe, imported("b")) }

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
    fun `改餐段时目标那餐已经有记录就互换`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        val b = runBlocking { repository.createRecipe("面条") }
        runBlocking {
            repository.logMeal(a, day(0), MealSlot.BREAKFAST)
            repository.logMeal(b, day(0), MealSlot.DINNER)
        }

        runBlocking { repository.moveMealSlot(day(0), MealSlot.BREAKFAST, MealSlot.DINNER) }

        val byRecipe = history().associate { it.recipeId to it.slot }
        assertEquals(MealSlot.DINNER, byRecipe[a])
        assertEquals(MealSlot.BREAKFAST, byRecipe[b])
    }

    @Test
    fun `改餐段到空着的那一餐时只是搬过去`() {
        val a = runBlocking { repository.createRecipe("豆浆") }
        runBlocking { repository.logMeal(a, day(0), MealSlot.BREAKFAST) }

        runBlocking { repository.moveMealSlot(day(0), MealSlot.BREAKFAST, MealSlot.LUNCH) }

        assertEquals(MealSlot.LUNCH, history().single().slot)
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
    fun `某道菜自己的历史按时间倒序`() {
        val a = runBlocking { repository.createRecipe("番茄炒蛋") }
        val b = runBlocking { repository.createRecipe("土豆丝") }
        runBlocking {
            repository.logMeal(a, day(3), MealSlot.DINNER)
            repository.logMeal(a, day(0), MealSlot.LUNCH)
            repository.logMeal(b, day(1), MealSlot.LUNCH)
        }

        val rows = runBlocking { repository.mealHistoryOf(a).first() }
        assertEquals(listOf(day(0), day(3)), rows.map { it.epochDay })
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

    private fun imported(name: String) = ImportedImage(
        path = "images/$name.jpg",
        thumbPath = "thumbs/$name.jpg",
        width = 100,
        height = 80,
    )
}
