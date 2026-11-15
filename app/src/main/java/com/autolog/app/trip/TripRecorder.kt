package com.autolog.app.trip

import android.util.Log
import com.autolog.app.data.Driver
import com.autolog.app.data.Profile
import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import com.autolog.app.data.TripDao
import com.autolog.app.vehicle.NaviPlace
import com.autolog.app.vehicle.VehicleSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 화면이 보여 줄 현재 상태. */
data class RecorderStatus(
    val gear: Gear = Gear.UNKNOWN,
    val odometerKm: Float? = null,
    val driverDoorOpen: Boolean = false,
    val state: DetectorState = DetectorState.Idle,
    /**
     * 정차 대기 중인 운행이 지금 닫히면 저장될 모습. id는 [TripRecorder.PARKED_ID]다.
     * 문을 열기 전에 차 안에서 용무를 미리 고를 수 있게 화면에 보여 준다. 짧은 이동이면 null.
     */
    val parked: Trip? = null,
)

/**
 * 차량 신호를 [TripDetector]에 넘기고, 닫힌 운행을 저장한다.
 * 출발지는 운행이 열릴 때의 주소, 도착지는 내비 목적지 이름(없으면 정차할 때의 주소)으로 채운다.
 *
 * 기어를 P에 두면 운행은 아직 닫히지 않았지만, 닫히면 저장될 모습을 [RecorderStatus.parked]로 내놓는다.
 * 그동안 고른 용무와 메모는 운행이 닫힐 때 그대로 적힌다. 닫히기 전에 다시 출발하면 버린다.
 *
 * [scope]는 메인 스레드 스코프여야 한다. 신호 콜백과 타이머가 모두 메인 스레드에서 돌아 상태 머신이 한 스레드에서만 불린다.
 */
