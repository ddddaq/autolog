package com.autolog.app.report

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 표 한 장짜리 엑셀 파일(.xlsx)을 만든다. 외부 라이브러리 없이 필요한 부분만 직접 쓴다.
 * 글자는 글자 칸으로, 숫자는 숫자 칸으로 들어가 엑셀에서 바로 합계를 낼 수 있다.
 */
object Xlsx {

    /**
     * @param lines 표의 줄들
     * @param boldLines 굵게 쓸 줄 번호(0부터)
     * @param columnWidths 열 너비(글자 수 기준)
     */
    fun workbook(sheetName: String, lines: List<List<Cell>>, boldLines: Set<Int> = emptySet(), columnWidths: List<Int> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", CONTENT_TYPES)
            zip.put("_rels/.rels", ROOT_RELS)
            zip.put("xl/workbook.xml", workbookXml(sheetName))
            zip.put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            zip.put("xl/styles.xml", STYLES)
            zip.put("xl/worksheets/sheet1.xml", sheetXml(lines, boldLines, columnWidths))
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.put(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(lines: List<List<Cell>>, boldLines: Set<Int>, columnWidths: List<Int>): String = buildString {
        append(XML_HEAD)
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        if (columnWidths.isNotEmpty()) {
            append("<cols>")
            columnWidths.forEachIndexed { index, width ->
                append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols>")
        }
        append("<sheetData>")
        lines.forEachIndexed { lineIndex, line ->
            val style = if (lineIndex in boldLines) " s=\"1\"" else ""
            append("<row r=\"${lineIndex + 1}\">")
            line.forEachIndexed { columnIndex, cell ->
                val ref = columnName(columnIndex) + (lineIndex + 1)
                when (cell) {
                    is Cell.Number -> append("<c r=\"$ref\"$style><v>${plain(cell.value)}</v></c>")
                    is Cell.Text -> if (cell.text.isNotEmpty()) {
                        append("<c r=\"$ref\"$style t=\"inlineStr\"><is><t xml:space=\"preserve\">${escape(cell.text)}</t></is></c>")
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 0 → A, 25 → Z, 26 → AA */
    internal fun columnName(index: Int): String {
        var n = index
        val name = StringBuilder()
        while (true) {
            name.insert(0, 'A' + n % 26)
            n = n / 26 - 1
            if (n < 0) break
        }
        return name.toString()
    }

    /** 숫자 칸에 넣는 값. 소수 첫째 자리까지, 지수 표기 없이. */
    private fun plain(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded % 1f == 0f) rounded.toLong().toString() else String.format(Locale.ROOT, "%.1f", rounded)
    }

    private fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                // XML에 넣을 수 없는 제어 문자는 뺀다.
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    private fun workbookXml(sheetName: String): String =
        XML_HEAD +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"${escape(sheetName.take(31))}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    private const val CONTENT_TYPES = XML_HEAD +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val ROOT_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "</Relationships>"

    private const val WORKBOOK_RELS = XML_HEAD +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    /** 글꼴 둘(보통, 굵게)과 칸 모양 둘(0: 보통, 1: 굵게). */
    private const val STYLES = XML_HEAD +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"11\"/><name val=\"맑은 고딕\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"2\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"
}
