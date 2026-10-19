package org.miu.cookbook.ui.recipes

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
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
