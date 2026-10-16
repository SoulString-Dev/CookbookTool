package org.miu.cookbook.ui.recipes

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 详情页和常驻顶栏共用的一小块状态。
 *
 * 「Markdown」和「编辑」这两个按钮在顶栏上（那是 CookbookRoot 的地盘），可它们要改的东西
 * 在详情页里（编辑态、Markdown 文本）。把这点状态放进一个对象、两边都拿着它，就不必把
 * 详情页那一整套状态提到外面来。
 *
 * 这个对象由 CookbookRoot 按 recipeId `remember` 住，所以在
 * 「详情页 → 原料编辑器 → 返回」这样的来回里它一直活着 —— 回到详情页时还是编辑态。
 */
@Stable
class RecipeDetailState {

    /** 是否处于编辑态。 */
    var editing by mutableStateOf(false)

    /** Markdown 浮窗要不要显示。 */
    var markdownVisible by mutableStateOf(false)

    /** 这道菜还在不在。不在了就别显示编辑入口。 */
    var available by mutableStateOf(false)

    /** 序列化好的 Markdown 源码，详情页每次数据变化就写进来。 */
    var markdown by mutableStateOf("")

    /** 只认第一次进来的那个入口参数（长按菜单的「编辑食谱」是直接进编辑态）。 */
    internal var initialized = false
}
