package com.autolog.app.trip

import com.autolog.app.data.Purpose
import com.autolog.app.data.Trip
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * 운행 합치기와 나누기. 안드로이드에 기대지 않는 순수 코틀린 코드다.
 *
 * 합치기: 잠깐 내렸다 탄 탓에 둘로 끊긴 운행을 하나로 잇는다. 같은 날, 계기판 거리가 바로 이어지는 두 운행만 합친다.
 * 나누기: 한 운행 안에 용무가 둘 섞였을 때(업무 중 사적 용무 등) 중간 계기판 거리에서 둘로 가른다.
 *
 * 둘 다 새 운행을 만들어 돌려준다. 원래 운행을 지운 표시로 남겨 두는 일은 부르는 쪽이 한다.
 */
object TripEdits {

    /** 계기판 거리를 같다고 보는 오차(km). */
    private const val SAME_KM = 0.05f

    /**
     * [trip] 바로 앞에서 계기판 거리가 이어지는, 같은 날의 운행. 없으면 null.
     * 며칠 전 운행과는 계기판 거리가 이어져도 합치지 않는다. 운행기록부 한 줄은 하루의 운행이기 때문이다.
     */
    fun previous(trip: Trip, all: List<Trip>, zone: ZoneId = ZoneId.systemDefault()): Trip? {
        val day = dayOf(trip.startAtMs, zone)
        return all.filter {
            it.id != trip.id && !it.deleted && abs(it.endOdoKm - trip.startOdoKm) < SAME_KM && dayOf(it.startAtMs, zone) == day
        }.maxByOrNull { it.endAtMs }
    }

