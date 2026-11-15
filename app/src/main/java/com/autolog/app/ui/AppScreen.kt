package com.autolog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autolog.app.data.Driver
import com.autolog.app.data.Profile
import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import com.autolog.app.place.SavedPlace
import com.autolog.app.share.PhoneShare
import com.autolog.app.trip.DetectorState
import com.autolog.app.trip.RecorderStatus
import com.autolog.app.vehicle.VoiceMemo

enum class AppTab(val label: String) { HOME("운행"), REPORT("운행기록부"), SETTINGS("설정") }

/** 운행 한 건에 대해 화면이 부르는 동작. */
class TripActions(
    val classify: (Long, Purpose) -> Unit,
    /** 여러 운행의 용무를 한 번에 정한다. */
    val classifyAll: (Map<Long, Purpose>) -> Unit,
    val setDriver: (Long, Driver) -> Unit,
    val setNote: (Long, String) -> Unit,
    val delete: (Long) -> Unit,
    /** 음성 메모 듣기를 시작한다. */
    val listen: (Long) -> Unit,
    /** 듣기를 마치고 알아들은 말을 메모로 남긴다. */
    val stopListening: () -> Unit,
    /** 듣기를 그만두고 알아들은 말도 버린다. */
    val cancelListening: () -> Unit,
    /** 운행(원래 기록)의 출발지(true) 또는 도착지(false)에 이름을 붙인다. */
    val namePlace: (Trip, Boolean, String) -> Unit,
    val unnamePlace: (Trip, Boolean) -> Unit,
    /** 손으로 적은 운행을 추가한다. */
    val addManual: (ManualTripInput) -> Unit,
    /** 앞 운행과 뒤 운행(원래 기록)을 하나로 합친다. */
    val merge: (Trip, Trip) -> Unit,
    /** 운행(원래 기록)을 중간 계기판 거리에서 둘로 나눈다. */
    val split: (Trip, Float) -> Unit,
    /** 합치거나 나눈 운행(원래 기록)을 그 전으로 되돌린다. */
    val undoEdit: (Trip) -> Unit,
    /** 지운 운행을 되살린다. */
    val restore: (Long) -> Unit,
)

/** 이 폭부터 두 열로 놓는다. Pleos Connect의 앱 영역 2개에 해당한다. */
private val WIDE_FROM = 1100.dp

/**
 * 앱 화면의 뼈대. 맨 위에 상태 배너를 두고, 정차 중일 때만 탭과 내용을 보여 준다.
 * 주행 중에는 상태 한 줄만 남는다. 기록 항목에 동의하기 전에는 안내 화면만 보인다.
 */
