package org.miu.cookbook.ui.history

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.CookbookApplication
import org.miu.cookbook.di.AppContainer
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.domain.meal.MealSlot
import org.miu.cookbook.ui.theme.CookbookTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 饮食历史页的界面测试（Robolectric 里跑 Compose，数据库是真的 Room）。
 *
 * 这个页面里的弹窗都没有输入框，所以能这么测；带输入框的弹窗在 Robolectric 下
 * 永远 idle 不下来（见 [org.miu.cookbook.ui.recipes.RecipeListScreenTest] 的说明）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MealHistoryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container: AppContainer
        get() = ApplicationProvider.getApplicationContext<CookbookApplication>().container

    private val today: LocalDate = LocalDate.now()
    private val todayLabel: String = "${today.monthValue}月${today.dayOfMonth}日"

    /** 当天卡片的标题：「2025 周五 3月14日」。 */
    private val todayDialogTitle: String =
        "${today.year} ${WEEKDAYS[today.dayOfWeek.value - 1]} $todayLabel"

    @After
    fun cleanUp() {
        runBlocking { deleteAll() }
    }

    private suspend fun deleteAll() {
        val ids = container.repository.recipeList.first().map { it.recipe.id }
        if (ids.isNotEmpty()) container.repository.deleteRecipes(ids)
    }

    private fun seed(title: String, slot: MealSlot, day: LocalDate = today) = runBlocking {
        val id = container.repository.createRecipe(title)
        container.repository.logMeal(id, day.toEpochDay(), slot)
        id
    }

    private fun launch(onOpen: (Long) -> Unit = {}) {
        val c = container
        compose.setContent {
            CookbookTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppContainer provides c) {
                    MealHistoryScreen(onOpenRecipe = onOpen)
                }
            }
        }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `没有记录时显示空状态`() {
        runBlocking { deleteAll() }
        launch()
        compose.onNodeWithText("还没有饮食记录").assertIsDisplayed()
    }

    @Test
    fun `一天里记了两顿，点开是当天的卡片`() {
        runBlocking { deleteAll() }
        seed("豆浆", MealSlot.BREAKFAST)
        seed("番茄炒蛋", MealSlot.DINNER)
        launch()

        waitForText(todayLabel)
        compose.onNodeWithText("早餐").assertIsDisplayed()
        compose.onNodeWithText("晚餐").assertIsDisplayed()
        compose.onNodeWithText("豆浆").assertIsDisplayed()
        compose.onNodeWithText("番茄炒蛋").assertIsDisplayed()

        compose.onNodeWithText(todayLabel).performClick()

        // 卡片标题是「2025 周五 3月14日」这种；菜名这时在列表和卡片里各出现一次
        waitForText(todayDialogTitle)
        compose.onNodeWithText(todayDialogTitle).assertIsDisplayed()
        compose.onAllNodesWithText("豆浆").assertCountEquals(2)
        compose.onAllNodesWithText("番茄炒蛋").assertCountEquals(2)
    }

    @Test
    fun `长按某一天能删掉这一天`() {
        runBlocking { deleteAll() }
        seed("番茄炒蛋", MealSlot.DINNER)
        launch()

        waitForText(todayLabel)
        compose.onNodeWithText(todayLabel).performTouchInput { longClick() }

        waitForText("删除这一天")
        compose.onNodeWithText("删除这一天").performClick()
        waitForText("删除这一天的记录")
        compose.onNodeWithText("确定删除").performClick()

        waitForText("还没有饮食记录")
        compose.onNodeWithText("还没有饮食记录").assertIsDisplayed()
    }

    private companion object {
        val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }
}
