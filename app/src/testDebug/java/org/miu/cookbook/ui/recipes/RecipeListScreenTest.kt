package org.miu.cookbook.ui.recipes

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.CookbookApplication
import org.miu.cookbook.di.AppContainer
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.theme.CookbookTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 食谱列表页的界面测试（Robolectric 里跑 Compose）。
 * 数据库是真的 Room，所以每个用例结束都把食谱删干净，免得串味。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RecipeListScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<CookbookApplication>().container

    @After
    fun cleanUp() {
        runBlocking { deleteAll() }
    }

    private suspend fun deleteAll() {
        val ids = container.repository.recipeList.first().map { it.recipe.id }
        if (ids.isNotEmpty()) container.repository.deleteRecipes(ids)
    }

    private fun seed(vararg titles: String): List<Long> = runBlocking {
        deleteAll()
        titles.map { container.repository.createRecipe(it) }
    }

    private fun launch(
        newRecipeRequested: Boolean = false,
        selectionRequested: Boolean = false,
        onOpen: (Long) -> Unit = {},
        railEnabled: Boolean = false,
        railCategories: List<org.miu.cookbook.ui.sidebar.RailCategory> = emptyList(),
        railSelectedUuid: String = "",
    ) {
        val c = container
        compose.setContent {
            CookbookTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppContainer provides c) {
                    RecipeListScreen(
                        onOpenRecipe = onOpen,
                        onEditRecipe = {},
                        newRecipeRequested = newRecipeRequested,
                        onNewRecipeHandled = {},
                        selectionRequested = selectionRequested,
                        onSelectionRequestedHandled = {},
                        railEnabled = railEnabled,
                        railCategories = railCategories,
                        railSelectedUuid = railSelectedUuid,
                    )
                }
            }
        }
    }

    /**
     * 注意：这里**故意不测**「弹窗里带输入框」的流程（新建食谱、编辑分类）。
     *
     * Robolectric 下 `AlertDialog` 里只要放 `OutlinedTextField`，Compose 就永远 idle 不下来
     * （`AppNotIdleException: Compose did not get idle after 219146 attempts in 60 SECONDS`，
     * 有时直接 OOM 在 `android.text.StaticLayout.<init>`）；换成只有 `Text` 的弹窗就正常；
     * 用 `mainClock.autoAdvance = false` 暂停时钟也救不了（`fetchSemanticsNodes` 内部照样 waitForIdle）。
     * 这是 Robolectric 的环境问题，不是应用的问题，所以那部分逻辑改去
     * [org.miu.cookbook.data.CookbookRepositoryTest] 测仓库，界面留给真机验收。
     */
    /** 等某个文本出现，最多 5 秒。 */
    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `拼音首字母能搜到，不相关的被筛掉`() {
        seed("番茄炒蛋", "土豆丝")
        launch()
        waitForText("番茄炒蛋")

        compose.onNode(hasSetTextAction()).performTextInput("fqcd")

        waitForText("找到 1 道")
        compose.onNodeWithText("番茄炒蛋").assertIsDisplayed()
        compose.onNodeWithText("土豆丝").assertDoesNotExist()
    }

    /** 建两道菜，把 [withCategory] 加上「家常菜」，返回分类的 uuid。 */
    private fun seedWithCategory(withCategory: String): String = runBlocking {
        val categoryId = container.repository.ensureCategory("家常菜")
        val uuid = container.repository.categories.first().first { it.id == categoryId }.uuid
        val target = container.repository.recipeList.first().first { it.recipe.title == withCategory }
        container.repository.setCategories(target.recipe.id, setOf(categoryId))
        uuid
    }

    @Test
    fun `不搜索时，选中的分类只看这个分类的菜`() {
        seed("红烧肉", "土豆丝")
        val hot = seedWithCategory("红烧肉")
        launch(
            railEnabled = true,
            railCategories = listOf(org.miu.cookbook.ui.sidebar.RailCategory(hot, "家常菜")),
            railSelectedUuid = hot,
        )
        waitForText("红烧肉")
        compose.onNodeWithText("土豆丝").assertDoesNotExist()
    }

    @Test
    fun `搜索时分类不过滤，只是把命中的排前面`() {
        seed("红烧肉", "土豆烧肉")
        val hot = seedWithCategory("红烧肉")
        launch(
            railEnabled = true,
            railCategories = listOf(org.miu.cookbook.ui.sidebar.RailCategory(hot, "家常菜")),
            railSelectedUuid = hot,
        )
        waitForText("红烧肉")

        compose.onNode(hasSetTextAction()).performTextInput("肉")
        waitForText("找到 2 道")
        val hit = compose.onNodeWithText("红烧肉").getBoundsInRoot().top
        val rest = compose.onNodeWithText("土豆烧肉").getBoundsInRoot().top
        assertTrue("带分类的应该排在前面", hit < rest)
    }

    @Test
    fun `顶栏进了多选之后可以批量删除`() {
        seed("番茄炒蛋", "土豆丝")
        // 多选入口已经搬到顶栏「⋮」菜单里（那个按钮在 CookbookRoot 里），
        // 这里直接把「已经请求进多选」当输入喂进来，测的还是选择与删除本身。
        launch(selectionRequested = true)
        waitForText("番茄炒蛋")

        compose.onNodeWithText("番茄炒蛋").performClick()
        compose.onNodeWithText("土豆丝").performClick()
        compose.onNodeWithText("已选 2 项").assertIsDisplayed()

        compose.onNodeWithText("删除").performClick()
        waitForText("删除食谱")
        compose.onNodeWithText("确定删除").performClick()

        waitForText("还没有食谱")
        compose.onNodeWithText("还没有食谱").assertIsDisplayed()
    }

}
