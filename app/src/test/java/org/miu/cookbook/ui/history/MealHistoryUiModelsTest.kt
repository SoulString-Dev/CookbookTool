package org.miu.cookbook.ui.history

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.miu.cookbook.data.db.MealLogRow
import org.miu.cookbook.domain.meal.MealSlot

/**
 * 历史列表的分组与排序。纯函数，不用 Robolectric。
 *
 * 特别要盯住「一天里按早/午/晚排」：枚举在库里存的是名字（TEXT），
 * 哪天不小心把它交给 SQL 排序，就会变成 早/晚/午。
 */
class MealHistoryUiModelsTest {

    private val baseDir = File("/tmp/cookbook")

    private val today: Long = LocalDate.of(2025, 3, 14).toEpochDay()

    private fun row(
        logId: Long,
        epochDay: Long = today,
        slot: MealSlot = MealSlot.DINNER,
        recipeId: Long = logId,
        title: String = "番茄炒蛋",
        coverPath: String? = null,
        createdAt: Long = 0,
    ) = MealLogRow(
        logId = logId,
        recipeId = recipeId,
        epochDay = epochDay,
        slot = slot,
        createdAt = createdAt,
        title = title,
        coverPath = coverPath,
    )

    @Test
    fun `一天里按早午晚排`() {
        val days = listOf(
            row(1, slot = MealSlot.DINNER),
            row(2, slot = MealSlot.BREAKFAST),
            row(3, slot = MealSlot.LUNCH),
        ).toHistoryDays(baseDir)

        assertEquals(1, days.size)
        assertEquals(
            listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.DINNER),
            days.single().orderedMeals.map { it.first },
        )
    }

    @Test
    fun `没记录的那一餐不占位`() {
        val days = listOf(row(1, slot = MealSlot.LUNCH)).toHistoryDays(baseDir)
        val meals = days.single().orderedMeals

        assertEquals(1, meals.size)
        assertEquals(MealSlot.LUNCH, meals.single().first)
        assertEquals(listOf("番茄炒蛋"), meals.single().second.map { it.title })
    }

    @Test
    fun `天数按日期正序，同一天聚在一起`() {
        val days = listOf(
            row(1, epochDay = today + 2),
            row(2, epochDay = today),
            row(3, epochDay = today + 2, slot = MealSlot.LUNCH),
        ).toHistoryDays(baseDir)

        assertEquals(listOf(today, today + 2), days.map { it.epochDay })
        assertEquals(2, days.last().logCount)
        // allLogIds 是按「早/午/晚」顺序排出来的，所以是 午(3) 在前、晚(1) 在后
        assertEquals(listOf(3L, 1L), days.last().allLogIds)
    }

    @Test
    fun `封面相对路径会拼成绝对路径`() {
        val days = listOf(row(1, coverPath = "thumbs/a.jpg")).toHistoryDays(baseDir)
        val entry = days.single().meals.values.single().single()
        assertEquals(File(baseDir, "thumbs/a.jpg").absolutePath, entry.coverPath)

        val noCover = listOf(row(1)).toHistoryDays(baseDir)
        assertEquals(null, noCover.single().meals.values.single().single().coverPath)
    }

    @Test
    fun `日期文字是年月日加星期`() {
        val day = listOf(row(1)).toHistoryDays(baseDir).single()
        assertEquals("3月14日", day.dayLabel)
        assertEquals("2025 周五", day.yearWeekLabel)
    }

    @Test
    fun `同一餐里的多道菜按记录顺序排`() {
        val days = listOf(
            row(1, slot = MealSlot.LUNCH, title = "后来的", createdAt = 200),
            row(2, slot = MealSlot.LUNCH, title = "先记的", createdAt = 100),
        ).toHistoryDays(baseDir)

        assertEquals(
            listOf("先记的", "后来的"),
            days.single().meals.getValue(MealSlot.LUNCH).map { it.title },
        )
    }
}
