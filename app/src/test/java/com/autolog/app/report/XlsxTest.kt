package com.autolog.app.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class XlsxTest {

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val parts = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                parts[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return parts
    }

    private val lines = listOf(
        listOf<Cell>(Cell.Text("업무용승용차 운행기록부")),
        listOf(Cell.Text("사용일자(요일)"), Cell.Text("주행거리(km)"), Cell.Text("비고")),
        listOf(Cell.Text("2026-11-09(월)"), Cell.Number(18.5f), Cell.Text("A사 & \"B사\" <방문>")),
        listOf(Cell.Text("합계"), Cell.Number(12_340f), Cell.Text("")),
    )

    @Test
    fun `엑셀 파일에 필요한 부분이 모두 들어 있다`() {
        val parts = unzip(Xlsx.workbook("2026년 11월", lines))
        assertEquals(
            setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"),
            parts.keys,
        )
        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"2026년 11월\""))
    }

    @Test
    fun `모든 부분이 올바른 XML이다`() {
        val factory = DocumentBuilderFactory.newInstance()
        unzip(Xlsx.workbook("시트", lines, boldLines = setOf(1), columnWidths = listOf(18, 12, 30))).forEach { (name, xml) ->
            // 깨진 XML이면 여기서 예외가 난다.
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            assertTrue(name, document.documentElement != null)
        }
    }

    @Test
    fun `숫자는 숫자 칸으로, 글자는 글자 칸으로 들어간다`() {
        val sheet = unzip(Xlsx.workbook("시트", lines)).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<c r=\"B3\"><v>18.5</v></c>"))
        assertTrue(sheet.contains("<c r=\"B4\"><v>12340</v></c>"))
        assertTrue(sheet.contains("<c r=\"A3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">2026-11-09(월)</t></is></c>"))
        // 빈 글자 칸은 쓰지 않는다.
        assertFalse(sheet.contains("r=\"C4\""))
    }

    @Test
    fun `XML에서 뜻이 있는 글자는 바꿔 적는다`() {
        val sheet = unzip(Xlsx.workbook("시트", lines)).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("A사 &amp; &quot;B사&quot; &lt;방문&gt;"))
    }

    @Test
    fun `굵게 쓸 줄과 열 너비가 반영된다`() {
        val sheet = unzip(Xlsx.workbook("시트", lines, boldLines = setOf(1), columnWidths = listOf(18, 12))).getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<c r=\"A2\" s=\"1\" t=\"inlineStr\">"))
        assertTrue(sheet.contains("<col min=\"2\" max=\"2\" width=\"12\" customWidth=\"1\"/>"))
    }

    @Test
    fun `열 이름은 Z 다음에 AA로 넘어간다`() {
        assertEquals("A", Xlsx.columnName(0))
        assertEquals("H", Xlsx.columnName(7))
        assertEquals("Z", Xlsx.columnName(25))
        assertEquals("AA", Xlsx.columnName(26))
        assertEquals("AB", Xlsx.columnName(27))
    }
}
