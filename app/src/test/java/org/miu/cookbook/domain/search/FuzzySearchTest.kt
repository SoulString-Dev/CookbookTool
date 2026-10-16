package org.miu.cookbook.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzySearchTest {

    private val tomatoEgg = FuzzySearch.Document(
        title = "番茄炒蛋",
        categories = listOf("家常菜"),
        ingredients = listOf("番茄", "鸡蛋"),
    )

    private val potatoShreds = FuzzySearch.Document(
        title = "酸辣土豆丝",
        categories = listOf("川菜"),
        ingredients = listOf("土豆", "干辣椒", "醋"),
    )

    private val braisedPork = FuzzySearch.Document(
        title = "红烧肉",
        categories = listOf("家常菜"),
        ingredients = listOf("五花肉", "冰糖"),
    )

    @Test
    fun `逐字子序列能跨字命中`() {
        assertNotNull("「番炒」应该能找到「番茄炒蛋」", FuzzySearch.score(tomatoEgg, "番炒"))
        assertNotNull("「番蛋」应该能找到「番茄炒蛋」", FuzzySearch.score(tomatoEgg, "番蛋"))
        assertNull("「番炒」不该命中「酸辣土豆丝」", FuzzySearch.score(potatoShreds, "番炒"))
    }

    @Test
    fun `拼音首字母能命中`() {
        // initials() 给的是最常见读音：「茄」在 Unihan 里 jiā 排在 qié 前面，
        // 所以显示成 fjcd；但匹配时两个声母都认（见下一个断言）。
        assertEquals("fjcd", FuzzySearch.initials("番茄炒蛋"))
        assertNotNull("fqcd", FuzzySearch.score(tomatoEgg, "fqcd"))
        assertNotNull("多音字「茄」的另一个读音也不能漏", FuzzySearch.score(tomatoEgg, "fjcd"))
        assertNotNull("只打一半也该命中", FuzzySearch.score(tomatoEgg, "fq"))
        assertNull("fqcd 不该命中土豆丝", FuzzySearch.score(potatoShreds, "fqcd"))
    }

    @Test
    fun `数字开头的菜名首字母照样能搜`() {
        val friedRice = FuzzySearch.Document("3分钟蛋炒饭")
        assertEquals("3fzdcf", FuzzySearch.initials("3分钟蛋炒饭"))
        assertNotNull(FuzzySearch.score(friedRice, "3fz"))
    }

    @Test
    fun `前缀比子串靠前，子串比子序列靠前`() {
        val prefix = FuzzySearch.score(tomatoEgg, "番")!!
        val substring = FuzzySearch.score(tomatoEgg, "茄炒")!!
        val subsequence = FuzzySearch.score(tomatoEgg, "番炒")!!
        assertEquals(0, prefix)
        assertTrue("前缀 $prefix < 子串 $substring", prefix < substring)
        assertTrue("子串 $substring < 子序列 $subsequence", substring < subsequence)
    }

    @Test
    fun `菜名命中排在分类和原料之前`() {
        // 「家常」只在红烧肉的分类里
        val categoryHit = FuzzySearch.score(braisedPork, "家常")!!
        // 「蛋」在番茄炒蛋的菜名里，也在它的原料「鸡蛋」里
        val titleHit = FuzzySearch.score(tomatoEgg, "蛋")!!
        val ingredientHit = FuzzySearch.score(
            FuzzySearch.Document(title = "紫菜汤", ingredients = listOf("鸡蛋")),
            "蛋",
        )!!
        assertTrue("菜名 $titleHit < 分类 $categoryHit", titleHit < categoryHit)
        assertTrue("分类 $categoryHit < 原料 $ingredientHit", categoryHit < ingredientHit)
    }

    @Test
    fun `空白和标点不影响匹配`() {
        val spaced = FuzzySearch.Document("番茄 炒蛋")
        assertNotNull(FuzzySearch.score(spaced, "番茄炒蛋"))
        assertNotNull(FuzzySearch.score(spaced, " 番、炒 "))
        assertNotNull(FuzzySearch.score(tomatoEgg, "番茄·炒蛋"))
    }

    @Test
    fun `大小写不敏感`() {
        val pizza = FuzzySearch.Document("Pizza 玛格丽特")
        assertNotNull(FuzzySearch.score(pizza, "pizza"))
        assertNotNull(FuzzySearch.score(pizza, "PIZZA"))
    }

    @Test
    fun `空查询匹配一切`() {
        assertEquals(0, FuzzySearch.score(tomatoEgg, "")!!)
        assertEquals(0, FuzzySearch.score(tomatoEgg, "   ")!!)
    }

    @Test
    fun `不相关的查询返回不匹配`() {
        assertNull(FuzzySearch.score(tomatoEgg, "巧克力"))
        assertNull(FuzzySearch.score(tomatoEgg, "zzz"))
    }

    @Test
    fun `rank 过滤并按相关度排序，同分保持原顺序`() {
        val items = listOf(
            FuzzySearch.Document("青椒土豆丝"), // 「土豆」子串
            FuzzySearch.Document("土豆炖牛肉"), // 「土豆」前缀
            FuzzySearch.Document("番茄炒蛋"),
        )
        val ranked = FuzzySearch.rank(items, "土豆") { it }
        assertEquals(listOf("土豆炖牛肉", "青椒土豆丝"), ranked.map { it.title })
    }

    @Test
    fun `rank 查询为空时原样返回`() {
        val items = listOf(tomatoEgg, potatoShreds)
        assertEquals(items, FuzzySearch.rank(items, "") { it })
        assertEquals(items, FuzzySearch.rank(items, "   ") { it })
    }
}
