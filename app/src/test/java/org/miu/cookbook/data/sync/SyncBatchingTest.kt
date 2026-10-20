package org.miu.cookbook.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.miu.cookbook.sync.model.PushDelete
import org.miu.cookbook.sync.model.PushUpsert
import org.miu.cookbook.sync.model.SyncRecipePayload

/**
 * 推送切批的规矩：一批的编码体积不能超过服务端的请求体上限。
 *
 * 纯 JVM 测试（不需要 Room、不需要 Android）。
 */
class SyncBatchingTest {

    private fun upsert(title: String, notes: String = ""): PushUpsert = PushUpsert(
        uuid = "uuid-$title",
        updatedAt = 1000,
        payload = SyncRecipePayload(
            title = title,
            createdAt = 1000,
            updatedAt = 1000,
            ingredients = if (notes.isEmpty()) {
                emptyList()
            } else {
                listOf(org.miu.cookbook.sync.model.SyncIngredient(name = notes, sortOrder = 0))
            },
        ),
    )

    @Test
    fun `小批量不会被切开`() {
        val parts = splitPushBySize(listOf(upsert("红烧肉"), upsert("可乐鸡翅")), emptyList())
        assertEquals(1, parts.size)
        assertEquals(2, parts.single().first.size)
    }

    @Test
    fun `超预算的批量会被切成几段，每段都不超预算`() {
        // 每条约 1MB 的正文，五条一定超过 3MB 的预算
        val big = "x".repeat(1024 * 1024)
        val upserts = (1..5).map { upsert("菜$it", notes = big) }

        val parts = splitPushBySize(upserts, emptyList())

        assertTrue("应该切开了，实际 ${parts.size} 段", parts.size > 1)
        parts.forEach { part ->
            val size = part.first.sumOf { encodedSize(it) }
            assertTrue("段体积 $size 超过预算 $MAX_PUSH_BYTES", size <= MAX_PUSH_BYTES)
        }
        // 一条都不能丢
        assertEquals(5, parts.sumOf { it.first.size })
    }

    @Test
    fun `删除请求挂在第一段上`() {
        val big = "x".repeat(2 * 1024 * 1024)
        val parts = splitPushBySize(
            listOf(upsert("甲", big), upsert("乙", big)),
            listOf(PushDelete("gone", 500)),
        )

        assertEquals(2, parts.size)
        assertEquals(listOf("gone"), parts.first().second.map { it.uuid })
        assertTrue("只有第一段带删除", parts.last().second.isEmpty())
    }

    @Test
    fun `没有待推送的菜时，删除自己也能成一段`() {
        val parts = splitPushBySize(emptyList(), listOf(PushDelete("gone", 500)))
        assertEquals(1, parts.size)
        assertTrue(parts.single().first.isEmpty())
        assertEquals(1, parts.single().second.size)
    }
}
