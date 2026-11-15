package com.autolog.app.trip

import android.content.Context

/** 열려 있는 운행의 출발 정보. */
data class OpenTrip(
    val startAtMs: Long,
    val startOdoKm: Float?,
    val startPlace: String?,
    val startLat: Double? = null,
    val startLng: Double? = null,
)

/**
 * 열려 있는 운행의 출발 정보를 디스크에 남긴다.
 * 주행 중에 앱 프로세스가 죽어도, 다시 켜졌을 때 출발 시각·출발 계기판 거리·출발지를 되살릴 수 있다.
 */
class OpenTripStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("open_trip", Context.MODE_PRIVATE)

    fun save(trip: OpenTrip) {
        prefs.edit().apply {
            putLong(KEY_START_AT, trip.startAtMs)
            if (trip.startOdoKm != null) putFloat(KEY_START_ODO, trip.startOdoKm) else remove(KEY_START_ODO)
            if (trip.startPlace != null) putString(KEY_START_PLACE, trip.startPlace) else remove(KEY_START_PLACE)
            if (trip.startLat != null && trip.startLng != null) {
                putString(KEY_START_LAT, trip.startLat.toString())
                putString(KEY_START_LNG, trip.startLng.toString())
            } else {
                remove(KEY_START_LAT)
                remove(KEY_START_LNG)
            }
        }.apply()
    }

    fun clear() {
        if (prefs.contains(KEY_START_AT)) prefs.edit().clear().apply()
    }

    /** 저장된 출발 정보. 없으면 null. */
    fun load(): OpenTrip? {
        if (!prefs.contains(KEY_START_AT)) return null
        return OpenTrip(
            startAtMs = prefs.getLong(KEY_START_AT, 0L),
            startOdoKm = if (prefs.contains(KEY_START_ODO)) prefs.getFloat(KEY_START_ODO, 0f) else null,
            startPlace = prefs.getString(KEY_START_PLACE, null),
            startLat = prefs.getString(KEY_START_LAT, null)?.toDoubleOrNull(),
            startLng = prefs.getString(KEY_START_LNG, null)?.toDoubleOrNull(),
        )
    }

    private companion object {
        const val KEY_START_AT = "start_at_ms"
        const val KEY_START_ODO = "start_odo_km"
        const val KEY_START_PLACE = "start_place"
        const val KEY_START_LAT = "start_lat"
        const val KEY_START_LNG = "start_lng"
    }
}