class TripRecorder(
    private val signals: VehicleSignals,
    private val places: NaviPlace,
    private val dao: TripDao,
    private val scope: CoroutineScope,
    private val openTrips: OpenTripStore,
    private val currentDriver: () -> Driver?,
    /** 운행이 닫혀 저장될 때. 저장한 운행을 준다. */
    private val onTripClosed: (Trip) -> Unit = {},
    /** 정차해서 용무를 미리 고를 수 있게 됐을 때. 운행 한 건에 한 번만 부른다. */
    private val onParked: (Trip) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : VehicleSignals.Listener {

    private val detector = TripDetector()
    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status.asStateFlow()
    private var started = false
    private var ticker: Job? = null
    private var persisted: OpenTrip? = null
    private var startPlace: String? = null
    private var startLat: Double? = null
    private var startLng: Double? = null
    private var endSpot: NaviPlace.Spot? = null
    private var minTripKm = Profile.DEFAULT_MIN_TRIP_KM.toFloat()
    private var parkedPurpose = Purpose.UNCLASSIFIED
    private var parkedNote = ""
    private var parkedAnnounced = false

    fun start() {
        if (started) return
        started = true
        // 주행 중에 앱이 꺼졌다면, 저장해 둔 출발 정보로 운행을 되살린다.
        openTrips.load()?.let { open ->
            detector.restore(open.startAtMs, open.startOdoKm)
            startPlace = open.startPlace
            startLat = open.startLat
            startLng = open.startLng
            persisted = open
            publish()
            Log.i(TAG, "열려 있던 운행을 되살림 (출발 계기판 ${open.startOdoKm}km)")
        }
        places.start()
        signals.start(this)
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                val before = detector.state
                afterEvent(before, detector.onTick(now()))
            }
        }
    }

    /** 운행을 닫는 기준과, 용무를 묻지 않는 짧은 이동의 기준을 설정값으로 맞춘다. */
    fun configure(parkTimeoutMs: Long, minTripKm: Float) {
        detector.parkTimeoutMs = parkTimeoutMs
        this.minTripKm = minTripKm
        publish()
    }

    /** 정차 중에 용무를 미리 고른다. 운행이 닫힐 때 그대로 적힌다. 정차 대기가 아니면 무시한다. */
    fun setParkedPurpose(purpose: Purpose) {
        if (detector.state !is DetectorState.ParkedPending) return
        parkedPurpose = purpose
        publish()
    }

    /** 정차 중에 비고 메모를 미리 적는다. */
    fun setParkedNote(note: String) {
        if (detector.state !is DetectorState.ParkedPending) return
        parkedNote = note
        publish()
    }

    /** 기록을 멈추고 열려 있던 운행을 버린다. 동의를 거두거나 데이터를 모두 지울 때 부른다. */
    fun stop() {
        if (!started) return
        started = false
        ticker?.cancel()
        ticker = null
        signals.stop()
        places.stop()
        detector.reset()
        openTrips.clear()
        persisted = null
        startPlace = null
        startLat = null
        startLng = null
        endSpot = null
        clearParkedChoice()
        _status.value = RecorderStatus()
        Log.i(TAG, "운행 기록을 멈춤")
    }

    override fun onGear(gear: Gear) {
        val before = detector.state
        val done = detector.onGear(gear, now())
        _status.value = _status.value.copy(gear = gear)
        afterEvent(before, done)
    }

    override fun onOdometer(km: Float) {
        if (km == _status.value.odometerKm) return // 같은 값이 주기적으로 다시 온다
        detector.onOdometer(km)
        _status.value = _status.value.copy(odometerKm = km)
        publish()
        persistOpenTrip()
    }

    override fun onDriverDoor(open: Boolean) {
        val before = detector.state
        val done = detector.onDriverDoor(open)
        _status.value = _status.value.copy(driverDoorOpen = open)
        afterEvent(before, done)
    }

    override fun onProblem(what: String, error: Throwable) {
        Log.w(TAG, "$what 실패: ${error.javaClass.simpleName}: ${error.message}")
    }

    private fun afterEvent(before: DetectorState, done: CompletedTrip?) {
        val after = detector.state
        if (before::class != after::class) {
            Log.i(TAG, "상태: ${before::class.simpleName} → ${after::class.simpleName} (계기판 ${detector.odometerKm}km)")
            if (before is DetectorState.Idle && after is DetectorState.Driving) {
                // 운행이 열렸다. 출발지 주소를 물어 둔다.
                startPlace = null
                startLat = null
                startLng = null
                endSpot = null
                places.requestCurrentSpot { spot ->
                    if (detector.state !is DetectorState.Idle && startPlace == null) {
                        startPlace = spot?.address?.takeIf { it.isNotBlank() }
                        startLat = spot?.knownLatitude
                        startLng = spot?.knownLongitude
                        persistOpenTrip()
                    }
                }
            }
            if (after is DetectorState.ParkedPending) {
                // 정차했다. 문이 열리기 전에 도착지 주소를 물어 둔다.
                places.requestCurrentSpot { spot ->
                    endSpot = spot
                    publish()
                }
            }
            if (before is DetectorState.ParkedPending && after is DetectorState.Driving) {
                // 닫히기 전에 다시 출발했다. 도착지가 달라질 수 있으니 미리 고른 용무는 버리고 다음 정차 때 다시 묻는다.
                if (parkedPurpose != Purpose.UNCLASSIFIED || parkedNote.isNotEmpty()) Log.i(TAG, "다시 출발해 미리 고른 용무를 버림")
                clearParkedChoice()
            }
        }
        persistOpenTrip()
        if (done != null) save(done)
        if (after is DetectorState.Idle) clearParkedChoice()
        publish()
    }

    /** 상태와 정차 중 운행의 모습을 화면에 내놓는다. */
    private fun publish() {
        val parked = parkedPreview()
        _status.value = _status.value.copy(state = detector.state, parked = parked)
        if (parked != null && !parkedAnnounced) {
            parkedAnnounced = true
            onParked(parked)
        }
    }

    /** 정차 대기 중인 운행이 지금 닫히면 저장될 모습. 짧은 이동은 묻지 않으므로 null. */
    private fun parkedPreview(): Trip? {
        val s = detector.state as? DetectorState.ParkedPending ?: return null
        val start = s.startOdoKm ?: return null
        val end = detector.odometerKm ?: return null
        if (end - start < minTripKm) return null
        return build(CompletedTrip(s.startAtMs, s.parkedAtMs, start, end)).copy(id = PARKED_ID)
    }

    private fun clearParkedChoice() {
        parkedPurpose = Purpose.UNCLASSIFIED
        parkedNote = ""
        parkedAnnounced = false
    }

    /** 닫힌(또는 지금 닫힌다고 본) 운행을 저장할 모습으로 만든다. 정차 중에 고른 용무와 메모가 들어간다. */
    private fun build(done: CompletedTrip): Trip {
        // 도착지: 운행 중(또는 출발 직전)에 내비에 설정한 목적지 이름이 있으면 그것, 없으면 정차한 곳의 주소.
        val destination = places.recentDestination(sinceMs = done.startAtMs - DESTINATION_LOOKBACK_MS)
        val stopSpot = endSpot ?: places.lastSpot
        val endPlace = destination ?: stopSpot?.address?.takeIf { it.isNotBlank() }
        val driver = currentDriver()
        return Trip(
            startAtMs = done.startAtMs,
            endAtMs = done.endAtMs,
            startOdoKm = done.startOdoKm,
            endOdoKm = done.endOdoKm,
            distanceKm = done.distanceKm,
            purpose = parkedPurpose,
            note = parkedNote,
            source = if (done.recovered) Trip.SOURCE_RECOVERED else Trip.SOURCE_AUTO,
            driverName = driver?.name.orEmpty(),
            driverDept = driver?.dept.orEmpty(),
            startPlace = startPlace.orEmpty(),
            endPlace = endPlace.orEmpty(),
            startLat = startLat,
            startLng = startLng,
            endLat = stopSpot?.knownLatitude,
            endLng = stopSpot?.knownLongitude,
            endFromNavi = destination != null,
        )
    }

    private fun save(done: CompletedTrip) {
        val trip = build(done)
        Log.i(
            TAG,
            "운행 종료: ${trip.startPlace.ifBlank { "?" }} → ${trip.endPlace.ifBlank { "?" }}, " +
                "${done.startOdoKm} → ${done.endOdoKm}km (${done.distanceKm}km)" +
                (if (trip.purpose != Purpose.UNCLASSIFIED) " [미리 고른 용무: ${trip.purpose}]" else "") +
                (if (done.distanceKm < minTripKm) " [짧은 이동]" else "") +
                if (done.recovered) " [복구]" else "",
        )
        places.clearDestination()
        startPlace = null
        startLat = null
        startLng = null
        endSpot = null
        scope.launch(Dispatchers.IO) { dao.insert(trip) }
        onTripClosed(trip)
    }

    /** 열려 있는 운행의 출발 정보를 디스크에 맞춰 둔다. 바뀐 때만 쓴다. */
    private fun persistOpenTrip() {
        val open: OpenTrip? = when (val s = detector.state) {
            is DetectorState.Driving -> OpenTrip(s.startAtMs, s.startOdoKm, startPlace, startLat, startLng)
            is DetectorState.ParkedPending -> OpenTrip(s.startAtMs, s.startOdoKm, startPlace, startLat, startLng)
            DetectorState.Idle -> null
        }
        if (open == persisted) return
        persisted = open
        if (open == null) openTrips.clear() else openTrips.save(open)
    }

    companion object {
        /** 정차 대기 중인 운행을 화면에 보여 줄 때 쓰는 id. 저장된 운행의 id와 겹치지 않는다. */
        const val PARKED_ID = -1L
        private const val TAG = "AUTOLOG"
        private const val TICK_MS = 5_000L
        /** 출발 전에 목적지를 찍어 두는 경우를 받아 주는 시간. */
        private const val DESTINATION_LOOKBACK_MS = 10 * 60_000L
    }
}
