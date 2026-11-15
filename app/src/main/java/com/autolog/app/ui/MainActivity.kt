package com.autolog.app.ui

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.autolog.app.AutoLogApp
import com.autolog.app.data.DemoData
import com.autolog.app.data.Trip
import com.autolog.app.place.PlaceBook
import com.autolog.app.trip.TripEdits
import com.autolog.app.trip.TripRecorder
import com.autolog.app.trip.TripRecorderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AutoLogApp
        // 화면이 뒤로 물러나도 운행 기록이 이어지도록 포그라운드 서비스를 띄운다. 동의한 뒤에만.
        if (app.profiles.profile.value.consented) TripRecorderService.start(this)

        // 시연 준비용: adb로 --ez seed true를 주면 예시 차량·운전자·운행을 넣는다. 디버그 빌드에서만, 비어 있을 때만.
        if (intent?.getBooleanExtra(EXTRA_SEED, false) == true && isDebuggable()) {
            lifecycleScope.launch(Dispatchers.IO) {
                DemoData.fillProfileIfEmpty(app.profiles)
                // 예시 운행이 현재 계기판 거리로 끝나도록, 계기판 값이 들어올 때까지 잠깐 기다린다.
                val odometer = withTimeoutOrNull(6_000) { app.recorder.status.first { it.odometerKm != null }.odometerKm }
                val seeded = if (odometer != null) DemoData.seedIfEmpty(app.trips, odometer) else DemoData.seedIfEmpty(app.trips)
                if (seeded) Log.i(TAG, "시연용 예시 운행을 넣음 (끝 계기판 ${odometer ?: 12_340f}km)")
                // 예시 운행의 '회사'가 지금 서 있는 자리가 되도록 장소를 하나 넣는다. 저장한 장소가 없을 때만.
                if (app.placeStore.places.value.isEmpty()) {
                    val spot = withTimeoutOrNull(4_000) {
                        var found = withContext(Dispatchers.Main) { app.navi.lastSpot }
                        while (found == null) {
                            delay(300)
                            found = withContext(Dispatchers.Main) { app.navi.lastSpot }
                        }
                        found
                    }
                    if (spot != null && spot.knownLatitude != null) {
                        val here = PlaceBook.Endpoint(spot.address, spot.knownLatitude, spot.knownLongitude, byCoordinates = true)
                        app.placeStore.update { PlaceBook.named(it, here, PlaceBook.WORK) }
                        Log.i(TAG, "시연용: 지금 자리(${spot.address})를 '회사'로 저장")
                    }
                }
            }
        }

        // 개발용: 월말 안내처럼 날짜에 따라 달라지는 화면을 보려고, 디버그 빌드에서는 --el now <밀리초>로 '지금'을 바꿀 수 있다.
        val pretendNow = intent?.getLongExtra(EXTRA_NOW, 0L)?.takeIf { it > 0L && isDebuggable() }

        setContent {
            AutoLogTheme {
                val status by app.recorder.status.collectAsStateWithLifecycle()
                val restricted by app.restriction.restricted.collectAsStateWithLifecycle()
                val profile by app.profiles.profile.collectAsStateWithLifecycle()
                val memo by app.voiceMemo.state.collectAsStateWithLifecycle()
                val places by app.placeStore.places.collectAsStateWithLifecycle()
                val share by app.phoneShare.state.collectAsStateWithLifecycle()
                val tripsFlow = remember { app.trips.observeAll() }
                val rawTrips by tripsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                // 화면과 운행기록부에는 저장한 장소 이름으로 바꿔 읽은 운행을 쓴다. 저장된 기록은 그대로 둔다.
                val trips = remember(rawTrips, places) { PlaceBook.resolve(rawTrips, places) }
                // 지운 표시가 있는 운행까지 보고, 합치기나 나누기를 되돌릴 수 있는 운행을 가린다.
                val everythingFlow = remember { app.trips.observeEverything() }
                val everything by everythingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                val undoable = remember(everything) {
                    everything.filter { !it.deleted && TripEdits.undoPlan(it, everything) != null }.map { it.id }.toSet()
                }
                val restorable = remember(everything, places) { PlaceBook.resolve(TripEdits.restorable(everything), places) }
                // 정차 대기 중인 운행. 닫히면 지금 고른 운전자로 적히므로 그 이름으로 보여 준다.
                val parked = remember(status.parked, places, profile.driver) {
                    status.parked?.let { open ->
                        val driver = profile.driver
                        PlaceBook.resolve(listOf(open.copy(driverName = driver?.name.orEmpty(), driverDept = driver?.dept.orEmpty())), places).first()
                    }
                }
                val scope = rememberCoroutineScope()
                val actions = remember(app, scope) {
                    // 정차 대기 중인 운행은 아직 저장되지 않았다. 고른 용무와 메모는 기록기에 맡겨 두고, 운행이 닫힐 때 함께 적는다.
                    val parkedId = TripRecorder.PARKED_ID
                    TripActions(
                        classify = { id, purpose ->
                            if (id == parkedId) app.recorder.setParkedPurpose(purpose)
                            else scope.launch(Dispatchers.IO) { app.trips.setPurpose(id, purpose) }
                        },
                        classifyAll = { purposes ->
                            scope.launch(Dispatchers.IO) { purposes.filterKeys { it != parkedId }.forEach { (id, purpose) -> app.trips.setPurpose(id, purpose) } }
                        },
                        setDriver = { id, driver ->
                            // 카드에서 운전자를 바꾸면 그 운행에 적고, 다음 운행의 기본 운전자로도 삼는다.
                            app.profiles.update { p -> p.copy(currentDriver = p.drivers.indexOf(driver).takeIf { it >= 0 } ?: p.currentDriver) }
                            if (id != parkedId) scope.launch(Dispatchers.IO) { app.trips.setDriver(id, driver.name, driver.dept) }
                        },
                        setNote = { id, note ->
                            if (id == parkedId) app.recorder.setParkedNote(note.take(NOTE_MAX))
                            else scope.launch(Dispatchers.IO) { app.trips.setNote(id, note) }
                        },
                        delete = { id -> if (id != parkedId) scope.launch(Dispatchers.IO) { app.trips.markDeleted(listOf(id), Trip.DELETED_BY_USER) } },
                        listen = { id ->
                            app.voiceMemo.listen(id) { text ->
                                if (id == parkedId) scope.launch { app.recorder.setParkedNote(text.take(NOTE_MAX)) }
                                else scope.launch(Dispatchers.IO) { app.trips.setNote(id, text.take(NOTE_MAX)) }
                            }
                        },
                        stopListening = { app.voiceMemo.stopListening() },
                        cancelListening = { app.voiceMemo.cancel() },
                        namePlace = { trip, start, name ->
                            val endpoint = if (start) PlaceBook.startOf(trip) else PlaceBook.endOf(trip)
                            app.placeStore.update { PlaceBook.named(it, endpoint, name) }
                        },
                        unnamePlace = { trip, start ->
                            val endpoint = if (start) PlaceBook.startOf(trip) else PlaceBook.endOf(trip)
                            app.placeStore.update { PlaceBook.unnamed(it, endpoint) }
                        },
                        merge = { first, second ->
                            // 합친 운행을 먼저 넣고 원래 두 건을 지운 표시로 돌린다. 중간에 끊겨도 기록이 사라지지 않는다.
                            scope.launch(Dispatchers.IO) {
                                app.trips.insert(TripEdits.merge(first, second))
                                app.trips.markDeleted(listOf(first.id, second.id), Trip.DELETED_BY_EDIT)
                                Log.i(TAG, "운행을 합침: ${first.startOdoKm} → ${second.endOdoKm}km")
                            }
                        },
                        split = { trip, midOdoKm ->
                            TripEdits.split(trip, midOdoKm)?.let { (first, second) ->
                                scope.launch(Dispatchers.IO) {
                                    app.trips.insert(first)
                                    app.trips.insert(second)
                                    app.trips.markDeleted(listOf(trip.id), Trip.DELETED_BY_EDIT)
                                    Log.i(TAG, "운행을 나눔: ${trip.startOdoKm} → $midOdoKm → ${trip.endOdoKm}km")
                                }
                            }
                        },
                        restore = { id ->
                            scope.launch(Dispatchers.IO) {
                                app.trips.restore(listOf(id))
                                Log.i(TAG, "지운 운행을 되살림: $id")
                            }
                        },
                        undoEdit = { trip ->
                            TripEdits.undoPlan(trip, everything)?.let { plan ->
                                // 원래 운행을 먼저 되살리고 나서 합치거나 나눈 운행을 지운 표시로 돌린다.
                                scope.launch(Dispatchers.IO) {
                                    app.trips.restore(plan.restoreIds)
                                    app.trips.markDeleted(plan.removeIds, Trip.DELETED_BY_UNDO)
                                    Log.i(TAG, "되돌림: 운행 ${plan.restoreIds}을 되살리고 ${plan.removeIds}을 지운 표시로")
                                }
                            }
                        },
                        addManual = { input ->
                            // 시각은 모르므로 그날 정오로 적는다. 화면에는 날짜와 '직접 추가'만 보인다.
                            // 두 운행 사이의 빈 구간을 채울 때는 앞 운행 바로 뒤로 적는다.
                            val noon = input.atMs ?: at(System.currentTimeMillis()).toLocalDate().minusDays(input.daysAgo.toLong())
                                .atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            val driver = app.profiles.profile.value.driver
                            val trip = Trip(
                                startAtMs = noon,
                                endAtMs = noon,
                                startOdoKm = input.startOdoKm,
                                endOdoKm = input.endOdoKm,
                                distanceKm = input.endOdoKm - input.startOdoKm,
                                purpose = input.purpose,
                                source = Trip.SOURCE_MANUAL,
                                driverName = driver?.name.orEmpty(),
                                driverDept = driver?.dept.orEmpty(),
                            )
                            scope.launch(Dispatchers.IO) {
                                app.trips.insert(trip)
                                Log.i(TAG, "운행을 손으로 추가: ${input.startOdoKm} → ${input.endOdoKm}km")
                            }
                        },
                    )
                }
                AppScreen(
                    status = status,
                    trips = trips,
                    rawTrips = rawTrips,
                    parked = parked,
                    undoable = undoable,
                    restorable = restorable,
                    places = places,
                    restricted = restricted,
                    profile = profile,
                    nowMs = pretendNow ?: System.currentTimeMillis(),
                    memo = memo,
                    actions = actions,
                    onProfileChange = { transform -> app.profiles.update(transform) },
                    onExport = { year, month, done ->
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                runCatching { app.exporter.export(year, month, trips, profile) }
                                    .map { result ->
                                        val where = if (result.inDownloads) "차량 저장소의 Download/AutoLog 폴더" else "앱 내부 폴더"
                                        "${result.tripCount}건을 저장했습니다. $where, ${result.fileName}" to true
                                    }
                                    .getOrElse { error ->
                                        Log.w(TAG, "운행기록부 내보내기 실패: ${error.javaClass.simpleName}: ${error.message}")
                                        "내보내지 못했습니다" to false
                                    }
                            }
                            done(message.first, message.second)
                        }
                    },
                    share = share,
                    onShare = { year, month ->
                        // 내보내 둔 파일을 같은 네트워크의 폰이 받아 갈 수 있게 연다.
                        scope.launch(Dispatchers.IO) {
                            val opened = app.phoneShare.open("${year}년 ${month}월 운행기록부", app.exporter.exported(year, month))
                            if (!opened) Log.w(TAG, "폰으로 받기를 열지 못함")
                        }
                    },
                    onCloseShare = { scope.launch(Dispatchers.IO) { app.phoneShare.close() } },
                    onConsent = {
                        app.profiles.update { it.copy(consented = true) }
                        Log.i(TAG, "기록 항목에 동의함")
                    },
                    onRemovePlace = { place -> app.placeStore.update { list -> list.filter { it != place } } },
                    onWipe = {
                        scope.launch {
                            app.wipeAll()
                            Log.i(TAG, "데이터를 모두 지움")
                        }
                    },
                )
            }
        }
    }

    private fun isDebuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        const val EXTRA_SEED = "seed"
        const val EXTRA_NOW = "now"
        private const val TAG = "AUTOLOG"
        private const val NOTE_MAX = 60
    }
}
