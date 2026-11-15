package com.autolog.app.report

import java.time.LocalDate

/** 월말 안내. 달이 끝나기 전에 분류하지 않은 운행을 정리하도록 알린다. */
object MonthEnd {

    /** 달이 이만큼 남았을 때부터 알린다(일). */
    const val REMIND_DAYS = 3

    /** 그 달의 남은 날 수. 말일이면 0. */
    fun daysLeft(date: LocalDate): Int = date.lengthOfMonth() - date.dayOfMonth

    /** 알릴 때인가. 이번 달에 분류하지 않은 운행이 있고 달이 [REMIND_DAYS]일 이하로 남았을 때. */
    fun shouldRemind(date: LocalDate, unclassifiedThisMonth: Int): Boolean =
        unclassifiedThisMonth > 0 && daysLeft(date) <= REMIND_DAYS

    /** 화면에 보여 줄 문장. "11월이 3일 남았습니다", 말일에는 "오늘이 11월 마지막 날입니다". */
    fun message(date: LocalDate): String {
        val left = daysLeft(date)
        return if (left == 0) "오늘이 ${date.monthValue}월 마지막 날입니다" else "${date.monthValue}월이 ${left}일 남았습니다"
    }
}
