package org.miu.cookbook.domain.meal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 一餐。暂时只考虑早/午/晚，不做甜点或加餐。 */
enum class MealSlot {
    BREAKFAST,
    LUNCH,
    DINNER,
    ;

    companion object {
        /** 已存储的序号（1/2/3）；Room 存的是 ordinal，这里只用于展示层排序与调试。 */
        fun ofOrdinal(value: Int): MealSlot = entries[value.coerceIn(entries.indices)]
    }
}

/** 一次饮食发生的“时间点”：精确到某一天的某一餐。 */
data class MealMoment(val date: LocalDate, val slot: MealSlot)

/**
 * 餐段判定规则（用户确认版）：
 *  - 02:00–10:29 早餐
 *  - 10:30–15:29 午餐
 *  - 其余（15:30–23:59 与 00:00–01:59）晚餐
 *
 * 00:00–01:59 属于“深夜的那顿晚饭”，因此归到**前一天**的晚餐：
 * 凌晨 1 点吃的东西不该记成新一天的开始。
 */
object MealTime {

    private const val BREAKFAST_START = 2 * 3600
    private const val LUNCH_START = 10 * 3600 + 30 * 60
    private const val DINNER_START = 15 * 3600 + 30 * 60

    /** 纯时段判定，不涉及跨日。 */
    fun slotOf(time: LocalTime): MealSlot {
        val s = time.toSecondOfDay()
        return when {
            s >= BREAKFAST_START && s < LUNCH_START -> MealSlot.BREAKFAST
            s >= LUNCH_START && s < DINNER_START -> MealSlot.LUNCH
            else -> MealSlot.DINNER
        }
    }

    /** 时刻 → (归属日期, 餐段)。 */
    fun resolve(dateTime: LocalDateTime): MealMoment {
        val s = dateTime.toLocalTime().toSecondOfDay()
        return if (s < BREAKFAST_START) {
            // 凌晨 0:00–1:59：算作前一天的晚餐
            MealMoment(dateTime.toLocalDate().minusDays(1), MealSlot.DINNER)
        } else {
            MealMoment(dateTime.toLocalDate(), slotOf(dateTime.toLocalTime()))
        }
    }
}
