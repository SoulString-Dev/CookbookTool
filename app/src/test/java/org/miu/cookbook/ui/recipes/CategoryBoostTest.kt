package org.miu.cookbook.ui.recipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.miu.cookbook.domain.search.FuzzySearch
import org.miu.cookbook.ui.sidebar.railLabel

/**
 * 侧栏选中分类后的两种口径（用户最终定的）：
 * - **不搜索**时只看这个分类（[onlyInCategory]）；
 * - **搜索**时不过滤，只是把带这个分类的提前（[boostCategory]）。
 *
 * 最初实现成「一律只置顶不过滤」，被用户纠正过一次，别再改回去。
 */
class CategoryBoostTest {

    private fun item(id: Long, title: String, vararg uuids: String) = RecipeListItem(
        id = id,
        title = title,
        categories = emptyList(),
        categoryUuids = uuids.toList(),
        ingredients = emptyList(),
        coverPath = null,
        searchDocument = FuzzySearch.Document(title = title),
    )

    @Test
    fun `没选分类时顺序原样不动`() {
        val items = listOf(item(1, "红烧肉"), item(2, "土豆丝"))
        assertEquals(listOf(1L, 2L), items.boostCategory("").map { it.id })
    }

    @Test
    fun `选中分类后带这个分类的排前面，其余的仍然都在`() {
        val items = listOf(
            item(1, "红烧肉", "家常菜"),
            item(2, "土豆丝"),
            item(3, "可乐鸡翅", "家常菜"),
            item(4, "凉拌黄瓜"),
        )
        val boosted = items.boostCategory("家常菜")
        assertEquals(listOf(1L, 3L, 2L, 4L), boosted.map { it.id })
        assertEquals(items.size, boosted.size)
    }

    @Test
    fun `带多个分类的菜在自己那个分类下只会被提前一次`() {
        val items = listOf(
            item(1, "红烧肉", "家常菜", "硬菜"),
            item(2, "炒青菜", "家常菜"),
        )
        // 「硬菜」置顶：只有第 1 道命中，命中段里仍是原来的先后
        assertEquals(listOf(1L, 2L), items.boostCategory("硬菜").map { it.id })
    }

    @Test
    fun `分类里一道菜都没有时，列表还是全部菜`() {
        val items = listOf(item(1, "红烧肉", "家常菜"), item(2, "土豆丝", "家常菜"))
        assertEquals(listOf(1L, 2L), items.boostCategory("甜点").map { it.id })
    }

    @Test
    fun `不搜索时只看这个分类，选全部就原样返回`() {
        val items = listOf(
            item(1, "红烧肉", "家常菜"),
            item(2, "土豆丝"),
            item(3, "可乐鸡翅", "家常菜"),
        )
        assertEquals(listOf(1L, 3L), items.onlyInCategory("家常菜").map { it.id })
        assertEquals(items, items.onlyInCategory(""))
    }

    @Test
    fun `分类里一道菜都没有时，列表是空的`() {
        val items = listOf(item(1, "红烧肉", "家常菜"))
        assertTrue(items.onlyInCategory("甜点").isEmpty())
    }

    @Test
    fun `侧栏标签长于六个字时留五个字加省略号`() {
        assertEquals("家常菜", railLabel("家常菜"))
        // 六个字正好放得下，不截
        assertEquals("一日三餐四季", railLabel("一日三餐四季"))
        // 七个字起才截成五个字 + 一个省略号
        assertEquals("一日三餐四…", railLabel("一日三餐四季家"))
        assertTrue(railLabel("一日三餐四季家常菜").endsWith("…"))
        assertEquals("一日三餐四…", railLabel("一日三餐四季家常菜"))
    }
}
