package org.miu.cookbook.domain.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 备份 JSON 的编解码。纯 Kotlin，不用 Robolectric。 */
class BackupCodecTest {

    private val sample = BackupFile.create(
        exportedAt = 1_700_000_000_000,
        categories = listOf(BackupCategory(1, "家常菜", 0), BackupCategory(2, "快手菜", 1)),
        recipes = listOf(
            BackupRecipe(
                id = 7,
                title = "番茄炒蛋",
                createdAt = 100,
                updatedAt = 200,
                categories = listOf(1),
                ingredients = listOf(BackupIngredient("鸡蛋", "2", "个", 0)),
                content = listOf(
                    BackupContentBlock("DIRECTIONS", "LIST_ITEM", 0, "打蛋", 0),
                    BackupContentBlock("DIRECTIONS", "HEADING", 0, "火候", 1),
                    BackupContentBlock("NOTE", "LIST_ITEM", 1, "少放盐", 0),
                ),
                images = listOf(BackupImage("images/a.jpg", "thumbs/a.jpg", 100, 80, 0, true)),
            ),
        ),
        mealLogs = listOf(BackupMealLog(recipeId = 7, epochDay = 20_000, slot = "DINNER")),
    )

    @Test
    fun `写出来再读回去是同一份数据`() {
        assertEquals(sample, BackupCodec.decode(BackupCodec.encode(sample)))
    }

    @Test
    fun `文件名和格式号写进文件里，方便人工辨认`() {
        val text = BackupCodec.encode(sample)
        assertTrue(text.contains("\"format\": \"${BackupFile.FORMAT}\""))
        assertTrue(text.contains("\"version\": ${BackupFile.VERSION}"))
    }

    @Test
    fun `不是本应用的备份时给出人话提示`() {
        try {
            BackupCodec.decode("""{"hello":"world"}""")
            fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("这个文件不是菜谱本的备份", e.message)
        }
    }

    @Test
    fun `不是 JSON 时也给出同样的提示`() {
        try {
            BackupCodec.decode("这不是 JSON")
            fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("这个文件不是菜谱本的备份", e.message)
        }
    }

    @Test
    fun `备份来自更新的版本时拒绝导入`() {
        val text = BackupCodec.encode(sample).replace("\"version\": 1", "\"version\": 99")
        try {
            BackupCodec.decode(text)
            fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("备份文件来自更新的版本，请先升级应用", e.message)
        }
    }

    @Test
    fun `多出来的字段不会让解析失败`() {
        val text = BackupCodec.encode(sample).replace(
            "\"exportedAt\": 1700000000000",
            "\"exportedAt\": 1700000000000, \"somethingNew\": true",
        )
        assertEquals(sample, BackupCodec.decode(text))
    }
}