@Composable
fun AppScreen(
    status: RecorderStatus,
    /** 출발지·도착지를 저장한 장소 이름으로 바꿔 읽은 운행 */
    trips: List<Trip>,
    /** 원래 기록 그대로의 운행 */
    rawTrips: List<Trip>,
    /** 정차 대기 중인 운행. 저장한 장소 이름과 지금 운전자로 바꿔 읽은 것 */
    parked: Trip?,
    /** 합치기나 나누기를 되돌릴 수 있는 운행의 id */
    undoable: Set<Long>,
    /** 되살릴 수 있는 지운 운행 */
    restorable: List<Trip>,
    places: List<SavedPlace>,
    restricted: Boolean,
    profile: Profile,
    nowMs: Long,
    memo: VoiceMemo.State,
    actions: TripActions,
    onProfileChange: ((Profile) -> Profile) -> Unit,
    onExport: (Int, Int, (String, Boolean) -> Unit) -> Unit,
    /** 폰으로 받기가 열려 있으면 그 상태 */
    share: PhoneShare.State?,
    onShare: (Int, Int) -> Unit,
    onCloseShare: () -> Unit,
    onConsent: () -> Unit,
    onRemovePlace: (SavedPlace) -> Unit,
    onWipe: () -> Unit,
) {
    val driving = restricted || status.state is DetectorState.Driving
    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }

    // 주행이 시작되면 듣던 음성 메모를 그만둔다.
    LaunchedEffect(driving) { if (driving) actions.cancelListening() }
    // 주행이 시작되면 폰으로 받기도 닫는다.
    LaunchedEffect(driving) { if (driving) onCloseShare() }
    // 빈 구간을 찾는 기준. 운행이 열려 있으면 그 운행의 출발 거리까지만 기록이 이어져 있으면 된다.
    val uptoOdoKm = when (val state = status.state) {
        DetectorState.Idle -> status.odometerKm
        is DetectorState.Driving -> state.startOdoKm
        is DetectorState.ParkedPending -> state.startOdoKm
    }

    // 설정에서 바꾼 용무 버튼 이름을 화면 전체에 내려 준다.
    val labelOf: (Purpose) -> String = remember(profile.purposeLabels) { { purpose -> profile.labelOf(purpose) } }

    CompositionLocalProvider(LocalPurposeLabel provides labelOf) {
    Surface(modifier = Modifier.fillMaxSize().dismissKeyboardOnOutsideTap(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE_FROM
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = if (wide) 48.dp else 24.dp, vertical = if (wide) 36.dp else 24.dp)) {
                if (!profile.consented) {
                    if (driving) {
                        StatusBanner(status = status, driving = true, stacked = !wide, parkTimeoutMin = profile.parkTimeoutMin, modifier = Modifier.fillMaxWidth())
                    } else {
                        ConsentContent(
                            wide = wide,
                            onAgree = {
                                // 차량 정보가 비어 있으면 동의한 뒤 바로 설정에서 적게 한다.
                                if (profile.vehicleModel.isBlank() && profile.plateNo.isBlank()) tab = AppTab.SETTINGS
                                onConsent()
                            },
                        )
                    }
                    return@Column
                }

                if (wide) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusBanner(status = status, driving = driving, stacked = false, parkTimeoutMin = profile.parkTimeoutMin, modifier = Modifier.weight(1f))
                        if (!driving) Tabs(tab = tab, onSelect = { tab = it })
                    }
                } else {
                    StatusBanner(status = status, driving = driving, stacked = true, parkTimeoutMin = profile.parkTimeoutMin, modifier = Modifier.fillMaxWidth())
                    if (!driving) {
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Tabs(tab = tab, onSelect = { tab = it }) }
                    }
                }
                if (!driving) {
                    Spacer(Modifier.height(if (wide) 28.dp else 20.dp))
                    when (tab) {
                        AppTab.HOME -> HomeContent(
                            trips = trips, rawTrips = rawTrips, undoable = undoable, restorable = restorable, places = places, drivers = profile.drivers,
                            parked = parked, minTripKm = profile.minTripKm,
                            odometerKm = status.odometerKm, uptoOdoKm = uptoOdoKm,
                            nowMs = nowMs, wide = wide, memo = memo, actions = actions,
                        )
                        AppTab.REPORT -> ReportContent(
                            trips = trips, profile = profile, uptoOdoKm = uptoOdoKm, nowMs = nowMs, wide = wide,
                            share = share, onExport = onExport, onShare = onShare, onCloseShare = onCloseShare,
                        )
                        AppTab.SETTINGS -> SettingsContent(
                            profile = profile, places = places, wide = wide,
                            onChange = onProfileChange, onRemovePlace = onRemovePlace, onWipe = onWipe,
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Tabs(tab: AppTab, onSelect: (AppTab) -> Unit) {
    AppTab.entries.forEach { item -> Pill(text = item.label, selected = item == tab, onClick = { onSelect(item) }) }
}

/** 상태 배너. [stacked]이면 제목과 설명을 두 줄로 쌓는다(좁은 창). */
@Composable
private fun StatusBanner(status: RecorderStatus, driving: Boolean, stacked: Boolean, parkTimeoutMin: Int, modifier: Modifier = Modifier) {
    val state = status.state
    val title: String
    val detail: String
    when (state) {
        is DetectorState.Driving -> {
            title = "운행 중"
            val start = state.startOdoKm
            val current = status.odometerKm
            detail = when {
                start == null -> "계기판 거리를 기다리는 중"
                current != null && current > start -> "${km(start)}km에서 출발 · 지금까지 ${km(current - start)}km"
                else -> "${km(start)}km에서 출발"
            }
        }
        is DetectorState.ParkedPending -> {
            title = "정차"
            detail = "운전석 문을 열거나 ${parkTimeoutMin}분이 지나면 운행을 마칩니다"
        }
        DetectorState.Idle -> {
            title = if (driving) "주행 중" else "대기 중"
            detail = status.odometerKm?.let { "계기판 ${km(it)}km" } ?: if (driving) "정차하면 화면이 다시 보입니다" else "차량 신호를 기다리는 중"
        }
    }
    val active = state !is DetectorState.Idle
    val container = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val content = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Surface(color = container, contentColor = content, shape = RoundedCornerShape(28.dp), modifier = modifier) {
        if (stacked) {
            Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(active)
                    Spacer(Modifier.width(16.dp))
                    Text(text = title, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Spacer(Modifier.height(6.dp))
                Text(text = detail, fontSize = 30.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(active)
                Spacer(Modifier.width(20.dp))
                Text(text = title, fontSize = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(28.dp))
                Text(text = detail, fontSize = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Dot(active: Boolean) {
    Box(
        modifier = Modifier.size(20.dp).clip(CircleShape)
            .background(if (active) Color(0xFF5BE38A) else MaterialTheme.colorScheme.outline)
    )
}
