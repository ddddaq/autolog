package com.autolog.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MonthEndTest {

    @Test
    fun `달의 남은 날 수를 센다`() {
        assertEquals(19, MonthEnd.daysLeft(LocalDate.of(2026, 11, 11)))
        assertEquals(3, MonthEnd.daysLeft(LocalDate.of(2026, 11, 27)))
        assertEquals(0, MonthEnd.daysLeft(LocalDate.of(2026, 11, 30)))
        assertEquals(0, MonthEnd.daysLeft(LocalDate.of(2027, 2, 28)))
    }

    @Test
    fun `달이 사흘 이하로 남고 미분류가 있을 때만 알린다`() {
        assertFalse(MonthEnd.shouldRemind(LocalDate.of(2026, 11, 26), 2))
        assertTrue(MonthEnd.shouldRemind(LocalDate.of(2026, 11, 27), 2))
        assertTrue(MonthEnd.shouldRemind(LocalDate.of(2026, 11, 30), 1))
        assertFalse(MonthEnd.shouldRemind(LocalDate.of(2026, 11, 30), 0))
    }

    @Test
    fun `남은 날에 맞춰 문장을 만든다`() {
        assertEquals("11월이 3일 남았습니다", MonthEnd.message(LocalDate.of(2026, 11, 27)))
        assertEquals("오늘이 11월 마지막 날입니다", MonthEnd.message(LocalDate.of(2026, 11, 30)))
    }
}
