package com.autolog.app.report

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.autolog.app.data.Profile
import com.autolog.app.data.Trip
import com.autolog.app.share.PhoneShare
import java.io.File
import java.util.Locale

/**
 * 운행기록부를 파일로 쓴다. 엑셀 파일(.xlsx), 서식 모양의 PDF, CSV를 함께 만든다.
 *
 * 차량 저장소의 Download/AutoLog 폴더에 써서 다른 앱이나 USB로 꺼낼 수 있게 하고,
 * 그 폴더에 쓰지 못하면 앱 내부 폴더(files/export)에만 남긴다.
 */
class LogbookExporter(context: Context) {

    data class Result(val tripCount: Int, val fileName: String, val inDownloads: Boolean)

    private val appContext = context.applicationContext

    fun export(year: Int, month: Int, trips: List<Trip>, profile: Profile): Result {
        val monthTrips = Logbook.monthTrips(trips, year, month)
        val labelOf = profile::labelOf
        val sheet = Logbook.sheet(profile.vehicleModel, profile.plateNo, year, month, monthTrips, labelOf = labelOf)
        val base = String.format(Locale.ROOT, "autolog_%04d-%02d", year, month)
        val xlsx = Xlsx.workbook(
            sheetName = "${year}년 ${month}월",
            lines = sheet,
            boldLines = setOf(0, Logbook.HEADER_LINE),
            columnWidths = COLUMN_WIDTHS,
        )
        // 엑셀이 한글을 바로 읽도록 CSV 앞에 UTF-8 BOM을 붙인다.
        val csv = (BOM + Logbook.csv(profile.vehicleModel, profile.plateNo, year, month, monthTrips, labelOf = labelOf)).toByteArray(Charsets.UTF_8)
        val pdf = LogbookPdf.bytes(
            LogbookPdf.Content(
                vehicleModel = profile.vehicleModel,
                plateNo = profile.plateNo,
                year = year,
                month = month,
                rows = Logbook.rows(monthTrips, labelOf = labelOf),
                totals = Logbook.totals(monthTrips),
                yearTotals = Logbook.totals(Logbook.yearTrips(trips, year)),
            )
        )

        val dir = File(appContext.filesDir, INTERNAL_DIR).apply { mkdirs() }
        File(dir, "$base.xlsx").writeBytes(xlsx)
        File(dir, "$base.csv").writeBytes(csv)
        File(dir, "$base.pdf").writeBytes(pdf)

        val shared = runCatching {
            saveToDownloads("$base.xlsx", XLSX_MIME, xlsx) && saveToDownloads("$base.pdf", "application/pdf", pdf) &&
                saveToDownloads("$base.csv", "text/csv", csv)
        }.getOrElse { error ->
            Log.w(TAG, "Download 폴더에 쓰지 못함: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
        Log.i(TAG, "운행기록부 내보냄: $base.xlsx, $base.pdf, $base.csv (${monthTrips.size}건, ${if (shared) "Download/AutoLog" else "앱 내부 폴더"})")
        return Result(monthTrips.size, "$base.xlsx · .pdf · .csv", shared)
    }

    /** 그 달에 내보내 둔 파일. 폰으로 받기에 쓴다. PDF, 엑셀, CSV 순서이고 없는 것은 빠진다. */
    fun exported(year: Int, month: Int): List<PhoneShare.Item> {
        val base = String.format(Locale.ROOT, "autolog_%04d-%02d", year, month)
        val dir = File(appContext.filesDir, INTERNAL_DIR)
        return listOf("pdf" to "application/pdf", "xlsx" to XLSX_MIME, "csv" to "text/csv; charset=utf-8")
            .map { (extension, mime) -> PhoneShare.Item("$base.$extension", mime, File(dir, "$base.$extension")) }
            .filter { it.file.exists() }
    }

    /** 내보낸 파일을 모두 지운다. 데이터 전체 삭제 때 부른다. */
    fun deleteAll() {
        File(appContext.filesDir, INTERNAL_DIR).deleteRecursively()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                appContext.contentResolver.delete(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                    arrayOf(SHARED_PATH),
                )
            }.onFailure { Log.w(TAG, "Download 폴더의 파일을 지우지 못함: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val resolver = appContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 같은 달을 다시 내보내면 전에 쓴 파일을 지우고 새로 쓴다.
        // 같은 이름의 파일을 이 앱이 지울 수 없을 때(앱을 지웠다 다시 깐 경우 등) 시스템이 "이름 (1).xlsx"처럼 번호를 붙이므로,
        // 번호가 붙은 것까지 찾아 지운다. 이 앱이 쓴 파일만 지워진다.
        val stem = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.')
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(SHARED_PATH, "$stem%.$extension"),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SHARED_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return false
        val written = resolver.openOutputStream(uri)?.use { it.write(bytes); true } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return true
    }

    private companion object {
        const val TAG = "AUTOLOG"
        const val INTERNAL_DIR = "export"
        /** 엑셀이 CSV의 한글을 바로 읽게 하는 UTF-8 BOM */
        const val BOM = "\uFEFF"
        const val SHARED_PATH = "Download/AutoLog/"
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        /** 서식 항목 순서대로의 열 너비 */
        val COLUMN_WIDTHS = listOf(18, 20, 26, 26, 14, 16, 16, 44)
    }
}
