package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import com.autolog.app.place.PlaceBook

/** 용무 제안과 그 근거. */
data class Suggestion(val purpose: Purpose, val basis: Basis) {
    enum class Basis {
        /** 같은 두 곳을 오간 지난 운행 */
        ROUTE,
        /** 집과 회사 사이 운행 */
        HOME_WORK,
        /** 같은 도착지로 간 지난 운행 */
        DESTINATION,
    }
}

/**
 * 용무 제안. 지난 운행을 어떻게 분류했는지 보고 가장 많이 고른 용무를 제안한다.
 *
 * 1. 같은 두 곳을 오간 운행이 있으면 그 분류를 따른다. 방향은 가리지 않는다.
 *    집에서 회사로 간 운행을 출퇴근으로 적었다면, 회사에서 집으로 온 운행도 출퇴근으로 제안한다.
 * 2. 없으면, '집'과 '회사'로 이름 붙인 두 곳 사이 운행은 출퇴근으로 제안한다.
 * 3. 그것도 아니면 같은 도착지로 간 운행의 분류를 따른다.
 *
 * 출발지·도착지는 저장한 장소 이름으로 바꾼 뒤의 값을 받는다([com.autolog.app.place.PlaceBook.resolve]).
 *
 * 제안은 버튼을 강조할 뿐이고, 분류는 사용자가 눌러야 확정된다.
 */
object PurposeSuggester {

    fun suggest(trip: Trip, history: List<Trip>): Suggestion? {
        val classified = history.filter { it.id != trip.id && it.purpose != Purpose.UNCLASSIFIED }
        val from = trip.startPlace
        val to = trip.endPlace
        if (from.isNotBlank() && to.isNotBlank() && from != to) {
            val sameRoute = classified.filter {
                (it.startPlace == from && it.endPlace == to) || (it.startPlace == to && it.endPlace == from)
            }
            mostChosen(sameRoute)?.let { return Suggestion(it, Suggestion.Basis.ROUTE) }
            if (setOf(from, to) == HOME_AND_WORK) return Suggestion(Purpose.COMMUTE, Suggestion.Basis.HOME_WORK)
        }
        if (to.isNotBlank()) {
            mostChosen(classified.filter { it.endPlace == to })?.let { return Suggestion(it, Suggestion.Basis.DESTINATION) }
        }
        return null
    }

    /** 미분류 운행마다 제안을 구한다. 제안이 없는 운행은 결과에 없다. */
    fun suggestAll(trips: List<Trip>): Map<Long, Suggestion> =
        trips.filter { it.purpose == Purpose.UNCLASSIFIED }
            .mapNotNull { trip -> suggest(trip, trips)?.let { trip.id to it } }
            .toMap()

    private val HOME_AND_WORK = setOf(PlaceBook.HOME, PlaceBook.WORK)

    /** 가장 많이 고른 용무. 같으면 가장 최근에 고른 쪽. */
    private fun mostChosen(past: List<Trip>): Purpose? =
        past.groupBy { it.purpose }
            .maxWithOrNull(compareBy({ it.value.size }, { it.value.maxOf { t -> t.endAtMs } }))
            ?.key
}
