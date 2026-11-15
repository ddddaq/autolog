package com.autolog.app.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 운행기록부를 PDF로 그린다. A4 가로 한 장에 한 달, 줄이 많으면 여러 장으로 넘긴다.
 * 국세청 고시 서식의 항목과 번호(①~⑬)를 따른다. 상호명과 사업자등록번호는 앱이 모으지 않으므로 빈칸으로 둔다.
 * 안드로이드에 들어 있는 PdfDocument만 쓴다.
 */
object LogbookPdf {

    class Content(
        val vehicleModel: String,
        val plateNo: String,
        val year: Int,
        val month: Int,
        val rows: List<LogbookRow>,
        val totals: LogbookTotals,
        /** 그 해의 누적 */
        val yearTotals: LogbookTotals,
    )

    // A4 가로, 1/72인치 단위
    private const val PAGE_W = 842
    private const val PAGE_H = 595
    private const val MARGIN = 36f
    private const val ROW_H = 17f
    private const val HEAD_H = 30f

    /** 서식 항목 순서의 열: 제목(두 줄), 너비 비율, 숫자 열인가 */
    private class Column(val title: List<String>, val weight: Float, val number: Boolean)

    private val columns = listOf(
        Column(listOf("③ 사용일자", "(요일)"), 1.5f, false),
        Column(listOf("④ 사용자", "(부서·성명)"), 1.7f, false),
        Column(listOf("⑤ 주행 전", "계기판의 거리(㎞)"), 1.3f, true),
        Column(listOf("⑥ 주행 후", "계기판의 거리(㎞)"), 1.3f, true),
        Column(listOf("⑦ 주행거리", "(㎞)"), 1.0f, true),
        Column(listOf("⑧ 출·퇴근용", "(㎞)"), 1.0f, true),
        Column(listOf("⑨ 일반 업무용", "(㎞)"), 1.0f, true),
        Column(listOf("⑩ 비고"), 3.0f, false),
    )