    private fun dayOf(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /**
     * 앞 운행 [first]와 뒤 운행 [second]를 하나로. 출발은 앞 운행에서, 도착은 뒤 운행에서 가져온다.
     * 용무는 뒤 운행에 적혀 있으면 그것을, 아니면 앞 운행의 것을 쓴다.
     */
    fun merge(first: Trip, second: Trip): Trip = Trip(
        startAtMs = first.startAtMs,
        endAtMs = second.endAtMs,
        startOdoKm = first.startOdoKm,
        endOdoKm = second.endOdoKm,
        distanceKm = second.endOdoKm - first.startOdoKm,
        purpose = if (second.purpose != Purpose.UNCLASSIFIED) second.purpose else first.purpose,
        note = listOf(first.note, second.note).filter { it.isNotBlank() }.distinct().joinToString(" / "),
        source = Trip.SOURCE_MERGED,
        driverName = second.driverName.ifBlank { first.driverName },
        driverDept = if (second.driverName.isNotBlank()) second.driverDept else first.driverDept,
        startPlace = first.startPlace,
        endPlace = second.endPlace,
        startLat = first.startLat,
        startLng = first.startLng,
        endLat = second.endLat,
        endLng = second.endLng,
        endFromNavi = second.endFromNavi,
        replaces = "${first.id},${second.id}",
    )

    /** [midOdoKm]가 운행의 주행 전·후 계기판 거리 사이에 있는가. */
    fun canSplit(trip: Trip, midOdoKm: Float): Boolean =
        midOdoKm > trip.startOdoKm + SAME_KM && midOdoKm < trip.endOdoKm - SAME_KM

    /**
     * 운행을 [midOdoKm]에서 둘로. 나뉜 시각은 거리 비율로 어림한다.
     * 가른 지점의 장소는 알 수 없어 비워 둔다. 용무와 운전자, 메모는 두 쪽 모두 원래 것을 물려받는다.
     */
    fun split(trip: Trip, midOdoKm: Float): Pair<Trip, Trip>? {
        if (!canSplit(trip, midOdoKm)) return null
        val ratio = (midOdoKm - trip.startOdoKm) / (trip.endOdoKm - trip.startOdoKm)
        val midAtMs = trip.startAtMs + ((trip.endAtMs - trip.startAtMs) * ratio).toLong()
        val first = trip.copy(
            id = 0, endAtMs = midAtMs, endOdoKm = midOdoKm, distanceKm = midOdoKm - trip.startOdoKm,
            endPlace = "", endLat = null, endLng = null, endFromNavi = false, source = Trip.SOURCE_SPLIT,
            replaces = trip.id.toString(),
        )
        val second = trip.copy(
            id = 0, startAtMs = midAtMs, startOdoKm = midOdoKm, distanceKm = trip.endOdoKm - midOdoKm,
            startPlace = "", startLat = null, startLng = null, source = Trip.SOURCE_SPLIT,
            replaces = trip.id.toString(),
        )
        return first to second
    }

    /** 되돌리기 계획: 되살릴 운행과 지운 표시로 돌릴 운행. */
    data class UndoPlan(val restoreIds: List<Long>, val removeIds: List<Long>)

    /**
     * 합치기나 나누기를 되돌리는 방법. 되돌릴 수 없으면 null.
     * [all]은 지운 표시가 있는 운행까지 포함한 전체다.
     *
     * - 합친 운행: 원래 두 운행을 되살리고 합친 운행을 지운 표시로 돌린다.
     * - 나눈 운행: 나뉜 두 쪽이 모두 그대로 있을 때만, 원래 운행을 되살리고 두 쪽을 지운 표시로 돌린다.
     *   한쪽을 이미 지웠거나 다시 합쳤다면 되돌리지 않는다. 되살린 운행이 다른 운행과 겹치게 되기 때문이다.
     *
     * 되돌리면 합치거나 나눈 뒤에 고친 용무와 메모는 사라지고, 원래 운행에 적혀 있던 것이 돌아온다.
     */
    fun undoPlan(trip: Trip, all: List<Trip>): UndoPlan? {
        if (trip.deleted) return null
        return when (trip.source) {
            Trip.SOURCE_MERGED -> {
                val originals = linkedOriginals(trip, all) ?: guessMergedOriginals(trip, all) ?: return null
                UndoPlan(restoreIds = originals.map { it.id }, removeIds = listOf(trip.id))
            }
            Trip.SOURCE_SPLIT -> {
                val original = linkedOriginals(trip, all)?.singleOrNull() ?: guessSplitOriginal(trip, all) ?: return null
                val halves = all.filter {
                    !it.deleted && it.source == Trip.SOURCE_SPLIT &&
                        it.startOdoKm > original.startOdoKm - SAME_KM && it.endOdoKm < original.endOdoKm + SAME_KM &&
                        (it.replaces.isBlank() || it.replaces == original.id.toString())
                }.sortedBy { it.startOdoKm }
                val tiles = halves.size == 2 &&
                    abs(halves[0].startOdoKm - original.startOdoKm) < SAME_KM &&
                    abs(halves[0].endOdoKm - halves[1].startOdoKm) < SAME_KM &&
                    abs(halves[1].endOdoKm - original.endOdoKm) < SAME_KM
                if (!tiles) return null
                UndoPlan(restoreIds = listOf(original.id), removeIds = halves.map { it.id })
            }
            else -> null
        }
    }

    /**
     * 되살릴 수 있는 지운 운행. 최근 것부터.
     *
     * 사용자가 '이 운행 삭제'로 지운 것만 내놓는다. 합치거나 나눠서 바뀐 원래 운행과, 되돌리면서 사라진 운행은
     * 합치기·나누기 쪽에서 다룬다. 지금 보이는 운행과 계기판 거리가 겹치는 것도 뺀다. 되살리면 같은 구간이 두 번 적히기 때문이다.
     * 까닭을 적어 두기 전에 지운 운행은, 합치거나 나눠서 생긴 것이 아니면 사용자가 지운 것으로 본다.
     */
    fun restorable(all: List<Trip>): List<Trip> {
        val shown = all.filter { !it.deleted }
        return all.filter { gone ->
            val byUser = when (gone.deletedReason) {
                Trip.DELETED_BY_USER -> true
                "" -> gone.source != Trip.SOURCE_MERGED && gone.source != Trip.SOURCE_SPLIT
                else -> false
            }
            gone.deleted && byUser && shown.none { overlaps(it, gone) }
        }.sortedByDescending { it.endAtMs }
    }

    private fun overlaps(a: Trip, b: Trip): Boolean =
        a.startOdoKm < b.endOdoKm - SAME_KM && b.startOdoKm < a.endOdoKm - SAME_KM

    /** [Trip.replaces]에 적힌 원래 운행들. 적혀 있지 않거나 하나라도 찾지 못하면 null. */
    private fun linkedOriginals(trip: Trip, all: List<Trip>): List<Trip>? {
        val ids = trip.replaces.split(',').mapNotNull { it.trim().toLongOrNull() }
        if (ids.isEmpty()) return null
        val found = ids.mapNotNull { id -> all.firstOrNull { it.id == id && it.deleted } }
        return found.takeIf { it.size == ids.size }
    }

    /** 원래 운행을 적어 두기 전에 합친 운행: 지운 운행 가운데 계기판 거리가 딱 맞게 이어지는 두 건을 찾는다. */
    private fun guessMergedOriginals(trip: Trip, all: List<Trip>): List<Trip>? {
        val deleted = all.filter { it.deleted && it.id < trip.id }
        val firsts = deleted.filter { abs(it.startOdoKm - trip.startOdoKm) < SAME_KM && it.endOdoKm < trip.endOdoKm - SAME_KM }
        return firsts.flatMap { first ->
            deleted.filter { abs(it.startOdoKm - first.endOdoKm) < SAME_KM && abs(it.endOdoKm - trip.endOdoKm) < SAME_KM }
                .map { second -> listOf(first, second) }
        }.maxByOrNull { pair -> pair.sumOf { it.id } }
    }

    /** 원래 운행을 적어 두기 전에 나눈 운행: 지운 운행 가운데 이 운행을 품는 가장 최근의 것. */
    private fun guessSplitOriginal(trip: Trip, all: List<Trip>): Trip? =
        all.filter {
            it.deleted && it.id < trip.id &&
                it.startOdoKm < trip.startOdoKm + SAME_KM && it.endOdoKm > trip.endOdoKm - SAME_KM &&
                it.distanceKm > trip.distanceKm + SAME_KM
        }.maxByOrNull { it.id }
}
