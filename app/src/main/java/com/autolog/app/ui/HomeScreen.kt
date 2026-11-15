package com.autolog.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autolog.app.data.Driver
import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import com.autolog.app.place.PlaceBook
import com.autolog.app.place.SavedPlace
import com.autolog.app.report.Logbook
import com.autolog.app.report.MonthEnd
import com.autolog.app.trip.Ledger
import com.autolog.app.trip.PurposeSuggester
import com.autolog.app.trip.Suggestion
import com.autolog.app.trip.TripEdits
import com.autolog.app.trip.TripRecorder
import com.autolog.app.vehicle.VoiceMemo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 운행 탭. 정리할 운행, 용무 카드, 이번 달 요약, 운행 기록을 보여 준다.
 * 폭이 넓으면(앱 영역 2개) 카드·요약과 기록을 두 열로 나란히 놓는다.
 */
@Composable
fun HomeContent(
    /** 출발지·도착지를 저장한 장소 이름으로 바꿔 읽은 운행 */
    trips: List<Trip>,
    /** 원래 기록 그대로의 운행. 장소에 이름을 붙일 때 쓴다 */
    rawTrips: List<Trip>,
    /** 합치기나 나누기를 되돌릴 수 있는 운행의 id */
    undoable: Set<Long>,
    /** 되살릴 수 있는 지운 운행. 장소 이름으로 바꿔 읽은 것 */
    restorable: List<Trip>,
    places: List<SavedPlace>,
    drivers: List<Driver>,
    /** 정차 대기 중인 운행. 문을 열기 전에 용무를 미리 고르도록 카드로 보여 준다 */
    parked: Trip?,
    /** 이보다 짧은 이동은 기록만 하고 용무를 묻지 않는다(km) */
    minTripKm: Int,
    odometerKm: Float?,
    /** 빈 구간을 찾는 기준. 지금 계기판 거리이고, 운행이 열려 있으면 그 운행의 출발 거리다 */
    uptoOdoKm: Float?,
    nowMs: Long,
    wide: Boolean,
    memo: VoiceMemo.State,
    actions: TripActions,
) {
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    // 채우려고 고른 빈 구간. null이면 운행 추가는 마지막 기록 뒤를 채운다.
    var fillGap by remember { mutableStateOf<Ledger.Gap?>(null) }
    var showDeleted by rememberSaveable { mutableStateOf(false) }
    // 방금 정리한 운행. 용무를 잘못 눌렀을 때 되돌릴 수 있게 잠시 보여 준다.
    var lastDone by remember { mutableStateOf<DoneChange?>(null) }
    LaunchedEffect(lastDone) {
        if (lastDone != null) {
            delay(DONE_SHOWN_MS)
            lastDone = null
        }
    }
    val scope = rememberCoroutineScope()
    val leftScroll = rememberScrollState()
    // 전에 적은 메모. 같은 말을 다시 적을 때 키보드 없이 고르게 한다.
    val recentNotes = remember(trips) {
        trips.sortedByDescending { it.endAtMs }.map { it.note.trim() }.filter { it.isNotEmpty() }.distinct().take(RECENT_NOTES)
    }
    val minKm = minTripKm.toFloat()
    // 짧은 이동은 기록만 하고 용무를 묻지 않는다. 정리할 운행에 넣지 않는다.
    val unclassified = remember(trips, minKm) { trips.filter { it.purpose == Purpose.UNCLASSIFIED && !Ledger.isShortMove(it, minKm) } }
    // 아직 분류하지 않은 운행마다, 지난 분류를 보고 용무를 제안한다.
    val suggestions = remember(trips, unclassified) {
        val asked = unclassified.map { it.id }.toSet()
        PurposeSuggester.suggestAll(trips).filterKeys { it in asked }
    }
    // 정차 중인 운행에도 지난 분류를 보고 용무를 제안한다. 이미 골랐으면 제안하지 않는다.
    val parkedSuggestion = remember(parked, trips) {
        parked?.takeIf { it.purpose == Purpose.UNCLASSIFIED }?.let { PurposeSuggester.suggest(it, trips) }
    }
    val target = trips.firstOrNull { it.id == selectedId } ?: parked ?: unclassified.firstOrNull()

    // 새 운행이 들어오면 고르던 운행을 놓고 새 운행의 카드를 보여 준다.
    LaunchedEffect(trips.firstOrNull()?.id) { selectedId = null }
    // 정차하면 고르던 운행을 놓고 정차 중인 운행의 카드를 보여 준다.
    LaunchedEffect(parked?.startAtMs) { if (parked != null) selectedId = null }
    // 정차 중에 골라 둔 용무로 운행이 닫히면, 무엇으로 기록했는지 알려 주고 되돌릴 수 있게 한다.
    var lastParked by remember { mutableStateOf<Trip?>(null) }
    LaunchedEffect(parked, trips.firstOrNull()?.id) {
        val was = lastParked
        if (parked != null) {
            lastParked = parked
        } else if (was != null) {
            val closed = trips.firstOrNull { it.startAtMs == was.startAtMs && it.startOdoKm == was.startOdoKm }
            if (closed != null) {
                lastParked = null
                if (closed.purpose != Purpose.UNCLASSIFIED) {
                    lastDone = DoneChange(before = mapOf(closed.id to Purpose.UNCLASSIFIED), after = mapOf(closed.id to closed.purpose))
                }
            }
        }
    }

    // 계기판 거리가 벌어진 구간. 운행이 막 닫혀 목록에 들어오기 전의 짧은 틈은 건너뛴다.
    val freshGaps = remember(trips, uptoOdoKm) { Ledger.gaps(trips, uptoOdoKm) }
    var settledGaps by remember { mutableStateOf(emptyList<Ledger.Gap>()) }
    LaunchedEffect(freshGaps) {
        delay(GAP_SETTLE_MS)
        settledGaps = freshGaps
    }
    val gaps = settledGaps.filter { it in freshGaps }
    val showGap = gaps.isNotEmpty() && !adding
    val gap: @Composable () -> Unit = {
        if (showGap) {
            GapBar(gaps = gaps, onFill = {
                fillGap = gaps.first()
                adding = true
            })
        }
    }

    val card: @Composable () -> Unit = {
        val isParked = target != null && target.id == TripRecorder.PARKED_ID
        val rawTarget = if (isParked) null else target?.let { t -> rawTrips.firstOrNull { it.id == t.id } }
        TripCard(
            trip = target,
            raw = rawTarget,
            previous = rawTarget?.let { TripEdits.previous(it, rawTrips) },
            undoable = rawTarget != null && rawTarget.id in undoable,
            places = places,
            suggestion = if (isParked) parkedSuggestion else target?.let { suggestions[it.id] },
            parked = isParked,
            shortUnderKm = minTripKm.takeIf { target != null && Ledger.isShortMove(target, minKm) },
            drivers = drivers,
            memo = memo,
            actions = actions,
            recentNotes = recentNotes,
            // 목록에서 고른 운행을 보고 있을 때만 닫기 버튼을 둔다.
            onClose = if (selectedId != null && target?.id == selectedId) ({ selectedId = null }) else null,
            onClassified = { trip, purpose ->
                // 정차 중인 운행은 아직 닫히지 않았다. 카드를 그대로 두고, 닫힐 때 알린다.
                if (trip.id != TripRecorder.PARKED_ID) {
                    selectedId = null
                    lastDone = if (trip.purpose != purpose) DoneChange(before = mapOf(trip.id to trip.purpose), after = mapOf(trip.id to purpose)) else null
                }
            },
        )
    }
    val done: @Composable () -> Unit = {
        lastDone?.let { change ->
            DoneBar(
                change = change,
                trips = trips.filter { it.id in change.after },
                onUndo = {
                    lastDone = null
                    selectedId = change.before.keys.singleOrNull()
                    actions.classifyAll(change.before)
                },
            )
        }
    }
    // 달이 끝나 갈 때는 이번 달 미분류가 한 건뿐이어도 정리하라고 알린다.
    val today = at(nowMs).toLocalDate()
    val unclassifiedThisMonth = unclassified.count { at(it.endAtMs).let { end -> end.year == today.year && end.monthValue == today.monthValue } }
    val monthEnd = if (MonthEnd.shouldRemind(today, unclassifiedThisMonth)) MonthEnd.message(today) else null
    val showPending = unclassified.size >= 2 || monthEnd != null
    val pending: @Composable () -> Unit = {
        if (showPending) {
            PendingBar(
                count = unclassified.size,
                suggested = suggestions.size,
                notice = monthEnd,
                onAcceptAll = {
                    selectedId = null
                    val accepted = suggestions.mapValues { it.value.purpose }
                    lastDone = DoneChange(before = accepted.mapValues { Purpose.UNCLASSIFIED }, after = accepted)
                    actions.classifyAll(accepted)
                },
            )
        }
    }
    val select: (Trip) -> Unit = { trip -> selectedId = if (selectedId == trip.id) null else trip.id }
    val manual: @Composable () -> Unit = {
        ManualTripForm(
            gap = fillGap,
            lastEndOdoKm = trips.maxOfOrNull { it.endOdoKm },
            odometerKm = odometerKm,
            onAdd = { input ->
                adding = false
                fillGap = null
                actions.addManual(input)
            },
            onCancel = {
                adding = false
                fillGap = null
            },
        )
    }
    val listTitle: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "운행 기록", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 목록의 운행을 누르면 카드에서 고칠 수 있다는 것을 알린다.
                Text(
                    text = "운행을 누르면 고칠 수 있습니다",
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!adding) {
                if (restorable.isNotEmpty()) {
                    QuietButton(text = if (showDeleted) "지운 운행 닫기" else "지운 운행 ${restorable.size}건", onClick = { showDeleted = !showDeleted })
                }
                QuietButton(text = "운행 추가", onClick = {
                    fillGap = null
                    adding = true
                })
            }
        }
    }

    if (wide) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(leftScroll),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (adding) manual()
                if (showDeleted && restorable.isNotEmpty()) DeletedPanel(trips = restorable, onRestore = actions.restore)
                done()
                gap()
                pending()
                card()
                MonthSummary(trips = trips, nowMs = nowMs, minTripKm = minKm)
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                listTitle()
                Spacer(Modifier.height(12.dp))
                // 새 운행이 맨 위에 들어오면 목록을 처음으로 돌려 바로 보이게 한다.
                val listState = rememberLazyListState()
                LaunchedEffect(trips.firstOrNull()?.id) { listState.scrollToItem(0) }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(trips, key = { it.id }) { trip ->
                        TripRow(trip = trip, suggestion = suggestions[trip.id], short = Ledger.isShortMove(trip, minKm), selected = trip.id == target?.id, onClick = {
                            select(trip)
                            // 왼쪽 열이 내려가 있으면 카드가 안 보인다. 고른 운행의 카드가 보이게 처음으로 돌린다.
                            scope.launch { leftScroll.scrollTo(0) }
                        })
                    }
                }
            }
        }
    } else {
        val listState = rememberLazyListState()
        LaunchedEffect(trips.firstOrNull()?.id) { listState.scrollToItem(0) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (adding) item { manual() }
            if (showDeleted && restorable.isNotEmpty()) item { DeletedPanel(trips = restorable, onRestore = actions.restore) }
            if (lastDone != null) item { done() }
            if (showGap) item { gap() }
            if (showPending) item { pending() }
            item { card() }
            item { MonthSummary(trips = trips, nowMs = nowMs, minTripKm = minKm) }
            item { listTitle() }
            // 카드는 목록 위쪽에 있다. 아래쪽 운행을 누르면 카드가 보이는 자리로 올린다.
            val cardIndex = listOf(adding, showDeleted && restorable.isNotEmpty(), lastDone != null, showGap, showPending).count { it }
            items(trips, key = { it.id }) { trip ->
                TripRow(trip = trip, suggestion = suggestions[trip.id], short = Ledger.isShortMove(trip, minKm), selected = trip.id == target?.id, onClick = {
                    select(trip)
                    scope.launch { listState.scrollToItem(cardIndex) }
                })
            }
        }
    }
}

