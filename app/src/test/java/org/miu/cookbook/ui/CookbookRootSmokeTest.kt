package org.miu.cookbook.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.miu.cookbook.CookbookApplication
import org.miu.cookbook.di.LocalAppContainer
import org.miu.cookbook.ui.theme.CookbookTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 没有真机也没有模拟器，所以这层烟雾测试是唯一能自动化验证
 * “界面真的能起来、侧边栏真的能切”的手段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CookbookRootSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun launchApp() {
        val container = ApplicationProvider.getApplicationContext<CookbookApplication>().container
        composeRule.setContent {
            CookbookTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    CookbookRoot()
                }
            }
        }
    }

    @Test
    fun `默认落在食谱页并显示空状态`() {
        launchApp()
        composeRule.onNodeWithText("还没有食谱").assertIsDisplayed()
        // M4 起，非编辑态的这个加号是「记录饮食」（在记录模式里再按才是新增食谱）
        composeRule.onNodeWithContentDescription("记录饮食").assertIsDisplayed()
    }

    @Test
    fun `侧边栏可以切到饮食历史`() {
        launchApp()
        composeRule.onNodeWithContentDescription("打开侧边栏").performClick()
        composeRule.waitForIdle()

        // 这里用语义动作而不是触摸点击：Robolectric 下 ModalNavigationDrawer
        // 的蒙版节点会吃掉落在抽屉条目上的合成触摸事件（真机不会），
        // 而本测试要验的是“点了条目会不会真的切页面”，不是触摸分发本身。
        composeRule.onAllNodesWithText("饮食历史").onFirst()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("还没有饮食记录").assertIsDisplayed()
    }
}
