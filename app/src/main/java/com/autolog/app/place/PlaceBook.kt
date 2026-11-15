package com.autolog.app.place

import com.autolog.app.data.Trip
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 이름을 붙인 장소. 좌표가 있으면 그 둘레 [PlaceBook.RADIUS_M] 안에서 출발하거나 정차한 운행에 이 이름을 쓴다.
 * [rawNames]는 이 장소로 볼 원래 이름들이다. 내비 목적지 이름이나, 좌표를 모르던 때의 주소가 들어간다.
 */
data class SavedPlace(
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val rawNames: Set<String> = emptySet(),
)

/**
 * 운행의 출발지·도착지를 저장한 장소 이름으로 바꿔 읽는다. 안드로이드에 기대지 않는 순수 코틀린 코드다.
 *
 * 차량이 주는 주소는 "○○구 ○○동"처럼 넓어서 글자만으로는 같은 곳인지 알 수 없다. 그래서 좌표를 먼저 본다.
 * 도착지가 내비 목적지 이름이면 그 이름으로 찾는다. 목적지는 차가 선 자리와 떨어져 있을 수 있기 때문이다.
 */
object PlaceBook {

    const val HOME = "집"
    const val WORK = "회사"

    /** 같은 장소로 보는 거리(m). 주차장 안에서 자리가 달라지는 정도를 받아 준다. */
    const val RADIUS_M = 150.0

    /** 운행의 한쪽 끝. */
    data class Endpoint(
        /** 차량이 준 원래 이름: 주소 또는 내비 목적지 이름 */
        val raw: String,
        val latitude: Double?,
        val longitude: Double?,
        /** 좌표로 장소를 찾아도 되는가. 내비 목적지 이름이면 false */
        val byCoordinates: Boolean,
    )

    fun startOf(trip: Trip) = Endpoint(trip.startPlace, trip.startLat, trip.startLng, byCoordinates = true)

    fun endOf(trip: Trip) = Endpoint(trip.endPlace, trip.endLat, trip.endLng, byCoordinates = !trip.endFromNavi)

    /** 이 끝에 해당하는 저장 장소. 없으면 null. */
    fun find(endpoint: Endpoint, places: List<SavedPlace>): SavedPlace? {
        val lat = endpoint.latitude
        val lng = endpoint.longitude
        if (endpoint.byCoordinates && lat != null && lng != null) {
            // 좌표를 아는 곳끼리는 좌표로만 견준다. 주소 글자가 같아도 다른 곳일 수 있다.
            val nearest = places
                .mapNotNull { place ->
                    val pLat = place.latitude ?: return@mapNotNull null
                    val pLng = place.longitude ?: return@mapNotNull null
                    place to distanceM(lat, lng, pLat, pLng)
                }
                .filter { it.second <= RADIUS_M }
                .minByOrNull { it.second }
                ?.first
            if (nearest != null) return nearest
            // 좌표 없이 이름만 붙여 둔 장소(좌표를 남기기 전의 운행에서 붙인 이름)는 원래 이름으로 찾는다.
            if (endpoint.raw.isBlank()) return null
            return places.firstOrNull { it.latitude == null && endpoint.raw in it.rawNames }
        }
        if (endpoint.raw.isBlank()) return null
        return places.firstOrNull { endpoint.raw in it.rawNames }
    }

    /** 화면과 운행기록부에 쓸 이름. 저장한 장소가 있으면 그 이름, 없으면 원래 이름. */
    fun label(endpoint: Endpoint, places: List<SavedPlace>): String = find(endpoint, places)?.name ?: endpoint.raw

    /** 운행들의 출발지·도착지를 저장한 장소 이름으로 바꾼 사본. 원래 기록은 바꾸지 않는다. */
    fun resolve(trips: List<Trip>, places: List<SavedPlace>): List<Trip> =
        if (places.isEmpty()) trips
        else trips.map { it.copy(startPlace = label(startOf(it), places), endPlace = label(endOf(it), places)) }

    /**
     * 이 끝에 [name]이라는 이름을 붙인 뒤의 장소 목록.
     * 이미 다른 이름이 붙어 있던 곳이면 그 이름을 바꾸고, 같은 이름의 장소가 있으면 거기에 합친다.
     */
    fun named(places: List<SavedPlace>, endpoint: Endpoint, name: String): List<SavedPlace> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return places
        val current = find(endpoint, places)
        val rest = places.filter { it !== current && it.name != trimmed }
        val sameName = places.firstOrNull { it !== current && it.name == trimmed }
        val useCoordinates = endpoint.byCoordinates && endpoint.latitude != null && endpoint.longitude != null
        val raws = buildSet {
            current?.let { addAll(it.rawNames) }
            sameName?.let { addAll(it.rawNames) }
            if (endpoint.raw.isNotBlank()) add(endpoint.raw)
        }
        val merged = SavedPlace(
            name = trimmed,
            // 좌표 없이 이름만 있던 장소에는 이번에 알게 된 좌표를 채운다.
            latitude = current?.latitude ?: sameName?.latitude ?: endpoint.latitude.takeIf { useCoordinates },
            longitude = current?.longitude ?: sameName?.longitude ?: endpoint.longitude.takeIf { useCoordinates },
            rawNames = raws,
        )
        return rest + merged
    }

    /** 이 끝에 붙어 있던 이름을 뗀 뒤의 장소 목록. */
    fun unnamed(places: List<SavedPlace>, endpoint: Endpoint): List<SavedPlace> {
        val current = find(endpoint, places) ?: return places
        return places.filter { it !== current }
    }

    /** 두 좌표 사이의 거리(m). */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