/**
 * 용무 카드. 이 카드에서 버튼 하나로 운행의 용무를 남긴다. 목록에서 고른 운행을 고칠 때도 쓴다.
 * 기어를 P에 두면 운행이 닫히기 전에 [parked] 카드가 먼저 뜬다. 여기서 고른 용무는 문을 열 때 그대로 기록된다.
 */
@Composable
private fun TripCard(
    trip: Trip?,
    raw: Trip?,
    /** 계기판 거리가 바로 앞에서 이어지는 운행(원래 기록). 합칠 수 있는 상대다 */
    previous: Trip?,
    /** 이 운행이 합치거나 나눠서 생긴 것이고, 되돌릴 수 있는가 */
    undoable: Boolean,
    places: List<SavedPlace>,
    suggestion: Suggestion?,
    /** 정차 대기 중이라 아직 닫히지 않은 운행인가 */
    parked: Boolean,
    /** 짧은 이동이면 그 기준 거리(km). 아니면 null */
    shortUnderKm: Int?,
    drivers: List<Driver>,
    memo: VoiceMemo.State,
    actions: TripActions,
    /** 전에 적은 메모. 눌러서 다시 쓴다 */
    recentNotes: List<String>,
    /** 목록에서 고른 운행을 보고 있을 때 카드를 닫는다. 정차 직후 뜬 카드에는 없다 */
    onClose: (() -> Unit)?,
    /** 용무를 눌렀을 때. 누르기 전의 운행과 새 용무를 준다 */
    onClassified: (Trip, Purpose) -> Unit,
) {
    val focus = LocalFocusManager.current
    val label = LocalPurposeLabel.current
    Panel {
        if (trip == null) {
            Text(text = "정리할 운행이 없습니다", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(text = "운행이 끝나면 여기에 카드가 뜹니다", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Panel
        }
        val route = placeLine(trip.startPlace, trip.endPlace)
        if (route.isNotEmpty() || onClose != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = route,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) QuietButton(text = "닫기", onClick = onClose)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = "${km(trip.distanceKm)}km", fontSize = 76.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(20.dp))
            Text(
                text = when {
                    parked && trip.purpose == Purpose.UNCLASSIFIED -> "정차 중 · 용무는?"
                    parked -> "정차 중 · ${label(trip.purpose)}"
                    shortUnderKm != null -> "짧은 이동"
                    trip.purpose == Purpose.UNCLASSIFIED -> "운행 · 용무는?"
                    else -> "운행 · ${label(trip.purpose)}"
                },
                fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (trip.source == Trip.SOURCE_MANUAL) "${tripDay(trip.startAtMs)} · 직접 추가" else tripWhen(trip.startAtMs, trip.endAtMs),
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "계기판 ${km(trip.startOdoKm)} → ${km(trip.endOdoKm)}km" + when (trip.source) {
                Trip.SOURCE_RECOVERED -> " · 앱 재시작 후 이어서 기록"
                Trip.SOURCE_MERGED -> " · 합친 운행"
                Trip.SOURCE_SPLIT -> " · 나눈 운행"
                else -> ""
            },
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (drivers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "운전자", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                drivers.take(4).forEach { driver ->
                    Pill(
                        text = driver.name,
                        selected = trip.driverName == driver.name && trip.driverDept == driver.dept,
                        onClick = { actions.setDriver(trip.id, driver) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        // 정차 중인 운행과 짧은 이동은 지금 무엇을 할 수 있는지 한 줄로 알린다.
        val hint = when {
            parked && trip.purpose == Purpose.UNCLASSIFIED -> "지금 골라 두면 문을 열 때 그대로 기록됩니다"
            parked -> "문을 열면 이 용무로 기록됩니다"
            shortUnderKm != null -> "${shortUnderKm}km보다 짧아 용무를 묻지 않았습니다. 골라 두면 업무 거리에 들어갑니다"
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (parked) 1 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        }
        // 정차 중인 카드는 용무를 고르기 전과 뒤의 줄 수를 같게 둔다. 누르는 순간 버튼 자리가 움직이지 않게 한다.
        if (parked && suggestion == null) {
            Text(
                text = if (trip.purpose == Purpose.UNCLASSIFIED) "다시 출발하면 다시 묻습니다" else "한 번 더 누르면 풀립니다. 다시 출발하면 다시 묻습니다",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (suggestion != null) {
            Text(
                text = when (suggestion.basis) {
                    Suggestion.Basis.HOME_WORK -> "집과 회사 사이 운행이라 '${label(suggestion.purpose)}'${directionParticle(label(suggestion.purpose))} 제안합니다"
                    Suggestion.Basis.ROUTE -> "전에 이 구간은 '${label(suggestion.purpose)}'${directionParticle(label(suggestion.purpose))} 분류했습니다"
                    Suggestion.Basis.DESTINATION -> "전에 이 도착지는 '${label(suggestion.purpose)}'${directionParticle(label(suggestion.purpose))} 분류했습니다"
                },
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
        Purpose.choices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { purpose ->
                    // 고른 용무와 제안한 용무는 진하게 보여 준다. 제안은 눌러야 확정된다.
                    val chosen = trip.purpose == purpose || suggestion?.purpose == purpose
                    Button(
                        onClick = {
                            focus.clearFocus()
                            onClassified(trip, purpose)
                            // 정차 중에 고른 용무는 한 번 더 누르면 푼다.
                            actions.classify(trip.id, if (parked && trip.purpose == purpose) Purpose.UNCLASSIFIED else purpose)
                        },
                        modifier = Modifier.weight(1f).height(112.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = if (chosen) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Text(text = label(purpose), fontSize = 36.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        MemoRow(trip = trip, memo = memo, recentNotes = recentNotes, actions = actions)
        // 아직 닫히지 않은 운행은 장소 이름, 합치기·나누기, 삭제를 할 수 없다.
        if (parked) return@Panel
        Spacer(Modifier.height(16.dp))
        var confirmDelete by remember(trip.id) { mutableStateOf(false) }
        var naming by remember(trip.id) { mutableStateOf(false) }
        var editing by remember(trip.id) { mutableStateOf(false) }
        val canName = raw != null && (raw.startPlace.isNotBlank() || raw.endPlace.isNotBlank() || raw.startLat != null || raw.endLat != null)
        if (naming && raw != null) {
            Spacer(Modifier.height(8.dp))
            PlaceNamer(raw = raw, places = places, actions = actions)
            Spacer(Modifier.height(8.dp))
        }
        if (editing && raw != null) {
            Spacer(Modifier.height(8.dp))
            TripEditor(raw = raw, previous = previous, undoable = undoable, actions = actions, onDone = { editing = false })
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (confirmDelete) "지워도 '지운 운행'에서 되살릴 수 있습니다" else "",
                fontSize = 26.sp,
                lineHeight = 34.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (canName && !confirmDelete) {
                QuietButton(text = if (naming) "장소 이름 닫기" else "장소 이름", onClick = {
                    naming = !naming
                    editing = false
                })
            }
            if (raw != null && !confirmDelete) {
                QuietButton(text = if (editing) "합치기·나누기 닫기" else "합치기·나누기", onClick = {
                    editing = !editing
                    naming = false
                })
            }
            if (confirmDelete) {
                QuietButton(text = "취소", onClick = { confirmDelete = false })
            }
            QuietButton(
                text = if (confirmDelete) "삭제 확인" else "이 운행 삭제",
                onClick = { if (confirmDelete) actions.delete(trip.id) else confirmDelete = true },
            )
        }
    }
}

/**
 * 장소에 이름 붙이기. 차량이 주는 주소 대신 '집', '회사' 같은 이름으로 읽게 한다.
 * 이름을 붙이면 그 자리에서 출발하거나 정차한 다른 운행도 같은 이름으로 보인다.
 */
@Composable
private fun PlaceNamer(raw: Trip, places: List<SavedPlace>, actions: TripActions) {
    val start = PlaceBook.startOf(raw)
    val end = PlaceBook.endOf(raw)
    val sameSpot = start.raw == end.raw && end.byCoordinates
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (start.raw.isNotBlank() || start.latitude != null) {
            PlaceNameLine(title = if (sameSpot) "출발지·도착지" else "출발지", raw = raw, start = true, endpoint = start, places = places, actions = actions)
        }
        if (!sameSpot && (end.raw.isNotBlank() || end.latitude != null)) {
            PlaceNameLine(title = "도착지", raw = raw, start = false, endpoint = end, places = places, actions = actions)
        }
    }
}

@Composable
private fun PlaceNameLine(title: String, raw: Trip, start: Boolean, endpoint: PlaceBook.Endpoint, places: List<SavedPlace>, actions: TripActions) {
    val saved = PlaceBook.find(endpoint, places)
    var typing by remember(raw.id, start) { mutableStateOf(false) }
    var custom by remember(raw.id, start) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = when {
                        saved != null && endpoint.raw.isNotBlank() && saved.name != endpoint.raw -> "${saved.name} (${endpoint.raw})"
                        saved != null -> saved.name
                        else -> endpoint.raw.ifBlank { "주소 없음" }
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Pill(text = PlaceBook.HOME, selected = saved?.name == PlaceBook.HOME, onClick = { actions.namePlace(raw, start, PlaceBook.HOME) })
            Pill(text = PlaceBook.WORK, selected = saved?.name == PlaceBook.WORK, onClick = { actions.namePlace(raw, start, PlaceBook.WORK) })
            Pill(text = "직접", selected = typing, onClick = { typing = !typing })
        }
        if (typing) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(PLACE_NAME_MAX) },
                    label = { Text(text = "장소 이름", fontSize = 24.sp) },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 30.sp),
                    modifier = Modifier.weight(1f).inputField(),
                )
                Button(
                    onClick = {
                        actions.namePlace(raw, start, custom)
                        custom = ""
                        typing = false
                        focus.clearFocus()
                    },
                    enabled = custom.isNotBlank(),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(84.dp),
                ) {
                    Text(text = "붙이기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (saved != null) {
            Spacer(Modifier.height(8.dp))
            QuietButton(text = "'${saved.name}' 이름 떼기", onClick = { actions.unnamePlace(raw, start) })
        }
    }
}

/**
 * 운행 합치기·나누기.
 * 합치기는 계기판 거리가 바로 이어지는 앞 운행이 있을 때만 된다. 나누기는 주행 전·후 계기판 거리 사이의 값을 적는다.
 * 원래 운행은 지운 표시로 남고 새 운행이 그 자리를 대신한다. 그래서 합치거나 나눈 운행은 되돌릴 수 있다.
 */
@Composable
private fun TripEditor(raw: Trip, previous: Trip?, undoable: Boolean, actions: TripActions, onDone: () -> Unit) {
    var mid by remember(raw.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val midKm = mid.trim().toFloatOrNull()
    val canSplit = midKm != null && TripEdits.canSplit(raw, midKm)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (undoable) {
            val merged = raw.source == Trip.SOURCE_MERGED
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (merged) "두 운행을 합친 운행입니다. 되돌리면 원래 두 건으로 돌아갑니다" else "한 운행을 나눈 운행입니다. 되돌리면 원래 한 건으로 돌아갑니다",
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        onDone()
                        actions.undoEdit(raw)
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(84.dp),
                ) {
                    Text(text = if (merged) "합치기 되돌리기" else "나누기 되돌리기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (previous != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "앞 운행 ${km(previous.distanceKm)}km와 이어집니다. 합치면 ${km(raw.endOdoKm - previous.startOdoKm)}km 한 건이 됩니다",
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalButton(
                    onClick = {
                        onDone()
                        actions.merge(previous, raw)
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.height(84.dp),
                ) {
                    Text(text = "앞 운행과 합치기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        } else {
            Text(text = "같은 날 계기판 거리가 이어지는 앞 운행이 없어 합칠 수 없습니다", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = mid,
                onValueChange = { mid = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                label = { Text(text = "나눌 지점의 계기판 거리(km)", fontSize = 24.sp) },
                placeholder = { Text(text = "${km(raw.startOdoKm)}와 ${km(raw.endOdoKm)} 사이", fontSize = 26.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 30.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).inputField(),
            )
            FilledTonalButton(
                onClick = {
                    focus.clearFocus()
                    onDone()
                    if (midKm != null) actions.split(raw, midKm)
                },
                enabled = canSplit,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(84.dp),
            ) {
                Text(text = "나누기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** 지운 운행. 잘못 지운 운행을 되살린다. 되살리면 운행 기록과 운행기록부에 다시 들어간다. */
@Composable
private fun DeletedPanel(trips: List<Trip>, onRestore: (Long) -> Unit) {
    Panel {
        Text(text = "지운 운행", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text = "되살리면 운행 기록과 운행기록부에 다시 들어갑니다", fontSize = 26.sp, lineHeight = 36.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        trips.take(DELETED_SHOWN).forEach { trip ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "${tripDay(trip.startAtMs)} · ${km(trip.distanceKm)}km", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        text = placeLine(trip.startPlace, trip.endPlace).ifEmpty { "계기판 ${km(trip.startOdoKm)} → ${km(trip.endOdoKm)}km" },
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilledTonalButton(onClick = { onRestore(trip.id) }, shape = RoundedCornerShape(50), modifier = Modifier.height(72.dp)) {
                    Text(text = "되살리기", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (trips.size > DELETED_SHOWN) {
            Text(text = "그 밖에 ${trips.size - DELETED_SHOWN}건", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 손으로 추가하는 운행의 입력값. */
data class ManualTripInput(
    val daysAgo: Int,
    val startOdoKm: Float,
    val endOdoKm: Float,
    val purpose: Purpose,
    /** 두 운행 사이의 빈 구간을 채울 때 운행을 넣을 시각. null이면 [daysAgo]로 고른 날의 정오 */
    val atMs: Long? = null,
)

/**
 * 운행 추가. 자동으로 기록되지 않은 운행을 계기판 거리로 적는다.
 * 주행 전 거리는 마지막 기록의 주행 후 거리로, 주행 후 거리는 지금 계기판 거리로 미리 채워 둔다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualTripForm(gap: Ledger.Gap?, lastEndOdoKm: Float?, odometerKm: Float?, onAdd: (ManualTripInput) -> Unit, onCancel: () -> Unit) {
    // 두 운행 사이의 빈 구간이면 앞 운행 바로 뒤에 넣는다. 날짜를 고를 필요가 없다.
    val between = gap?.takeIf { it.beforeMs != null }
    var daysAgo by remember { mutableStateOf(0) }
    var start by remember(gap) { mutableStateOf((gap?.fromOdoKm ?: lastEndOdoKm)?.let { Logbook.number(it) }.orEmpty()) }
    var end by remember(gap) { mutableStateOf((gap?.toOdoKm ?: odometerKm)?.let { Logbook.number(it) }.orEmpty()) }
    var purpose by remember { mutableStateOf(Purpose.UNCLASSIFIED) }
    val focus = LocalFocusManager.current
    val label = LocalPurposeLabel.current
    val startKm = start.trim().toFloatOrNull()
    val endKm = end.trim().toFloatOrNull()
    val distance = if (startKm != null && endKm != null) endKm - startKm else null
    val valid = distance != null && distance > 0f && distance <= MANUAL_MAX_KM

    Panel {
        Text(text = if (gap != null) "빈 구간 채우기" else "운행 추가", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                between != null -> "${tripDay(between.afterMs)} 운행 뒤에 기록되지 않은 구간입니다. 그 운행 바로 뒤에 한 줄로 들어갑니다"
                gap != null -> "마지막 기록 뒤로 계기판이 ${km(gap.km)}km 늘었습니다. 운행 한 건으로 적어 둡니다"
                else -> "자동으로 기록되지 않은 운행을 계기판 거리로 적습니다"
            },
            fontSize = 26.sp,
            lineHeight = 36.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        if (between == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "날짜", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                listOf("오늘", "어제", "그제").forEachIndexed { index, label ->
                    Pill(text = label, selected = daysAgo == index, onClick = { daysAgo = index })
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = start,
                onValueChange = { start = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                label = { Text(text = "주행 전 계기판(km)", fontSize = 24.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 32.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).inputField(),
            )
            OutlinedTextField(
                value = end,
                onValueChange = { end = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                label = { Text(text = "주행 후 계기판(km)", fontSize = 24.sp) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 32.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                modifier = Modifier.weight(1f).inputField(),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = when {
                distance == null -> "계기판 거리를 숫자로 적어 주세요"
                distance <= 0f -> "주행 후 거리가 주행 전 거리보다 커야 합니다"
                distance > MANUAL_MAX_KM -> "거리가 너무 깁니다. 숫자를 확인해 주세요"
                else -> "주행거리 ${km(distance)}km"
            },
            fontSize = 28.sp,
            fontWeight = if (valid) FontWeight.SemiBold else FontWeight.Normal,
            color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Purpose.choices.forEach { choice ->
                Pill(text = label(choice), selected = purpose == choice, onClick = { purpose = if (purpose == choice) Purpose.UNCLASSIFIED else choice })
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = {
                    focus.clearFocus()
                    if (startKm != null && endKm != null) {
                        onAdd(ManualTripInput(daysAgo, startKm, endKm, purpose, atMs = between?.let { it.afterMs + GAP_FILL_AFTER_MS }))
                    }
                },
                enabled = valid,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(84.dp),
            ) {
                Text(text = "추가", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
            }
            FilledTonalButton(
                onClick = {
                    focus.clearFocus()
                    onCancel()
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(84.dp),
            ) {
                Text(text = "취소", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

/** 비고 메모. 한 마디를 말하거나 글자로 적는다. 서식의 비고 칸에 용무·도착지 뒤로 붙는다. */
@Composable
private fun MemoRow(trip: Trip, memo: VoiceMemo.State, recentNotes: List<String>, actions: TripActions) {
    val listening = memo is VoiceMemo.State.Listening && memo.tripId == trip.id
    var draft by remember(trip.id, trip.note) { mutableStateOf(trip.note) }
    val changed = draft.trim() != trip.note
    val focus = LocalFocusManager.current
    var hadFocus by remember(trip.id) { mutableStateOf(false) }
    // 완료를 누르면 입력을 마친 것으로 보고 키보드를 내린다. 저장은 칸을 벗어날 때 한다.
    val save = { focus.clearFocus() }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = if (listening) (memo as VoiceMemo.State.Listening).heard else draft,
            onValueChange = { draft = it.take(MEMO_MAX) },
            enabled = !listening,
            label = { Text(text = if (listening) "듣는 중입니다. 말씀해 주세요" else "비고 메모", fontSize = 24.sp) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 30.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            // 따로 저장을 누르지 않아도, 다른 곳을 누르거나 용무를 골라 칸을 벗어나면 적은 메모를 남긴다.
            modifier = Modifier.weight(1f).inputField().onFocusChanged { state ->
                if (hadFocus && !state.isFocused && changed) actions.setNote(trip.id, draft.trim())
                hadFocus = state.isFocused
            },
        )
        Button(
            onClick = {
                focus.clearFocus()
                if (listening) actions.stopListening() else actions.listen(trip.id)
            },
            shape = RoundedCornerShape(50),
            colors = if (listening) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.height(84.dp),
        ) {
            Text(text = if (listening) "그만 듣기" else "음성 메모", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    // 전에 적은 메모를 눌러 다시 쓴다. 차 안에서는 글자를 치는 것보다 고르는 편이 쉽다.
    if (!listening && trip.note.isBlank() && draft.isBlank() && recentNotes.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "최근 메모", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            recentNotes.forEach { note ->
                QuietButton(text = note, onClick = { actions.setNote(trip.id, note) }, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
    if (memo is VoiceMemo.State.Failed && memo.tripId == trip.id) {
        Spacer(Modifier.height(8.dp))
        Text(text = memo.reason, fontSize = 26.sp, color = MaterialTheme.colorScheme.error)
    }
}

/**
 * 기록이 빈 구간. 앞 운행이 끝난 계기판 거리와 다음 운행이 시작한 계기판 거리가 벌어져 있으면 카드 위에 보인다.
 * 앱이 꺼져 있던 동안의 운행처럼 차량 신호로 잡지 못한 구간을 사용자가 찾지 않아도 되게 알려 주고, 그 자리를 운행 한 건으로 채우게 한다.
 */
@Composable
private fun GapBar(gaps: List<Ledger.Gap>, onFill: () -> Unit) {
    val gap = gaps.first()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "기록이 빈 구간 ${km(gap.km)}km", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = listOfNotNull(
                        (if (gap.beforeMs == null) "마지막 기록 뒤" else "${tripDay(gap.afterMs)} 운행 뒤") +
                            " · 계기판 ${km(gap.fromOdoKm)} → ${km(gap.toOdoKm)}km",
                        if (gaps.size > 1) "그 밖에 ${gaps.size - 1}곳" else null,
                    ).joinToString(" · "),
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onFill, shape = RoundedCornerShape(50), modifier = Modifier.height(84.dp)) {
                Text(text = "운행으로 채우기", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/**
 * 정리할 운행. 분류하지 않은 운행이 둘 이상 쌓였거나, 달이 끝나 가는데 이번 달 미분류가 남았을 때 카드 위에 보인다.
 * 지난 분류에서 찾은 제안을 한 번에 받아들인다. 운행마다 어떤 용무를 제안했는지는 운행 기록 목록에 적혀 있다.
 */
@Composable
private fun PendingBar(count: Int, suggested: Int, notice: String?, onAcceptAll: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "정리할 운행 ${count}건", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = listOfNotNull(
                        notice,
                        if (suggested > 0) "지난 분류대로 ${suggested}건을 제안했습니다" else "목록에서 운행을 눌러 용무를 골라 주세요",
                    ).joinToString(". "),
                    fontSize = 26.sp,
                    lineHeight = 34.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (suggested > 0) {
                Button(onClick = onAcceptAll, shape = RoundedCornerShape(50), modifier = Modifier.height(84.dp)) {
                    Text(text = "제안 ${suggested}건 모두 수락", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** 방금 정리한 운행들의 정리하기 전 용무와 정리한 뒤 용무. */
private class DoneChange(val before: Map<Long, Purpose>, val after: Map<Long, Purpose>)

/**
 * 방금 정리한 운행. 무엇으로 기록했는지 알려 주고, 잘못 눌렀으면 한 번에 되돌린다. 잠시 뒤 사라진다.
 * 용무를 누르면 카드가 바로 닫히기 때문에, 이것이 없으면 잘못 누른 것을 알아채기 어렵다.
 */
@Composable
private fun DoneBar(change: DoneChange, trips: List<Trip>, onUndo: () -> Unit) {
    val label = LocalPurposeLabel.current
    val one = trips.singleOrNull()
    val purpose = one?.let { change.after[it.id] }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 36.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (purpose != null) "'${label(purpose)}'${directionParticle(label(purpose))} 기록했습니다" else "${change.after.size}건을 제안대로 정리했습니다",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (one != null) {
                        listOf(placeLine(one.startPlace, one.endPlace), "${km(one.distanceKm)}km").filter { it.isNotBlank() }.joinToString(" · ")
                    } else {
                        "잘못 정리했으면 되돌릴 수 있습니다"
                    },
                    fontSize = 26.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QuietButton(text = "되돌리기", onClick = onUndo)
        }
    }
}

/** 이번 달 요약. 운행기록부 서식의 총주행거리, 업무용 사용거리, 업무사용비율에 해당한다. */
@Composable
private fun MonthSummary(trips: List<Trip>, nowMs: Long, minTripKm: Float) {
    val now = at(nowMs)
    val month = Logbook.monthTrips(trips, now.year, now.monthValue)
    val totals = Logbook.totals(month, minTripKm)
    val private = month.filter { it.purpose == Purpose.PRIVATE }.sumOf { it.distanceKm.toDouble() }.toFloat()

    Panel {
        Text(text = "${now.monthValue}월 요약", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = percent(totals.businessKm, totals.totalKm), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text(text = "업무사용비율", fontSize = 30.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Spacer(Modifier.height(16.dp))
        SummaryLine("총 주행", "${km(totals.totalKm)}km")
        SummaryLine("업무 사용", "${km(totals.businessKm)}km")
        SummaryLine("사적 사용", "${km(private)}km")
        SummaryLine("미분류", "${totals.unclassifiedCount}건")
        // 짧은 이동은 용무를 묻지 않지만 총 주행에는 들어 있다.
        if (totals.shortCount > 0) SummaryLine("짧은 이동", "${totals.shortCount}건 · ${km(totals.shortKm)}km")
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = label, fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TripRow(trip: Trip, suggestion: Suggestion?, short: Boolean, selected: Boolean, onClick: () -> Unit) {
    // 짧은 이동은 용무를 묻지 않으므로 정리할 운행처럼 진하게 표시하지 않는다.
    val pending = trip.purpose == Purpose.UNCLASSIFIED && !short
    val label = LocalPurposeLabel.current
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = listOf(tripDay(trip.startAtMs), trip.driverName).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(
                        if (trip.source == Trip.SOURCE_MANUAL) "직접 추가" else tripClock(trip.startAtMs, trip.endAtMs),
                        placeLine(trip.startPlace, trip.endPlace).ifEmpty { "${km(trip.startOdoKm)} → ${km(trip.endOdoKm)}km" },
                        trip.note,
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 24.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(text = "${km(trip.distanceKm)}km", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(24.dp))
            Surface(
                color = if (pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (pending) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    // 아직 분류하지 않은 운행에 제안이 있으면 무엇을 제안했는지 보여 준다.
                    text = when {
                        short -> "짧은 이동"
                        pending && suggestion != null -> "제안: ${label(suggestion.purpose)}"
                        else -> label(trip.purpose)
                    },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 방금 정리한 운행을 보여 주는 시간(ms). 잘못 누른 것을 알아채고 되돌릴 만큼은 남겨 둔다. */
private const val DONE_SHOWN_MS = 20_000L

/** 빈 구간을 알리기 전에 기다리는 시간(ms). 닫힌 운행이 목록에 들어오는 틈에 잠깐 벌어져 보이는 것을 거른다. */
private const val GAP_SETTLE_MS = 1_500L

/** 두 운행 사이의 빈 구간을 채운 운행은 앞 운행이 끝나고 이만큼 뒤로 적는다(ms). 목록에서 앞 운행 바로 다음에 놓인다. */
private const val GAP_FILL_AFTER_MS = 60_000L

/** 카드에 보여 주는 최근 메모 개수. */
private const val RECENT_NOTES = 3

/** 메모 길이 제한. 서식의 비고 칸에 들어갈 한 마디면 충분하다. */
private const val MEMO_MAX = 60

private const val PLACE_NAME_MAX = 20

/** 지운 운행 패널에 한 번에 보여 주는 건수. */
private const val DELETED_SHOWN = 8

/** 손으로 추가하는 운행 한 건의 거리 상한(km). 숫자를 잘못 적은 경우를 걸러 낸다. */
private const val MANUAL_MAX_KM = 2_000f
