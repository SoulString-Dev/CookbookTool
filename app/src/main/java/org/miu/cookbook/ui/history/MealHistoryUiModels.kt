package org.miu.cookbook.ui.history

import java.io.File
import java.time.LocalDate
import org.miu.cookbook.data.db.MealLogRow
import org.miu.cookbook.domain.meal.MealSlot

/** 历史卡片里的一道菜。 */
data class MealHistoryEntry(
    val logId: Long,
    val recipeId: Long,
    val title: String,
    /** 绝对路径，给 Coil 用；没有封面就是 null。 */
    val coverPath: String?,
)

/** 一天的记录：早餐/午餐/晚餐，没记的那一餐在 map 里根本没有键。 */
data class MealHistoryDay(
    val epochDay: Long,
    val date: LocalDate,
    val meals: Map<MealSlot, List<MealHistoryEntry>>,
) {
    /** 「3月5日」。 */
    val dayLabel: String = "${date.monthValue}月${date.dayOfMonth}日"

    /** 「2024 周二」。 */
    val yearWeekLabel: String =
        "${date.year} ${WEEKDAY_LABELS[date.dayOfWeek.value - 1]}"

    /** 这一天一共记了几条，用于删除前的提示。 */
    val logCount: Int = meals.values.sumOf { it.size }

    val allLogIds: List<Long> = meals.values.flatten().map { it.logId }

    /** 按早/午/晚列出这一天的餐（没记录的那一餐不出现）。 */
    val orderedMeals: List<Pair<MealSlot, List<MealHistoryEntry>>>
        get() = MealSlot.entries.mapNotNull { slot ->
            meals[slot]?.takeIf { it.isNotEmpty() }?.let { slot to it }
        }
}

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 把数据库的一次查询结果按天分组。
 *
 * 同一天里按早/午/晚排（这里必须按枚举顺序排，不能按 SQL —— 枚举在库里存的是名字，
 * 按字母排出来是 早/晚/午）。
 */
fun List<MealLogRow>.toHistoryDays(baseDir: File): List<MealHistoryDay> =
    groupBy { it.epochDay }
        .toSortedMap()
        .map { (epochDay, rows) ->
            val meals = rows
                .sortedWith(compareBy({ it.slot.ordinal }, { it.createdAt }, { it.logId }))
                .groupBy { it.slot }
                .mapValues { (_, slotRows) ->
                    slotRows.map { row ->
                        MealHistoryEntry(
                            logId = row.logId,
                            recipeId = row.recipeId,
                            title = row.title,
                            coverPath = row.coverPath?.let { File(baseDir, it).absolutePath },
                        )
                    }
                }
            MealHistoryDay(
                epochDay = epochDay,
                date = LocalDate.ofEpochDay(epochDay),
                meals = meals,
            )
        }
