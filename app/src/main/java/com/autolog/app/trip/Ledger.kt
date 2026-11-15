package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip

/**
 * 장부로서의 운행 기록을 살핀다. 운행기록부는 줄마다 주행 전·후 계기판 거리를 적으므로,
 * 앞줄의 주행 후 거리와 다음 줄의 주행 전 거리가 맞물려야 총주행거리가 계기판과 맞는다.
 */
object Ledger {

    /** 계기판 거리가 이보다 많이 벌어져야 빈 구간으로 본다(km). 소수 자리 반올림 차이는 넘긴다. */
    const val GAP_MIN_KM = 0.5f

    /**
     * 기록이 빈 구간. 앞 운행의 주행 후 거리([fromOdoKm])와 다음 운행의 주행 전 거리([toOdoKm]) 사이다.
     * [beforeMs]가 null이면 마지막 운행 뒤로 지금까지 비어 있는 구간이다.
     */
    data class Gap(val fromOdoKm: Float, val toOdoKm: Float, val afterMs: Long, val beforeMs: Long?) {
        val km: Float get() = toOdoKm - fromOdoKm
    }

    /**
     * 짧은 이동인가. 주차장 안에서 자리를 옮긴 것 같은 이동은 기록은 하되 용무를 묻지 않는다.
     * 차량 신호로 기록했고 아직 분류하지 않은 운행 가운데 [minTripKm]보다 짧은 것이다. 사용자가 용무를 고르면 보통 운행이 된다.
     */
    fun isShortMove(trip: Trip, minTripKm: Float): Boolean =
        trip.purpose == Purpose.UNCLASSIFIED && trip.distanceKm < minTripKm &&
            (trip.source == Trip.SOURCE_AUTO || trip.source == Trip.SOURCE_RECOVERED)

    /**
     * 기록이 빈 구간을 찾는다. 가장 최근 것이 앞에 온다.
     * [uptoOdoKm]은 지금 계기판 거리(운행 중이면 그 운행의 출발 거리)다. 마지막 기록보다 크면 그 사이도 빈 구간이다.
     */
    fun gaps(trips: List<Trip>, uptoOdoKm: Float?): List<Gap> {
        val ordered = trips.filter { !it.deleted }.sortedWith(compareBy({ it.startOdoKm }, { it.endOdoKm }))
        if (ordered.isEmpty()) return emptyList()
        val found = mutableListOf<Gap>()
        var covered = ordered.first()
        for (next in ordered.drop(1)) {
            if (next.startOdoKm - covered.endOdoKm >= GAP_MIN_KM) {
                found += Gap(covered.endOdoKm, next.startOdoKm, afterMs = covered.endAtMs, beforeMs = next.startAtMs)
            }
            if (next.endOdoKm >= covered.endOdoKm) covered = next
        }
        if (uptoOdoKm != null && uptoOdoKm - covered.endOdoKm >= GAP_MIN_KM) {
            found += Gap(covered.endOdoKm, uptoOdoKm, afterMs = covered.endAtMs, beforeMs = null)
        }
        return found.reversed()
    }
}
