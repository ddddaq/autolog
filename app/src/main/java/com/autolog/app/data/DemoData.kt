package com.autolog.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * 시연용 예시 운행. 저장된 운행이 하나도 없을 때만 넣는다.
 * 마지막 예시 운행의 주행 후 계기판 거리를 현재 계기판 거리에 맞추므로, 이어서 잡히는 실제 운행과 숫자가 이어진다.
 */
object DemoData {

    private data class Seed(
        val daysAgo: Long, val hour: Int, val minute: Int, val durationMin: Long, val km: Float,
        val purpose: Purpose, val from: String, val to: String, val driver: Int = 0,
    )

    /** 예시 차량과 운전자. 설정이 비어 있을 때만 채운다. */
    val drivers = listOf(Driver("김대리", "영업1팀"), Driver("박과장", "영업1팀"))

    fun fillProfileIfEmpty(store: ProfileStore) {
        store.update { p ->
            p.copy(
                vehicleModel = p.vehicleModel.ifBlank { "아이오닉 5" },
                plateNo = p.plateNo.ifBlank { "123가 4567" },
                drivers = p.drivers.ifEmpty { drivers },
                // 시연은 첫 실행 안내를 마친 상태에서 시작한다.
                consented = true,
            )
        }
    }

    private val plan = listOf(
        Seed(9, 8, 40, 38, 18f, Purpose.COMMUTE, "집", "회사"),
        Seed(9, 13, 5, 52, 32f, Purpose.BUSINESS_VISIT, "회사", "42dot"),
        Seed(9, 18, 30, 44, 21f, Purpose.COMMUTE, "회사", "집"),
        Seed(8, 8, 35, 40, 18f, Purpose.COMMUTE, "집", "회사"),
        Seed(8, 10, 20, 75, 64f, Purpose.BUSINESS_VISIT, "회사", "한빛상사", driver = 1),
        Seed(7, 9, 10, 82, 71f, Purpose.BUSINESS_GENERAL, "회사", "성남시청"),
        Seed(6, 8, 42, 37, 18f, Purpose.COMMUTE, "집", "회사"),
        Seed(4, 11, 0, 25, 12f, Purpose.PRIVATE, "집", "마트"),
        Seed(3, 14, 15, 58, 46f, Purpose.BUSINESS_VISIT, "회사", "한빛상사", driver = 1),
        Seed(2, 8, 38, 41, 18f, Purpose.COMMUTE, "집", "회사"),
        Seed(2, 19, 10, 96, 88f, Purpose.PRIVATE, "집", "가족 모임"),
        Seed(1, 13, 0, 50, 32f, Purpose.BUSINESS_VISIT, "회사", "42dot"),
    )

    suspend fun seedIfEmpty(dao: TripDao, endOdometerKm: Float = 12_340f, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (dao.count() > 0) return false
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        var odometer = endOdometerKm - plan.sumOf { it.km.toDouble() }.toFloat()
        val trips = plan.map { seed ->
            val start = today.minusDays(seed.daysAgo).atTime(seed.hour, seed.minute).atZone(zone).toInstant().toEpochMilli()
            val trip = Trip(
                startAtMs = start,
                endAtMs = start + seed.durationMin * 60_000L,
                startOdoKm = odometer,
                endOdoKm = odometer + seed.km,
                distanceKm = seed.km,
                purpose = seed.purpose,
                source = Trip.SOURCE_DEMO,
                driverName = drivers[seed.driver].name,
                driverDept = drivers[seed.driver].dept,
                startPlace = seed.from,
                endPlace = seed.to,
            )
            odometer += seed.km
            trip
        }
        dao.insertAll(trips)
        return true
    }
}
