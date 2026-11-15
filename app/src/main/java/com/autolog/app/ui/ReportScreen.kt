package com.autolog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autolog.app.data.Profile
import com.autolog.app.data.Trip
import com.autolog.app.report.Logbook
import com.autolog.app.report.LogbookRow
import com.autolog.app.share.PhoneShare
import com.autolog.app.trip.Ledger
import java.time.YearMonth
import java.util.Locale
import kotlin.math.floor

/**
 * 운행기록부 탭. 한 달 운행을 국세청 고시 서식의 항목 순서대로 보여 주고 엑셀·PDF·CSV 파일로 내보낸다.
 * 서식 아래쪽의 합계에 맞춰 그 해의 누적 거리와 업무사용비율도 함께 보여 준다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportContent(
    trips: List<Trip>,
    profile: Profile,
    /** 빈 구간을 찾는 기준이 되는 지금 계기판 거리 */
    uptoOdoKm: Float?,
    nowMs: Long,
    wide: Boolean,
    /** 폰으로 받기가 열려 있으면 그 상태 */
    share: PhoneShare.State?,
    onExport: (Int, Int, (String, Boolean) -> Unit) -> Unit,
    onShare: (Int, Int) -> Unit,
    onCloseShare: () -> Unit,
) {
    val now = at(nowMs)
    var year by rememberSaveable { mutableIntStateOf(now.year) }
    var month by rememberSaveable { mutableIntStateOf(now.monthValue) }
    var exported by rememberSaveable { mutableStateOf<String?>(null) }
    // 파일이 만들어졌는가. 만들어진 뒤에만 폰으로 받기를 내놓는다.
    var exportedOk by rememberSaveable { mutableStateOf(false) }
    // 이 화면을 떠나면 폰으로 받기를 닫는다.
    DisposableEffect(Unit) { onDispose { onCloseShare() } }
    val monthTrips = remember(trips, year, month) { Logbook.monthTrips(trips, year, month) }
    val label = LocalPurposeLabel.current
    val rows = remember(monthTrips, label) { Logbook.rows(monthTrips, labelOf = label) }
    val totals = remember(monthTrips, profile.minTripKm) { Logbook.totals(monthTrips, profile.minTripKm.toFloat()) }
    // 이 달에 걸린 빈 구간. 뒤 운행이 이 달에 시작했거나, 마지막 기록 뒤로 지금까지 비어 있는 구간이다.
    val monthGaps = remember(trips, uptoOdoKm, year, month) {
        Ledger.gaps(trips, uptoOdoKm).filter { gap ->
            val time = at(gap.beforeMs ?: nowMs)
            time.year == year && time.monthValue == month
        }
    }
    val yearTotals = remember(trips, year) { Logbook.totals(Logbook.yearTrips(trips, year)) }

    fun step(months: Long) {
        val next = YearMonth.of(year, month).plusMonths(months)
        year = next.year
        month = next.monthValue
        exported = null
        exportedOk = false
        onCloseShare()
    }

    val monthStepper: @Composable () -> Unit = {
        Pill(text = "이전 달", selected = false, onClick = { step(-1) })
        Text(text = "${year}년 ${month}월", fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Pill(text = "다음 달", selected = false, onClick = { step(1) })
    }
    val exportButton: @Composable () -> Unit = {
        Button(
            onClick = {
                exported = "내보내는 중입니다"
                exportedOk = false
                onCloseShare()
                onExport(year, month) { message, ok ->
                    exported = message
                    exportedOk = ok
                }
            },
            enabled = rows.isNotEmpty(),
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(84.dp),
        ) {
            Text(text = "엑셀·PDF로 내보내기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "업무용승용차 운행기록부", fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                monthStepper()
                exportButton()
            }
        } else {
            Text(text = "업무용승용차 운행기록부", fontSize = 36.sp, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { monthStepper() }
                exportButton()
            }
        }

        // 내보내기 결과. 눌렀을 때 무슨 일이 일어났는지 버튼 바로 아래에서 크게 알린다.
        exported?.let {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = it,
                        fontSize = 28.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // 만든 파일을 차 밖으로 가져가는 길. 같은 네트워크의 폰이 QR을 찍어 받아 간다.
                    if (exportedOk && share == null) QuietButton(text = "폰으로 받기", onClick = { onShare(year, month) })
                }
            }
        }

        if (share != null) SharePanel(share = share, onClose = onCloseShare)

        Panel {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(if (wide) 56.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 좁은 창에서는 설정에 있는 차량 정보를 빼고 숫자만 보여 준다.
                if (wide) {
                    Fact("차종", profile.vehicleModel.ifBlank { "설정에서 입력" })
                    Fact("자동차등록번호", profile.plateNo.ifBlank { "설정에서 입력" })
                }
                Fact("총주행거리", "${km(totals.totalKm)}km")
                Fact("업무용 사용거리", "${km(totals.businessKm)}km")
                Fact("업무사용비율", percent(totals.businessKm, totals.totalKm))
                Fact("미분류", "${totals.unclassifiedCount}건")
                // 짧은 이동은 용무를 묻지 않지만 총주행거리에는 들어 있다.
                if (totals.shortCount > 0) Fact("짧은 이동", "${totals.shortCount}건 · ${km(totals.shortKm)}km")
                if (monthGaps.isNotEmpty()) Fact("기록이 빈 구간", "${monthGaps.size}곳 · ${km(monthGaps.sumOf { it.km.toDouble() }.toFloat())}km")
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(if (wide) 56.dp else 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Fact(if (wide) "${year}년 누적 총주행거리" else "${year}년 누적", "${km(yearTotals.totalKm)}km")
                Fact(if (wide) "${year}년 누적 업무용 사용거리" else "업무용", "${km(yearTotals.businessKm)}km")
                Fact(if (wide) "${year}년 업무사용비율" else "비율", percent(yearTotals.businessKm, yearTotals.totalKm))
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxSize()) {
            // 좁은 창에서는 표를 줄이지 않고 옆으로 밀어 본다. 여덟 칸을 좁혀 넣으면 숫자가 잘린다.
            val table = if (wide) Modifier else Modifier.horizontalScroll(rememberScrollState()).width(TABLE_MIN_WIDTH)
            Column(modifier = table.padding(horizontal = 28.dp, vertical = 20.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Cell("사용일자(요일)", W_DATE, bold = true)
                    Cell("사용자(부서·성명)", W_USER, bold = true)
                    Cell("주행 전 계기판", W_ODO, bold = true, end = true)
                    Cell("주행 후 계기판", W_ODO, bold = true, end = true)
                    Cell("주행거리", W_KM, bold = true, end = true)
                    Cell("출·퇴근용", W_KM, bold = true, end = true)
                    Cell("일반 업무용", W_KM, bold = true, end = true)
                    Cell("비고", W_REMARK, bold = true)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (rows.isEmpty()) {
                    Text(
                        text = "이 달에는 운행 기록이 없습니다",
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 28.dp),
                    )
                } else {
                    LazyColumn {
                        items(rows) { row -> LogbookLine(row) }
                    }
                }
            }
        }
    }
}

/**
 * 폰으로 받기. QR을 찍으면 폰 브라우저에 받을 파일이 보인다. 폰이 파일을 받아 가면 여기에 알린다.
 * QR은 화면이 어두운 색이어도 흰 바탕에 검은 칸으로 그린다.
 */
@Composable
private fun SharePanel(share: PhoneShare.State, onClose: () -> Unit) {
    val closesAt = at(share.expiresAtMs)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            share.qr?.let { qr ->
                Canvas(modifier = Modifier.size(QR_SIZE).clip(RoundedCornerShape(16.dp)).background(Color.White)) {
                    val cells = qr.size + QR_QUIET * 2
                    val cell = floor(size.minDimension / cells)
                    val origin = (size.minDimension - cell * cells) / 2 + cell * QR_QUIET
                    for (y in qr.indices) for (x in qr.indices) {
                        if (qr[y][x]) drawRect(Color.Black, topLeft = Offset(origin + x * cell, origin + y * cell), size = Size(cell, cell))
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "폰으로 받기", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (share.reachable) "폰을 차량과 같은 Wi-Fi에 연결하고 QR을 찍으면 받을 파일이 보입니다"
                    else "차량이 네트워크에 연결되어 있지 않아 폰에서 찾아올 수 없습니다",
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = share.url, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (share.received.isEmpty()) "아직 받아 간 기기가 없습니다" else "받아 감: ${share.received.joinToString(", ")}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (share.received.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = String.format(Locale.ROOT, "%02d:%02d에 닫힙니다. 주행을 시작해도 닫힙니다", closesAt.hour, closesAt.minute),
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    QuietButton(text = "닫기", onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun LogbookLine(row: LogbookRow) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Cell(row.date, W_DATE)
        Cell(row.user, W_USER)
        Cell(km(row.startOdoKm), W_ODO, end = true)
        Cell(km(row.endOdoKm), W_ODO, end = true)
        Cell(km(row.distanceKm), W_KM, end = true)
        Cell(row.commuteKm?.let { km(it) }.orEmpty(), W_KM, end = true)
        Cell(row.businessKm?.let { km(it) }.orEmpty(), W_KM, end = true)
        Cell(row.remark, W_REMARK)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, bold: Boolean = false, end: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(horizontal = 10.dp),
        fontSize = 24.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (bold) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = if (end) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(text = value, fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val QR_SIZE = 280.dp

/** QR 둘레에 비워 두는 칸 수. */
private const val QR_QUIET = 4

private const val W_DATE = 1.5f
private const val W_USER = 1.5f
private const val W_ODO = 1.15f
private const val W_KM = 0.95f
private const val W_REMARK = 1.6f

/** 좁은 창에서 표가 차지하는 폭. 이보다 창이 좁으면 옆으로 민다. */
private val TABLE_MIN_WIDTH = 1500.dp
