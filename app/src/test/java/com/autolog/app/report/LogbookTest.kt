package com.autolog.app.report

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class LogbookTest {

    private val utc: ZoneId = ZoneOffset.UTC

    private fun ms(month: Int, day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, month, day, hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun trip(id: Long, month: Int, day: Int, hour: Int, startOdo: Float, km: Float, purpose: Purpose, name: String = "김대리", note: String = "") = Trip(
        id = id,
        startAtMs = ms(month, day, hour),
        endAtMs = ms(month, day, hour, 40),
        startOdoKm = startOdo,
        endOdoKm = startOdo + km,
        distanceKm = km,
        purpose = purpose,
        note = note,
        driverName = name,
        driverDept = "영업1팀",
    )

    private val trips = listOf(
        trip(4, 11, 10, 18, 150f, 18f, Purpose.UNCLASSIFIED),
        trip(1, 11, 9, 8, 100f, 18f, Purpose.COMMUTE),
        trip(2, 11, 9, 13, 118f, 32f, Purpose.BUSINESS_VISIT, note = "계약서 미팅"),
        trip(3, 11, 10, 11, 150f - 0f, 0f + 0f, Purpose.PRIVATE).copy(startOdoKm = 138f, endOdoKm = 150f, distanceKm = 12f, startAtMs = ms(11, 10, 11)),
        trip(9, 10, 31, 9, 60f, 40f, Purpose.BUSINESS_GENERAL),
    )

    @Test
    fun `해당 월 운행만 시간 순으로 고른다`() {
        val november = Logbook.monthTrips(trips, 2026, 11, utc)
        assertEquals(listOf(1L, 2L, 3L, 4L), november.map { it.id })
    }

    @Test
    fun `출퇴근과 일반 업무는 각자 칸에, 사적과 미분류는 업무 칸을 비운다`() {
        val rows = Logbook.rows(Logbook.monthTrips(trips, 2026, 11, utc), utc)

        assertEquals("2026-11-09(월)", rows[0].date)
        assertEquals("영업1팀 김대리", rows[0].user)
        assertEquals(18f, rows[0].commuteKm!!, 0f)
        assertNull(rows[0].businessKm)

        assertNull(rows[1].commuteKm)
        assertEquals(32f, rows[1].businessKm!!, 0f)
        assertEquals("거래처 방문 · 계약서 미팅", rows[1].remark)

        assertNull(rows[2].commuteKm)
        assertNull(rows[2].businessKm)
        assertEquals("사적 사용", rows[2].remark)

        assertNull(rows[3].businessKm)
        assertEquals("미분류", rows[3].remark)
    }

    @Test
    fun `업무사용비율은 업무용 사용거리를 총주행거리로 나눈 값이다`() {
        val totals = Logbook.totals(Logbook.monthTrips(trips, 2026, 11, utc))

        assertEquals(80f, totals.totalKm, 0f)          // 18 + 32 + 12 + 18
        assertEquals(18f, totals.commuteKm, 0f)
        assertEquals(32f, totals.generalBusinessKm, 0f)
        assertEquals(50f, totals.businessKm, 0f)
        assertEquals(0.625f, totals.businessRatio!!, 0.0001f)
        assertEquals(1, totals.unclassifiedCount)
    }

    @Test
    fun `운행이 없으면 비율을 내지 않는다`() {
        assertNull(Logbook.totals(emptyList()).businessRatio)
    }

    @Test
    fun `CSV는 서식 항목 순서대로 나온다`() {
        val csv = Logbook.csv("아이오닉 5", "123가 4567", 2026, 11, Logbook.monthTrips(trips, 2026, 11, utc), utc)
        val lines = csv.trim().split("\r\n")

        assertEquals("업무용승용차 운행기록부", lines[0])
        assertEquals("차종,아이오닉 5", lines[1])
        assertEquals("자동차등록번호,123가 4567", lines[2])
        assertEquals("기간,2026년 11월", lines[3])
        assertEquals("사용일자(요일),사용자(부서·성명),주행 전 계기판의 거리(km),주행 후 계기판의 거리(km),주행거리(km),출·퇴근용(km),일반 업무용(km),비고", lines[5])
        assertEquals("2026-11-09(월),영업1팀 김대리,100,118,18,18,,출퇴근", lines[6])
        assertEquals("2026-11-09(월),영업1팀 김대리,118,150,32,,32,거래처 방문 · 계약서 미팅", lines[7])
        assertEquals("2026-11-10(화),영업1팀 김대리,138,150,12,,,사적 사용", lines[8])
        assertTrue(lines.contains("총주행거리(km),80"))
        assertTrue(lines.contains("업무용 사용거리(km),50"))
        assertTrue(lines.contains("업무사용비율(%),62.5"))
    }

    @Test
    fun `비고에는 용무 다음에 도착지가 들어간다`() {
        val visit = trip(1, 11, 9, 13, 118f, 32f, Purpose.BUSINESS_VISIT, note = "계약서 미팅").copy(startPlace = "본사", endPlace = "한빛상사")
        val rows = Logbook.rows(listOf(visit), utc)

        assertEquals("거래처 방문 · 한빛상사 · 계약서 미팅", rows[0].remark)
    }

    @Test
    fun `쉼표나 따옴표가 든 칸은 따옴표로 감싼다`() {
        val odd = listOf(trip(1, 11, 9, 8, 100f, 18.5f, Purpose.BUSINESS_VISIT, note = "A사, \"B사\" 방문"))
        val csv = Logbook.csv("차", "번호", 2026, 11, odd, utc)

        assertTrue(csv.contains("100,118.5,18.5,,18.5,\"거래처 방문 · A사, \"\"B사\"\" 방문\""))
    }

    @Test
    fun `같은 날 운행은 계기판 거리 순으로 놓는다`() {
        // 손으로 추가한 운행은 시각을 정오로 적는다. 저녁에 기록된 운행보다 계기판 거리가 뒤면 뒤에 와야 한다.
        val day = java.time.LocalDate.of(2026, 11, 11)
        fun at(hour: Int) = day.atTime(hour, 0).atZone(utc).toInstant().toEpochMilli()
        val evening = Trip(id = 1, startAtMs = at(20), endAtMs = at(21), startOdoKm = 12_340f, endOdoKm = 12_372f, distanceKm = 32f)
        val manual = Trip(id = 2, startAtMs = at(12), endAtMs = at(12), startOdoKm = 12_372f, endOdoKm = 12_379f, distanceKm = 7f, source = Trip.SOURCE_MANUAL)
        val nextDay = Trip(id = 3, startAtMs = at(20) + 86_400_000L, endAtMs = at(21) + 86_400_000L, startOdoKm = 12_379f, endOdoKm = 12_400f, distanceKm = 21f)
        assertEquals(listOf(1L, 2L, 3L), Logbook.monthTrips(listOf(nextDay, manual, evening), 2026, 11, utc).map { it.id })
    }

    @Test
    fun `바꾼 버튼 이름이 비고에 들어가고 거리는 같은 칸에 남는다`() {
        val visit = Trip(id = 1, startAtMs = 0, endAtMs = 1, startOdoKm = 100f, endOdoKm = 132f, distanceKm = 32f, purpose = Purpose.BUSINESS_VISIT, endPlace = "성남 현장")
        val row = Logbook.rows(listOf(visit), utc) { if (it == Purpose.BUSINESS_VISIT) "현장 점검" else it.label }.single()
        assertEquals("현장 점검 · 성남 현장", row.remark)
        assertEquals(32f, row.businessKm!!, 0f)
        assertNull(row.commuteKm)
    }
}
