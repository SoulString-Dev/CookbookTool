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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = CookbookRepository(db.recipeDao(), db.categoryDao(), db.mealLogDao())
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
}
