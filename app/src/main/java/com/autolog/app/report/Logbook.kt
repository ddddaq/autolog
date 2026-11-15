package com.autolog.app.report

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import com.autolog.app.trip.Ledger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 운행기록부 서식의 한 줄. 국세청 고시 서식의 항목 순서를 따른다. */
data class LogbookRow(
    /** 사용일자(요일) */
    val date: String,
    /** 사용자(부서·성명) */
    val user: String,
    /** 주행 전 계기판의 거리 */
    val startOdoKm: Float,
    /** 주행 후 계기판의 거리 */
    val endOdoKm: Float,
    /** 주행거리 */
    val distanceKm: Float,
    /** 업무용 사용거리 중 출·퇴근용. 해당 없으면 null */
    val commuteKm: Float?,
    /** 업무용 사용거리 중 일반 업무용. 해당 없으면 null */
    val businessKm: Float?,
    /** 비고 */
    val remark: String,
)

/** 내보내는 표의 한 칸. 글자이거나 숫자다. */
sealed interface Cell {
    /** 파일에 적는 글자 */
    val text: String

    data class Text(override val text: String) : Cell

    /** 숫자 칸. [text]는 CSV와 화면에 쓰는 표기. */
    data class Number(val value: Float, override val text: String = Logbook.number(value)) : Cell
}

data class LogbookTotals(
    /** 총주행거리 */
    val totalKm: Float,
    val commuteKm: Float,
    val generalBusinessKm: Float,
    /** 용무를 골라야 하는 미분류 운행 수. 짧은 이동은 세지 않는다. */
    val unclassifiedCount: Int,
    /** 용무를 묻지 않은 짧은 이동. 총주행거리에는 들어 있다. */
    val shortCount: Int = 0,
    val shortKm: Float = 0f,
) {
    /** 업무용 사용거리 = 출·퇴근용 + 일반 업무용 */
    val businessKm: Float get() = commuteKm + generalBusinessKm

    /** 업무사용비율 = 업무용 사용거리 / 총주행거리 */
    val businessRatio: Float? get() = if (totalKm > 0f) businessKm / totalKm else null
}

/**
 * 운행 기록을 업무용승용차 운행기록부 서식으로 바꾼다.
 * 사적 사용과 미분류 운행도 한 줄로 적되, 업무용 사용거리 칸은 비운다. 그래야 총주행거리가 계기판과 맞는다.
 */