    fun bytes(content: Content): ByteArray {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD }
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; typeface = Typeface.DEFAULT_BOLD }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f }
        val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.6f }

        val tableW = PAGE_W - MARGIN * 2
        val weightSum = columns.sumOf { it.weight.toDouble() }.toFloat()
        val edges = columns.runningFold(MARGIN) { x, column -> x + tableW * column.weight / weightSum }

        // 첫 장은 머리 정보가 있어 줄이 적게 들어간다. 마지막 장에는 합계 칸이 들어갈 자리를 남긴다.
        val firstTop = MARGIN + 92f
        val nextTop = MARGIN + 24f
        val bottom = PAGE_H - MARGIN - 14f
        val totalsH = ROW_H * 2 + 22f
        fun capacity(top: Float) = ((bottom - top - HEAD_H) / ROW_H).toInt()
        val pages = mutableListOf<List<LogbookRow>>()
        var rest = content.rows
        var top = firstTop
        while (true) {
            val fits = capacity(top)
            val fitsWithTotals = ((bottom - top - HEAD_H - totalsH) / ROW_H).toInt()
            if (rest.size <= fitsWithTotals) {
                pages += rest
                break
            }
            pages += rest.take(fits)
            rest = rest.drop(fits)
            top = nextTop
        }

        val document = PdfDocument()
        pages.forEachIndexed { index, pageRows ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, index + 1).create())
            val canvas = page.canvas
            var y: Float
            if (index == 0) {
                val heading = "업무용승용차 운행기록부"
                canvas.drawText(heading, (PAGE_W - title.measureText(heading)) / 2f, MARGIN + 14f, title)
                canvas.drawText("기간  ${content.year}년 ${content.month}월", MARGIN, MARGIN + 38f, text)
                val right = PAGE_W - MARGIN
                canvas.drawText("상호명", right - 330f, MARGIN + 38f, text)
                canvas.drawLine(right - 295f, MARGIN + 40f, right - 180f, MARGIN + 40f, line)
                canvas.drawText("사업자등록번호", right - 165f, MARGIN + 38f, text)
                canvas.drawLine(right - 95f, MARGIN + 40f, right, MARGIN + 40f, line)
                canvas.drawText("1. 기본정보", MARGIN, MARGIN + 58f, bold)
                canvas.drawText("① 차종  ${content.vehicleModel}", MARGIN + 70f, MARGIN + 58f, text)
                canvas.drawText("② 자동차등록번호  ${content.plateNo}", MARGIN + 260f, MARGIN + 58f, text)
                canvas.drawText("2. 업무용 사용비율 계산", MARGIN, MARGIN + 80f, bold)
                y = firstTop
            } else {
                canvas.drawText("업무용승용차 운행기록부 (${content.year}년 ${content.month}월, 이어서)", MARGIN, MARGIN + 12f, bold)
                y = nextTop
            }

            // 표 머리
            canvas.drawRect(MARGIN, y, MARGIN + tableW, y + HEAD_H, line)
            columns.forEachIndexed { c, column ->
                if (c > 0) canvas.drawLine(edges[c], y, edges[c], y + HEAD_H, line)
                val lines = column.title
                lines.forEachIndexed { i, part ->
                    val baseline = y + HEAD_H / 2f + 3f + (i - (lines.size - 1) / 2f) * 10f
                    canvas.drawText(part, edges[c] + (edges[c + 1] - edges[c] - small.measureText(part)) / 2f, baseline, small)
                }
            }
            y += HEAD_H

            // 운행 줄
            pageRows.forEach { row ->
                canvas.drawRect(MARGIN, y, MARGIN + tableW, y + ROW_H, line)
                val cells = listOf(
                    row.date, row.user, Logbook.number(row.startOdoKm), Logbook.number(row.endOdoKm), Logbook.number(row.distanceKm),
                    row.commuteKm?.let { Logbook.number(it) }.orEmpty(), row.businessKm?.let { Logbook.number(it) }.orEmpty(), row.remark,
                )
                cells.forEachIndexed { c, value ->
                    if (c > 0) canvas.drawLine(edges[c], y, edges[c], y + ROW_H, line)
                    cell(canvas, value, edges[c], edges[c + 1], y + ROW_H - 5f, text, columns[c].number)
                }
                y += ROW_H
            }

            // 합계: 마지막 장에만
            if (index == pages.lastIndex) {
                y += 10f
                val third = tableW / 3f
                val labels = listOf("⑪ 총주행 거리(㎞)", "⑫ 업무용 사용거리(㎞)", "⑬ 업무사용비율(⑫/⑪)")
                val values = listOf(
                    Logbook.number(content.totals.totalKm),
                    Logbook.number(content.totals.businessKm),
                    percent(content.totals),
                )
                canvas.drawRect(MARGIN, y, MARGIN + tableW, y + ROW_H * 2, line)
                canvas.drawLine(MARGIN, y + ROW_H, MARGIN + tableW, y + ROW_H, line)
                labels.forEachIndexed { i, label ->
                    val left = MARGIN + third * i
                    if (i > 0) canvas.drawLine(left, y, left, y + ROW_H * 2, line)
                    canvas.drawText(label, left + (third - small.measureText(label)) / 2f, y + ROW_H - 5f, small)
                    canvas.drawText(values[i], left + (third - bold.measureText(values[i])) / 2f, y + ROW_H * 2 - 5f, bold)
                }
                y += ROW_H * 2 + 12f
                canvas.drawText(
                    "${content.year}년 누적  총주행 거리 ${Logbook.number(content.yearTotals.totalKm)}㎞, " +
                        "업무용 사용거리 ${Logbook.number(content.yearTotals.businessKm)}㎞, 업무사용비율 ${percent(content.yearTotals)}",
                    MARGIN, y, small,
                )
            }

            val pageNo = "${index + 1} / ${pages.size}"
            canvas.drawText(pageNo, PAGE_W - MARGIN - small.measureText(pageNo), PAGE_H - MARGIN + 4f, small)
            canvas.drawText("오토로그가 차량 신호로 기록", MARGIN, PAGE_H - MARGIN + 4f, small)
            document.finishPage(page)
        }

        val out = ByteArrayOutputStream()
        document.writeTo(out)
        document.close()
        return out.toByteArray()
    }

    /** 한 칸에 글자를 넣는다. 넘치면 잘라 말줄임표를 붙인다. 숫자는 오른쪽에 붙인다. */
    private fun cell(canvas: Canvas, value: String, left: Float, right: Float, baseline: Float, paint: Paint, alignRight: Boolean) {
        if (value.isEmpty()) return
        val pad = 4f
        val room = right - left - pad * 2
        var shown = value
        if (paint.measureText(shown) > room) {
            val count = paint.breakText(shown, true, room - paint.measureText("…"), null)
            shown = shown.take(count) + "…"
        }
        val x = if (alignRight) right - pad - paint.measureText(shown) else left + pad
        canvas.drawText(shown, x, baseline, paint)
    }

    private fun percent(totals: LogbookTotals): String =
        totals.businessRatio?.let { String.format(Locale.ROOT, "%.1f%%", it * 100f) } ?: "-"
}
