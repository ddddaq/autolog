package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTest {

    private fun trip(start: Float, end: Float, atMs: Long = 0L, purpose: Purpose = Purpose.UNCLASSIFIED, source: String = Trip.SOURCE_AUTO) =
        Trip(startAtMs = atMs, endAtMs = atMs + 1, startOdoKm = start, endOdoKm = end, distanceKm = end - start, purpose = purpose, source = source)

    @Test
    fun `계기판 거리가 이어지면 빈 구간이 없다`() {
        val trips = listOf(trip(100f, 118f, 1), trip(118f, 150f, 2), trip(150f, 150.6f, 3))
        assertTrue(Ledger.gaps(trips, uptoOdoKm = 150.6f).isEmpty())
    }

    @Test
    fun `앞 운행의 끝과 다음 운행의 시작이 벌어지면 빈 구간이다`() {
        val trips = listOf(trip(100f, 118f, 10), trip(130f, 150f, 20))

        val gap = Ledger.gaps(trips, uptoOdoKm = 150f).single()

        assertEquals(118f, gap.fromOdoKm, 0f)
        assertEquals(130f, gap.toOdoKm, 0f)
        assertEquals(12f, gap.km, 0f)
        assertEquals(11L, gap.afterMs)
        assertEquals(20L, gap.beforeMs)
    }

    @Test
    fun `마지막 기록 뒤로 계기판이 늘었으면 지금까지가 빈 구간이고 가장 앞에 온다`() {
        val trips = listOf(trip(100f, 118f, 10), trip(130f, 150f, 20))

        val gaps = Ledger.gaps(trips, uptoOdoKm = 157f)

        assertEquals(2, gaps.size)
        assertEquals(150f, gaps[0].fromOdoKm, 0f)
        assertEquals(157f, gaps[0].toOdoKm, 0f)
        assertNull(gaps[0].beforeMs)
        assertEquals(118f, gaps[1].fromOdoKm, 0f)
    }

    @Test
    fun `겹치는 운행과 반올림 차이는 빈 구간으로 보지 않는다`() {
        val trips = listOf(trip(100f, 120f, 1), trip(110f, 115f, 2), trip(120.2f, 140f, 3))
        assertTrue(Ledger.gaps(trips, uptoOdoKm = 140.3f).isEmpty())
    }

    @Test
    fun `지운 운행 자리는 빈 구간으로 나온다`() {
        val trips = listOf(trip(100f, 118f, 1), trip(118f, 150f, 2).copy(deleted = true), trip(150f, 160f, 3))
        assertEquals(32f, Ledger.gaps(trips, uptoOdoKm = null).single().km, 0f)
    }

    @Test
    fun `자동으로 기록한 미분류 운행이 기준보다 짧으면 짧은 이동이다`() {
        assertTrue(Ledger.isShortMove(trip(100f, 100.4f), minTripKm = 1f))
        assertFalse(Ledger.isShortMove(trip(100f, 101f), minTripKm = 1f))
        assertTrue(Ledger.isShortMove(trip(100f, 102f), minTripKm = 3f))
    }

    @Test
    fun `용무를 고른 운행과 손으로 추가한 운행은 짧아도 짧은 이동이 아니다`() {
        assertFalse(Ledger.isShortMove(trip(100f, 100.4f, purpose = Purpose.PRIVATE), minTripKm = 1f))
        assertFalse(Ledger.isShortMove(trip(100f, 100.4f, source = Trip.SOURCE_MANUAL), minTripKm = 1f))
    }
}
