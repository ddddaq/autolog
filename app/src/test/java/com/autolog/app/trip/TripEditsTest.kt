package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEditsTest {

    private val minute = 60_000L
    private val utc = java.time.ZoneOffset.UTC

    private val toStore = Trip(
        id = 1, startAtMs = 0, endAtMs = 20 * minute, startOdoKm = 100f, endOdoKm = 112f, distanceKm = 12f,
        driverName = "김대리", driverDept = "영업1팀", startPlace = "회사", endPlace = "편의점", note = "주유",
    )
    private val toClient = Trip(
        id = 2, startAtMs = 25 * minute, endAtMs = 60 * minute, startOdoKm = 112f, endOdoKm = 132f, distanceKm = 20f,
        purpose = Purpose.BUSINESS_VISIT, driverName = "김대리", driverDept = "영업1팀", startPlace = "편의점", endPlace = "한빛상사",
        endFromNavi = true,
    )

    @Test
    fun `계기판 거리가 바로 이어지는 앞 운행을 찾는다`() {
        val unrelated = Trip(id = 3, startAtMs = 0, endAtMs = 1, startOdoKm = 50f, endOdoKm = 60f, distanceKm = 10f)
        assertEquals(1L, TripEdits.previous(toClient, listOf(unrelated, toStore, toClient), utc)?.id)
        assertNull(TripEdits.previous(toStore, listOf(unrelated, toStore, toClient), utc))
    }

    @Test
    fun `계기판 거리가 이어져도 다른 날의 운행과는 합치지 않는다`() {
        val day = 24 * 60 * minute
        val twoDaysLater = toClient.copy(startAtMs = 2 * day + 25 * minute, endAtMs = 2 * day + 60 * minute)
        assertNull(TripEdits.previous(twoDaysLater, listOf(toStore, twoDaysLater), utc))
    }

    @Test
    fun `두 운행을 합치면 출발은 앞 운행, 도착은 뒤 운행에서 온다`() {
        val merged = TripEdits.merge(toStore, toClient)
        assertEquals(0L, merged.startAtMs)
        assertEquals(60 * minute, merged.endAtMs)
        assertEquals(100f, merged.startOdoKm, 0f)
        assertEquals(132f, merged.endOdoKm, 0f)
        assertEquals(32f, merged.distanceKm, 0f)
        assertEquals("회사" to "한빛상사", merged.startPlace to merged.endPlace)
        assertTrue(merged.endFromNavi)
        assertEquals(Purpose.BUSINESS_VISIT, merged.purpose)
        assertEquals("주유", merged.note)
        assertEquals(Trip.SOURCE_MERGED, merged.source)
        assertEquals(0L, merged.id)
    }

    @Test
    fun `뒤 운행을 분류하지 않았으면 앞 운행의 용무를 쓴다`() {
        val merged = TripEdits.merge(toStore.copy(purpose = Purpose.PRIVATE), toClient.copy(purpose = Purpose.UNCLASSIFIED))
        assertEquals(Purpose.PRIVATE, merged.purpose)
    }

    @Test
    fun `운행을 중간 계기판 거리에서 둘로 나눈다`() {
        val (first, second) = TripEdits.split(toClient, 117f)!!
        assertEquals(112f to 117f, first.startOdoKm to first.endOdoKm)
        assertEquals(117f to 132f, second.startOdoKm to second.endOdoKm)
        assertEquals(5f, first.distanceKm, 0f)
        assertEquals(15f, second.distanceKm, 0f)
        // 20km 가운데 5km 지점이면 시간도 4분의 1 지점으로 어림한다.
        val midAt = 25 * minute + (35 * minute) / 4
        assertEquals(midAt, first.endAtMs)
        assertEquals(midAt, second.startAtMs)
        // 가른 지점의 장소는 비운다.
        assertEquals("편의점" to "", first.startPlace to first.endPlace)
        assertEquals("" to "한빛상사", second.startPlace to second.endPlace)
        assertFalse(first.endFromNavi)
        assertTrue(second.endFromNavi)
        assertEquals(Purpose.BUSINESS_VISIT, first.purpose)
        assertEquals(Purpose.BUSINESS_VISIT, second.purpose)
        assertEquals(Trip.SOURCE_SPLIT, first.source)
    }

    @Test
    fun `주행 전후 계기판 거리 밖이거나 끝과 같은 값으로는 나누지 않는다`() {
        assertNull(TripEdits.split(toClient, 112f))
        assertNull(TripEdits.split(toClient, 132f))
        assertNull(TripEdits.split(toClient, 90f))
        assertNull(TripEdits.split(toClient, 140f))
        assertTrue(TripEdits.canSplit(toClient, 112.5f))
    }

    @Test
    fun `합친 운행을 되돌리면 원래 두 운행이 돌아온다`() {
        val merged = TripEdits.merge(toStore, toClient).copy(id = 3)
        val all = listOf(toStore.copy(deleted = true), toClient.copy(deleted = true), merged)
        val plan = TripEdits.undoPlan(merged, all)!!
        assertEquals(listOf(1L, 2L), plan.restoreIds)
        assertEquals(listOf(3L), plan.removeIds)
    }

    @Test
    fun `나눈 운행을 되돌리면 원래 운행이 돌아오고 두 쪽이 사라진다`() {
        val (a, b) = TripEdits.split(toClient, 117f)!!
        val first = a.copy(id = 5)
        val second = b.copy(id = 6)
        val all = listOf(toClient.copy(deleted = true), first, second)
        val plan = TripEdits.undoPlan(second, all)!!
        assertEquals(listOf(2L), plan.restoreIds)
        assertEquals(listOf(5L, 6L), plan.removeIds)
    }

    @Test
    fun `나뉜 한쪽을 지웠으면 나누기를 되돌리지 않는다`() {
        val (a, b) = TripEdits.split(toClient, 117f)!!
        val all = listOf(toClient.copy(deleted = true), a.copy(id = 5, deleted = true), b.copy(id = 6))
        assertNull(TripEdits.undoPlan(b.copy(id = 6), all))
    }

    @Test
    fun `합친 것을 다시 나눴다면 한 단계씩 되돌린다`() {
        val merged = TripEdits.merge(toStore, toClient).copy(id = 3)
        val (a, b) = TripEdits.split(merged, 120f)!!
        val all = listOf(toStore.copy(deleted = true), toClient.copy(deleted = true), merged.copy(deleted = true), a.copy(id = 4), b.copy(id = 5))
        // 먼저 나누기를 되돌리면 합친 운행이 돌아온다.
        val plan = TripEdits.undoPlan(a.copy(id = 4), all)!!
        assertEquals(listOf(3L), plan.restoreIds)
        assertEquals(listOf(4L, 5L), plan.removeIds)
    }

    @Test
    fun `원래 운행을 적어 두기 전에 합친 운행도 계기판 거리로 찾아 되돌린다`() {
        val legacy = TripEdits.merge(toStore, toClient).copy(id = 3, replaces = "")
        val unrelated = Trip(id = 9, startAtMs = 0, endAtMs = 1, startOdoKm = 50f, endOdoKm = 60f, distanceKm = 10f, deleted = true)
        val plan = TripEdits.undoPlan(legacy, listOf(unrelated, toStore.copy(deleted = true), toClient.copy(deleted = true), legacy))!!
        assertEquals(listOf(1L, 2L), plan.restoreIds)
    }

    @Test
    fun `자동으로 기록된 운행과 원래 운행을 찾지 못한 운행은 되돌릴 것이 없다`() {
        assertNull(TripEdits.undoPlan(toStore, listOf(toStore, toClient)))
        val orphan = TripEdits.merge(toStore, toClient).copy(id = 3, replaces = "")
        assertNull(TripEdits.undoPlan(orphan, listOf(orphan)))
    }

    private fun Trip.gone(reason: String) = copy(deleted = true, deletedReason = reason)

    @Test
    fun `사용자가 지운 운행은 되살릴 수 있다`() {
        val all = listOf(toStore, toClient.gone(Trip.DELETED_BY_USER))
        assertEquals(listOf(2L), TripEdits.restorable(all).map { it.id })
    }

    @Test
    fun `합쳐서 바뀐 원래 운행은 되살릴 목록에 나오지 않는다`() {
        val merged = TripEdits.merge(toStore, toClient).copy(id = 3)
        assertTrue(TripEdits.restorable(listOf(toStore.gone(Trip.DELETED_BY_EDIT), toClient.gone(Trip.DELETED_BY_EDIT), merged)).isEmpty())
    }

    @Test
    fun `합친 운행을 지웠으면 합친 운행만 되살릴 목록에 나온다`() {
        val merged = TripEdits.merge(toStore, toClient).copy(id = 3).gone(Trip.DELETED_BY_USER)
        val all = listOf(toStore.gone(Trip.DELETED_BY_EDIT), toClient.gone(Trip.DELETED_BY_EDIT), merged)
        assertEquals(listOf(3L), TripEdits.restorable(all).map { it.id })
    }

    @Test
    fun `되돌리면서 사라진 운행은 원래 운행을 지워도 되살릴 목록에 나오지 않는다`() {
        // 25km 운행을 나눴다가 되돌렸다. 그 뒤 25km 운행을 지우면, 목록에는 그 운행만 나와야 한다.
        val (a, b) = TripEdits.split(toClient, 117f)!!
        val all = listOf(toStore, toClient.gone(Trip.DELETED_BY_USER), a.copy(id = 5).gone(Trip.DELETED_BY_UNDO), b.copy(id = 6).gone(Trip.DELETED_BY_UNDO))
        assertEquals(listOf(2L), TripEdits.restorable(all).map { it.id })
    }

    @Test
    fun `지금 보이는 운행과 계기판 거리가 겹치는 지운 운행은 나오지 않는다`() {
        val overlapping = toClient.copy(id = 7, startOdoKm = 105f, endOdoKm = 125f).gone(Trip.DELETED_BY_USER)
        assertTrue(TripEdits.restorable(listOf(toStore, toClient, overlapping)).isEmpty())
    }

    @Test
    fun `까닭을 적어 두기 전에 지운 운행은 합치거나 나눠서 생긴 것이 아닐 때만 나온다`() {
        val (a, _) = TripEdits.split(toClient, 117f)!!
        val all = listOf(toStore, toClient.gone(""), a.copy(id = 5).gone(""))
        assertEquals(listOf(2L), TripEdits.restorable(all).map { it.id })
    }
}
