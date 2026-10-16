package org.miu.cookbook.domain.meal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 餐段规则是用户逐字确认过的：
 * 02:00–10:29 早餐，10:30–15:29 午餐，其余晚餐。
 */
class MealTimeTest {

    private val day = LocalDate.of(2025, 3, 14)

    private fun slotAt(hour: Int, minute: Int): MealSlot =
        MealTime.slotOf(LocalTime.of(hour, minute))

    @Test
    fun `凌晨两点到十点半之前是早餐`() {
        assertEquals(MealSlot.BREAKFAST, slotAt(2, 0))
        assertEquals(MealSlot.BREAKFAST, slotAt(7, 30))
        assertEquals(MealSlot.BREAKFAST, slotAt(10, 29))
    }

    @Test
    fun `十点半到三点半之前是午餐`() {
        assertEquals(MealSlot.LUNCH, slotAt(10, 30))
        assertEquals(MealSlot.LUNCH, slotAt(12, 0))
        assertEquals(MealSlot.LUNCH, slotAt(15, 29))
    }

    @Test
    fun `三点半之后到午夜是晚餐`() {
        assertEquals(MealSlot.DINNER, slotAt(15, 30))
        assertEquals(MealSlot.DINNER, slotAt(19, 0))
        assertEquals(MealSlot.DINNER, slotAt(23, 59))
    }

    @Test
    fun `凌晨零点到两点算作前一天的晚餐`() {
        assertEquals(MealMoment(day.minusDays(1), MealSlot.DINNER), MealTime.resolve(LocalDateTime.of(day, LocalTime.of(0, 0))))
        assertEquals(MealMoment(day.minusDays(1), MealSlot.DINNER), MealTime.resolve(LocalDateTime.of(day, LocalTime.of(1, 59))))
    }

    @Test
    fun `边界时刻归属当天`() {
        assertEquals(MealMoment(day, MealSlot.BREAKFAST), MealTime.resolve(LocalDateTime.of(day, LocalTime.of(2, 0))))
        assertEquals(MealMoment(day, MealSlot.LUNCH), MealTime.resolve(LocalDateTime.of(day, LocalTime.of(10, 30))))
        assertEquals(MealMoment(day, MealSlot.DINNER), MealTime.resolve(LocalDateTime.of(day, LocalTime.of(15, 30))))
    }

    @Test
    fun `手动挑餐段时只有凌晨的晚饭算前一天`() {
        val oneAm = LocalDateTime.of(day, LocalTime.of(1, 0))
        assertEquals(day, MealTime.dateFor(MealSlot.BREAKFAST, oneAm))
        assertEquals(day, MealTime.dateFor(MealSlot.LUNCH, oneAm))
        assertEquals(day.minusDays(1), MealTime.dateFor(MealSlot.DINNER, oneAm))

        val evening = LocalDateTime.of(day, LocalTime.of(19, 0))
        assertEquals(day, MealTime.dateFor(MealSlot.DINNER, evening))
        assertEquals(day, MealTime.dateFor(MealSlot.BREAKFAST, evening))
    }
}