object Logbook {

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREAN)

    /**
     * 해당 월에 끝난 운행을 날짜 순으로, 같은 날은 계기판 거리 순으로.
     * 손으로 추가한 운행은 시각을 모르므로, 같은 날 안에서는 계기판 거리가 순서를 정한다.
     */
    fun monthTrips(trips: List<Trip>, year: Int, month: Int, zone: ZoneId = ZoneId.systemDefault()): List<Trip> =
        trips.filter {
            val end = Instant.ofEpochMilli(it.endAtMs).atZone(zone)
            end.year == year && end.monthValue == month
        }.sortedWith(
            compareBy(
                { Instant.ofEpochMilli(it.startAtMs).atZone(zone).toLocalDate() },
                { it.startOdoKm },
                { it.startAtMs },
                { it.id },
            )
        )

    fun rows(
        monthTrips: List<Trip>,
        zone: ZoneId = ZoneId.systemDefault(),
        labelOf: (Purpose) -> String = { it.label },
    ): List<LogbookRow> = monthTrips.map { trip ->
        LogbookRow(
            date = dateFormat.format(Instant.ofEpochMilli(trip.startAtMs).atZone(zone)),
            user = listOf(trip.driverDept, trip.driverName).filter { it.isNotBlank() }.joinToString(" "),
            startOdoKm = trip.startOdoKm,
            endOdoKm = trip.endOdoKm,
            distanceKm = trip.distanceKm,
            commuteKm = trip.distanceKm.takeIf { trip.purpose == Purpose.COMMUTE },
            businessKm = trip.distanceKm.takeIf { trip.purpose == Purpose.BUSINESS_VISIT || trip.purpose == Purpose.BUSINESS_GENERAL },
            // 비고: 용무, 도착지(방문한 곳), 메모 순서
            remark = listOf(remarkFor(trip.purpose, labelOf), trip.endPlace, trip.note).filter { it.isNotBlank() }.joinToString(" · "),
        )
    }

    /** [minTripKm]을 주면 그보다 짧은 미분류 이동을 미분류 건수에서 빼고 따로 센다. 거리 합계는 달라지지 않는다. */
    fun totals(monthTrips: List<Trip>, minTripKm: Float = 0f): LogbookTotals {
        val short = monthTrips.filter { Ledger.isShortMove(it, minTripKm) }
        return LogbookTotals(
            totalKm = monthTrips.sumOf { it.distanceKm.toDouble() }.toFloat(),
            commuteKm = monthTrips.filter { it.purpose == Purpose.COMMUTE }.sumOf { it.distanceKm.toDouble() }.toFloat(),
            generalBusinessKm = monthTrips.filter { it.purpose == Purpose.BUSINESS_VISIT || it.purpose == Purpose.BUSINESS_GENERAL }
                .sumOf { it.distanceKm.toDouble() }.toFloat(),
            unclassifiedCount = monthTrips.count { it.purpose == Purpose.UNCLASSIFIED } - short.size,
            shortCount = short.size,
            shortKm = short.sumOf { it.distanceKm.toDouble() }.toFloat(),
        )
    }

    /** 해당 연도에 끝난 운행. 서식 아래쪽의 과세기간 합계에 쓴다. */
    fun yearTrips(trips: List<Trip>, year: Int, zone: ZoneId = ZoneId.systemDefault()): List<Trip> =
        trips.filter { Instant.ofEpochMilli(it.endAtMs).atZone(zone).year == year }

    /** 표 머리 줄이 몇 번째 줄인지(0부터). 엑셀 파일에서 굵게 표시하는 데 쓴다. */
    const val HEADER_LINE = 5

    /**
     * 내보내는 표 전체. CSV와 엑셀 파일이 같은 내용을 쓴다.
     * 머리(차종, 등록번호, 기간), 서식 항목 순서의 운행 줄, 합계(총주행거리, 업무용 사용거리, 업무사용비율) 순서다.
     */
    fun sheet(
        vehicleModel: String, plateNo: String, year: Int, month: Int, monthTrips: List<Trip>,
        zone: ZoneId = ZoneId.systemDefault(), labelOf: (Purpose) -> String = { it.label },
    ): List<List<Cell>> {
        val totals = totals(monthTrips)
        fun text(vararg values: String): List<Cell> = values.map { Cell.Text(it) }
        fun optional(value: Float?): Cell = if (value == null) Cell.Text("") else Cell.Number(value)
        val lines = mutableListOf<List<Cell>>()
        lines += text("업무용승용차 운행기록부")
        lines += text("차종", vehicleModel)
        lines += text("자동차등록번호", plateNo)
        lines += text("기간", "${year}년 ${month}월")
        lines += text("")
        lines += text(
            "사용일자(요일)", "사용자(부서·성명)", "주행 전 계기판의 거리(km)", "주행 후 계기판의 거리(km)",
            "주행거리(km)", "출·퇴근용(km)", "일반 업무용(km)", "비고",
        )
        rows(monthTrips, zone, labelOf).forEach { row ->
            lines += listOf(
                Cell.Text(row.date), Cell.Text(row.user), Cell.Number(row.startOdoKm), Cell.Number(row.endOdoKm), Cell.Number(row.distanceKm),
                optional(row.commuteKm), optional(row.businessKm), Cell.Text(row.remark),
            )
        }
        lines += text("")
        lines += listOf(Cell.Text("총주행거리(km)"), Cell.Number(totals.totalKm))
        lines += listOf(Cell.Text("업무용 사용거리(km)"), Cell.Number(totals.businessKm))
        lines += listOf(
            Cell.Text("업무사용비율(%)"),
            totals.businessRatio?.let { Cell.Number(it * 100f, String.format(Locale.ROOT, "%.1f", it * 100f)) } ?: Cell.Text(""),
        )
        return lines
    }

    /** 엑셀에서 바로 열리는 CSV. 파일로 쓸 때 앞에 UTF-8 BOM을 붙인다. */
    fun csv(
        vehicleModel: String, plateNo: String, year: Int, month: Int, monthTrips: List<Trip>,
        zone: ZoneId = ZoneId.systemDefault(), labelOf: (Purpose) -> String = { it.label },
    ): String =
        sheet(vehicleModel, plateNo, year, month, monthTrips, zone, labelOf)
            .joinToString("\r\n") { line -> line.joinToString(",") { escape(it.text) } } + "\r\n"

    private fun remarkFor(purpose: Purpose, labelOf: (Purpose) -> String): String = when (purpose) {
        Purpose.PRIVATE -> "사적 사용"
        else -> labelOf(purpose)
    }

    /** 12340.0 → "12340", 18.5 → "18.5". 천 단위 구분 기호는 넣지 않는다. */
    fun number(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else String.format(Locale.ROOT, "%.1f", rounded)
    }

    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}
