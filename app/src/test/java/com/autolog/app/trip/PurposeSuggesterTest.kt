package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurposeSuggesterTest {

    private fun trip(id: Long, endPlace: String, purpose: Purpose, endAtMs: Long = id * 1_000L, startPlace: String = "") = Trip(
        id = id, startAtMs = endAtMs - 500, endAtMs = endAtMs,
        startOdoKm = 0f, endOdoKm = 10f, distanceKm = 10f,
        purpose = purpose, startPlace = startPlace, endPlace = endPlace,
    )

    private fun suggested(trip: Trip, history: List<Trip>): Purpose? = PurposeSuggester.suggest(trip, history)?.purpose

    @Test
    fun `같은 도착지를 전에 분류했으면 그 용무를 제안한다`() {
        val history = listOf(
            trip(1, "한빛상사", Purpose.BUSINESS_VISIT),
            trip(2, "본사", Purpose.COMMUTE),
        )
        val suggestion = PurposeSuggester.suggest(trip(9, "한빛상사", Purpose.UNCLASSIFIED), history)
        assertEquals(Purpose.BUSINESS_VISIT, suggestion?.purpose)
        assertEquals(Suggestion.Basis.DESTINATION, suggestion?.basis)
    }

    @Test
    fun `여러 번 분류했으면 가장 많이 고른 용무를 제안한다`() {
        val history = listOf(
            trip(1, "본사", Purpose.COMMUTE),
            trip(2, "본사", Purpose.BUSINESS_GENERAL),
            trip(3, "본사", Purpose.COMMUTE),
        )
        assertEquals(Purpose.COMMUTE, suggested(trip(9, "본사", Purpose.UNCLASSIFIED), history))
    }

    @Test
    fun `횟수가 같으면 최근에 고른 용무를 제안한다`() {
        val history = listOf(
            trip(1, "본사", Purpose.COMMUTE),
            trip(2, "본사", Purpose.BUSINESS_GENERAL),
        )
        assertEquals(Purpose.BUSINESS_GENERAL, suggested(trip(9, "본사", Purpose.UNCLASSIFIED), history))
    }

    @Test
    fun `처음 가는 곳이거나 도착지를 모르면 제안하지 않는다`() {
        val history = listOf(trip(1, "한빛상사", Purpose.BUSINESS_VISIT), trip(2, "", Purpose.PRIVATE))
        assertNull(suggested(trip(9, "대한테크", Purpose.UNCLASSIFIED), history))
        assertNull(suggested(trip(9, "", Purpose.UNCLASSIFIED), history))
    }

    @Test
    fun `미분류 운행과 자기 자신은 근거로 쓰지 않는다`() {
        val current = trip(9, "한빛상사", Purpose.UNCLASSIFIED)
        val history = listOf(current, trip(1, "한빛상사", Purpose.UNCLASSIFIED))
        assertNull(suggested(current, history))
    }

    @Test
    fun `같은 두 곳을 반대 방향으로 오간 운행도 같은 용무로 제안한다`() {
        // 아침에 집에서 회사로 간 운행을 출퇴근으로 적었다. 저녁에 회사에서 집으로 온 운행은 도착지가 처음이어도 출퇴근이다.
        val history = listOf(trip(1, "본사", Purpose.COMMUTE, startPlace = "자택"))
        val suggestion = PurposeSuggester.suggest(trip(9, "자택", Purpose.UNCLASSIFIED, startPlace = "본사"), history)
        assertEquals(Purpose.COMMUTE, suggestion?.purpose)
        assertEquals(Suggestion.Basis.ROUTE, suggestion?.basis)
    }

    @Test
    fun `같은 구간의 분류가 같은 도착지의 분류보다 먼저다`() {
        val history = listOf(
            trip(1, "본사", Purpose.COMMUTE, startPlace = "자택"),
            trip(2, "본사", Purpose.BUSINESS_GENERAL, startPlace = "한빛상사"),
            trip(3, "본사", Purpose.BUSINESS_GENERAL, startPlace = "성남시청"),
        )
        // 도착지 '본사'만 보면 일반 업무가 많지만, 자택에서 본사로 온 운행은 출퇴근이다.
        assertEquals(Purpose.COMMUTE, suggested(trip(9, "본사", Purpose.UNCLASSIFIED, startPlace = "자택"), history))
        // 처음 보는 출발지에서 본사로 온 운행은 도착지 기준으로 제안한다.
        assertEquals(Purpose.BUSINESS_GENERAL, suggested(trip(10, "본사", Purpose.UNCLASSIFIED, startPlace = "42dot"), history))
    }

    @Test
    fun `미분류 운행 전체의 제안을 한 번에 구한다`() {
        val trips = listOf(
            trip(1, "본사", Purpose.COMMUTE, startPlace = "자택"),
            trip(2, "자택", Purpose.UNCLASSIFIED, startPlace = "본사"),
            trip(3, "대한테크", Purpose.UNCLASSIFIED, startPlace = "본사"),
        )
        val all = PurposeSuggester.suggestAll(trips)
        assertEquals(setOf(2L), all.keys)
        assertEquals(Purpose.COMMUTE, all[2L]?.purpose)
    }

    @Test
    fun `집과 회사 사이 운행은 지난 기록이 없어도 출퇴근으로 제안한다`() {
        val suggestion = PurposeSuggester.suggest(trip(9, "회사", Purpose.UNCLASSIFIED, startPlace = "집"), emptyList())
        assertEquals(Purpose.COMMUTE, suggestion?.purpose)
        assertEquals(Suggestion.Basis.HOME_WORK, suggestion?.basis)
        assertEquals(Purpose.COMMUTE, suggested(trip(10, "집", Purpose.UNCLASSIFIED, startPlace = "회사"), emptyList()))
        // 집에서 다른 곳으로 간 운행은 해당하지 않는다.
        assertNull(suggested(trip(11, "마트", Purpose.UNCLASSIFIED, startPlace = "집"), emptyList()))
    }

    @Test
    fun `집과 회사 사이라도 전에 다르게 분류했으면 그 분류가 먼저다`() {
        val history = listOf(trip(1, "회사", Purpose.BUSINESS_GENERAL, startPlace = "집"))
        val suggestion = PurposeSuggester.suggest(trip(9, "집", Purpose.UNCLASSIFIED, startPlace = "회사"), history)
        assertEquals(Purpose.BUSINESS_GENERAL, suggestion?.purpose)
        assertEquals(Suggestion.Basis.ROUTE, suggestion?.basis)
    }
}
