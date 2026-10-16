package org.miu.cookbook.domain.recipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 做法/备注编辑器的规则：空格缩进、退格回上级、句号变三级标题、编号怎么算。 */
class ContentOutlineTest {

    @Test
    fun `行首空格缩进，最多两级`() {
        var line = ContentLine("切块")
        line = ContentOutline.indent(line)
        assertEquals(1, line.level)
        line = ContentOutline.indent(line)
        assertEquals(2, line.level)
        // 只有三级有序列表，再敲也停在两级
        line = ContentOutline.indent(line)
        assertEquals(2, line.level)
    }

    @Test
    fun `退格回到上级，到顶就停住`() {
        var line = ContentLine("切块", level = 2)
        line = ContentOutline.outdent(line)
        assertEquals(1, line.level)
        line = ContentOutline.outdent(line)
        assertEquals(0, line.level)
        line = ContentOutline.outdent(line)
        assertEquals(0, line.level)
    }

    @Test
    fun `句号变三级标题，再敲一下变回列表项`() {
        val heading = ContentOutline.toggleHeading(ContentLine("火候", level = 2))
        assertEquals(true, heading.isHeading)
        assertEquals(0, heading.level)

        val back = ContentOutline.toggleHeading(heading)
        assertEquals(false, back.isHeading)
    }

    @Test
    fun `标题上的缩进键不起作用`() {
        val heading = ContentLine("火候", isHeading = true)
        assertEquals(heading, ContentOutline.indent(heading))
        assertEquals(heading, ContentOutline.outdent(heading))
    }

    @Test
    fun `每层自己编号，回到上级继续往下数`() {
        val lines = listOf(
            ContentLine("鸡蛋打散"),
            ContentLine("加盐", level = 1),
            ContentLine("加糖", level = 1),
            ContentLine("再打", level = 2),
            ContentLine("下锅"),
        )
        assertEquals(listOf(1, 1, 2, 1, 2), ContentOutline.numbers(lines))
    }

    @Test
    fun `三级标题不占编号，而且编号重新开始`() {
        val lines = listOf(
            ContentLine("第一步"),
            ContentLine("第二步"),
            ContentLine("火候", isHeading = true),
            ContentLine("中火"),
            ContentLine("小火"),
        )
        assertEquals(listOf(1, 2, 0, 1, 2), ContentOutline.numbers(lines))
    }

    @Test
    fun `存库前把空行都丢掉、层级夹到两级`() {
        val lines = listOf(
            ContentLine(""),
            ContentLine("  打散鸡蛋  "),
            ContentLine(""),
            ContentLine("下锅", level = 9),
            ContentLine(""),
        )
        assertEquals(
            listOf(ContentLine("打散鸡蛋"), ContentLine("下锅", level = 2)),
            ContentOutline.normalize(lines),
        )
    }

    @Test
    fun `数量是数字时默认补 g`() {
        assertEquals("g", Units.defaultFor("2", ""))
        assertEquals("个", Units.defaultFor("2", "个"))
        assertEquals("", Units.defaultFor("适量", ""))
        assertEquals("", Units.defaultFor("", ""))
    }

    @Test
    fun `原料的显示形式`() {
        assertEquals("鸡蛋 2个", IngredientLine("鸡蛋", "2", "个").display)
        assertEquals("盐 适量", IngredientLine("盐", "适量").display)
        assertEquals("番茄", IngredientLine("番茄").display)
    }

    @Test
    fun `切成三级标题时清掉行首的空格`() {
        val indented = ContentLine(text = "  火候", level = 2)
        val heading = ContentOutline.toggleHeading(indented)
        assertEquals("火候", heading.text)
        assertTrue(heading.isHeading)
        assertEquals(0, heading.level)
    }
}
