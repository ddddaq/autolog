package com.autolog.app.trip

/** 운행 경계 판정에 쓰는 기어 상태. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

sealed interface DetectorState {
    /** 운행 없음. */
    data object Idle : DetectorState

    /** 운행 중. 출발 계기판 거리를 아직 받지 못했으면 null. */
    data class Driving(val startAtMs: Long, val startOdoKm: Float?) : DetectorState

    /** 기어 P. 운전석 문이 열리거나 시간이 지나면 운행을 닫고, 다시 출발하면 같은 운행으로 잇는다. */
    data class ParkedPending(val startAtMs: Long, val startOdoKm: Float?, val parkedAtMs: Long) : DetectorState
}

/** 닫힌 운행 한 건. 운행기록부 서식의 주행 전·후 계기판 거리와 주행거리가 여기서 나온다. */
data class CompletedTrip(
    val startAtMs: Long,
    val endAtMs: Long,
    val startOdoKm: Float,
    val endOdoKm: Float,
    /** 앱이 꺼졌다 켜진 뒤 이어서 닫은 운행. 종료 시각은 추정값이다. */
    val recovered: Boolean = false,
) {
    val distanceKm: Float get() = endOdoKm - startOdoKm
}

/**
 * 차량 신호로 운행 한 건의 시작과 끝을 잡는 상태 머신.
 *
 * - 기어가 P를 벗어나면 운행을 연다. 그 시점의 계기판 거리가 출발 거리다.
 * - 기어가 P가 되면 정차 대기로 들어간다. 신호 대기처럼 다시 출발하면 같은 운행이 이어진다.
 * - 정차 대기 중 운전석 문이 열리거나 [parkTimeoutMs]가 지나면 운행을 닫는다. 종료 시각은 기어를 P에 넣은 때다.
 * - 계기판 거리가 늘었으면 짧은 이동도 운행으로 닫는다. 그래야 앞 운행의 끝과 다음 운행의 시작이 맞물린다.
 *   거리가 늘지 않은 경우(기어만 바꿨다 되돌린 경우)는 남기지 않는다.
 *
 * 안드로이드에 기대지 않는 순수 코틀린 코드라 단위 테스트로 검증한다. 한 스레드에서만 호출한다.
 */
class TripDetector(
    /** 설정에서 바꿀 수 있다. 바꾼 값은 다음 판정부터 쓰인다. */
    var parkTimeoutMs: Long = DEFAULT_PARK_TIMEOUT_MS,
) {
    var state: DetectorState = DetectorState.Idle
        private set

    var odometerKm: Float? = null
        private set

    private var driverDoorOpen = false
    private var restored = false

    /**
     * 앱이 꺼지기 전에 열려 있던 운행을 되살린다. 이후 들어오는 기어·도어 신호로 평소처럼 닫힌다.
     * 이미 운행 중이면 무시한다.
     */
    fun restore(startAtMs: Long, startOdoKm: Float?) {
        if (state != DetectorState.Idle) return
        state = DetectorState.Driving(startAtMs, startOdoKm)
        restored = true
    }

    /** 열려 있는 운행과 받은 신호를 모두 버리고 처음 상태로 돌아간다. */
    fun reset() {
        state = DetectorState.Idle
        odometerKm = null
        driverDoorOpen = false
        restored = false
    }

    fun onOdometer(km: Float) {
        odometerKm = km
        // 출발 시점에 계기판 거리를 아직 못 받았으면 처음 들어온 값을 출발 거리로 삼는다.
        when (val s = state) {
            is DetectorState.Driving -> if (s.startOdoKm == null) state = s.copy(startOdoKm = km)
            is DetectorState.ParkedPending -> if (s.startOdoKm == null) state = s.copy(startOdoKm = km)
            DetectorState.Idle -> Unit
        }
    }

    fun onGear(gear: Gear, nowMs: Long): CompletedTrip? {
        if (gear == Gear.UNKNOWN) return null
        val s = state
        return when {
            s is DetectorState.Idle && gear != Gear.PARK -> {
                state = DetectorState.Driving(nowMs, odometerKm)
                null
            }
            s is DetectorState.Driving && gear == Gear.PARK -> {
                state = DetectorState.ParkedPending(s.startAtMs, s.startOdoKm, nowMs)
                if (driverDoorOpen) close() else null
            }
            s is DetectorState.ParkedPending && gear != Gear.PARK -> {
                state = DetectorState.Driving(s.startAtMs, s.startOdoKm)
                null
            }
            else -> null
        }
    }

    fun onDriverDoor(open: Boolean): CompletedTrip? {
        driverDoorOpen = open
        return if (open && state is DetectorState.ParkedPending) close() else null
    }

    /** 주기적으로 불러 정차 대기 시간이 지났는지 확인한다. */
    fun onTick(nowMs: Long): CompletedTrip? {
        val s = state
        return if (s is DetectorState.ParkedPending && nowMs - s.parkedAtMs >= parkTimeoutMs) close() else null
    }

    private fun close(): CompletedTrip? {
        val s = state as? DetectorState.ParkedPending ?: return null
        state = DetectorState.Idle
        val recovered = restored
        restored = false
        val start = s.startOdoKm ?: return null
        val end = odometerKm ?: return null
        if (end <= start) return null
        return CompletedTrip(s.startAtMs, s.parkedAtMs, start, end, recovered)
    }

    companion object {
        const val DEFAULT_PARK_TIMEOUT_MS = 10 * 60_000L
    }
}
