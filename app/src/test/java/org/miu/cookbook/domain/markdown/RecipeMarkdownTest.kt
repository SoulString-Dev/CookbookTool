package org.miu.cookbook.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.miu.cookbook.domain.recipe.ContentLine
import org.miu.cookbook.domain.recipe.IngredientLine

/**
 * Markdown 序列化器的重点只有一个：**写了什么，读回来还是什么**。
 * 它是导出导入的格式，round-trip 错了就等于备份丢数据。
 */
class RecipeMarkdownTest {

    private val sample = MarkdownRecipe(
        title = "番茄炒蛋",
        categories = listOf("家常菜", "快手菜"),
        ingredients = listOf(
            IngredientLine("鸡蛋", "2", "个"),
            IngredientLine("番茄", "3", "个"),
            IngredientLine("盐", "适量"),
            IngredientLine("葱花"),
        ),
        directions = listOf(
            ContentLine("鸡蛋打散"),
            ContentLine("加一点盐", level = 1),
            ContentLine("番茄切块"),
            ContentLine("火候", isHeading = true),
            ContentLine("中火下锅"),
        ),
        notes = listOf(ContentLine("番茄先去皮更好吃")),
    )

    @Test
    fun `小数开头的行不会被当成序号`() {
        val text = """# 卤蛋

## 原料

1. 鸡蛋 6 个

## 做法

1. 锅里放水
1.5 升就够，别太多
"""

        val parsed = RecipeMarkdown.parse(text)

        // 「1.5 升就够」是正文，不是第 1 项「5 升就够」
        assertEquals(listOf("锅里放水", "1.5 升就够，别太多"), parsed.directions.map { it.text })
    }

    @Test
    fun `顿号和右括号也能当序号，句点后面没空格也行`() {
        val text = """# 拍黄瓜

## 做法

1、拍碎
2.切段
3) 拌料
"""

        val parsed = RecipeMarkdown.parse(text)

        assertEquals(listOf("拍碎", "切段", "拌料"), parsed.directions.map { it.text })
    }

    @Test
    fun `菜名是一级标题，分类是标签行`() {
        val text = RecipeMarkdown.serialize(sample)
        assertTrue(text.startsWith("# 番茄炒蛋"))
        assertTrue(text.contains("标签：家常菜 快手菜"))
    }

    @Test
    fun `原料、做法、备注都是二级标题，内容是带缩进的有序列表`() {
        val text = RecipeMarkdown.serialize(sample)
        assertTrue(text.contains("## 原料"))
        assertTrue(text.contains("## 做法"))
        assertTrue(text.contains("## 备注"))
        assertTrue(text.contains("1. 鸡蛋 2 个"))
        assertTrue(text.contains("  1. 加一点盐"))
    }

    @Test
    fun `三级标题用三个井号`() {
        val text = RecipeMarkdown.serialize(sample)
        assertTrue(text.contains("### 火候"))
    }

    @Test
    fun `写出去再读回来，内容一模一样`() {
        val text = RecipeMarkdown.serialize(sample)
        assertEquals(sample, RecipeMarkdown.parse(text))
    }

    @Test
    fun `二次往返稳定`() {
        val once = RecipeMarkdown.serialize(sample)
        val twice = RecipeMarkdown.serialize(RecipeMarkdown.parse(once))
        assertEquals(once, twice)
    }

    @Test
    fun `没有内容的区块不写标题，界面上也就不用画`() {
        val text = RecipeMarkdown.serialize(MarkdownRecipe(title = "白粥", ingredients = listOf(IngredientLine("米", "一杯"))))
        assertTrue(text.contains("## 原料"))
        assertTrue(!text.contains("## 做法"))
        assertTrue(!text.contains("## 备注"))
    }

    @Test
    fun `空文档不会崩，只是什么都读不到`() {
        assertEquals(MarkdownRecipe(""), RecipeMarkdown.parse(""))
    }

    @Test
    fun `吃了别人写的 Markdown 也能认出来`() {
        val foreign = """
            # 可乐鸡翅

            标签: 家常菜、下饭

            ## 用料

            1. 鸡翅 500 g
            2. 可乐 1 罐
            3. 姜 几片

            ## 步骤

            1. 鸡翅焯水
            2. 煎到两面金黄
            1. 倒可乐
            2. 收汁

            ## 提示

            1. 少放盐
        """.trimIndent()
        val recipe = RecipeMarkdown.parse(foreign)
        assertEquals("可乐鸡翅", recipe.title)
        assertEquals(listOf("家常菜", "下饭"), recipe.categories)
        assertEquals(
            listOf(
                IngredientLine("鸡翅", "500", "g"),
                IngredientLine("可乐", "1", "罐"),
                IngredientLine("姜", "几片"),
            ),
            recipe.ingredients,
        )
        // 有序列表的序号是别人随便写的，缩进层级才是真相
        assertEquals(
            listOf(
                ContentLine("鸡翅焯水"),
                ContentLine("煎到两面金黄"),
                ContentLine("倒可乐"),
                ContentLine("收汁"),
            ),
            recipe.directions,
        )
        assertEquals(listOf(ContentLine("少放盐")), recipe.notes)
    }

    @Test
    fun `没有区块标题时不会把正文认成原料`() {
        val recipe = RecipeMarkdown.parse("# 蛋炒饭\n\n1. 这个列表没有归属\n")
        assertEquals("蛋炒饭", recipe.title)
        assertTrue(recipe.ingredients.isEmpty())
        assertTrue(recipe.directions.isEmpty())
    }
}
