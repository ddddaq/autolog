package com.autolog.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 운행기록부 '사용자(부서·성명)' 칸에 들어가는 운전자. */
data class Driver(val name: String, val dept: String) {
    /** 서식 표기: 부서 성명 */
    val display: String get() = listOf(dept, name).filter { it.isNotBlank() }.joinToString(" ")
}

/** 운행기록부 머리 정보(차종, 자동차등록번호)와 운전자 목록. */
data class Profile(
    val vehicleModel: String = "",
    val plateNo: String = "",
    val drivers: List<Driver> = emptyList(),
    val currentDriver: Int = 0,
    /** 운행이 기록되면 음성으로 알린다. */
    val voiceGuide: Boolean = true,
    /** 기록 항목과 목적을 읽고 동의했는가. 동의하기 전에는 차량 신호를 읽지 않는다. */
    val consented: Boolean = false,
    /** 기어를 P에 둔 뒤 문을 열지 않아도 운행을 마치는 대기 시간(분) */
    val parkTimeoutMin: Int = DEFAULT_PARK_TIMEOUT_MIN,
    /** 이보다 짧은 이동은 기록만 하고 용무를 묻지 않는다(km) */
    val minTripKm: Int = DEFAULT_MIN_TRIP_KM,
    /** 사용자가 바꾼 용무 버튼 이름. 키는 [Purpose]의 이름. 없으면 원래 이름을 쓴다. */
    val purposeLabels: Map<String, String> = emptyMap(),
) {
    val driver: Driver? get() = drivers.getOrNull(currentDriver)

    /** 화면과 운행기록부에 쓸 용무 이름. */
    fun labelOf(purpose: Purpose): String = purposeLabels[purpose.name]?.takeIf { it.isNotBlank() } ?: purpose.label

    companion object {
        const val DEFAULT_PARK_TIMEOUT_MIN = 10
        const val DEFAULT_MIN_TRIP_KM = 1
        val parkTimeoutChoices = listOf(5, 10, 15, 30)
        val minTripChoices = listOf(1, 2, 3)
    }
}

class ProfileStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(read())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun update(transform: (Profile) -> Profile) {
        val next = transform(_profile.value).let { p ->
            p.copy(currentDriver = p.currentDriver.coerceIn(0, (p.drivers.size - 1).coerceAtLeast(0)))
        }
        _profile.value = next
        val drivers = JSONArray()
        next.drivers.forEach { drivers.put(JSONObject().put("name", it.name).put("dept", it.dept)) }
        prefs.edit()
            .putString(KEY_MODEL, next.vehicleModel)
            .putString(KEY_PLATE, next.plateNo)
            .putString(KEY_DRIVERS, drivers.toString())
            .putInt(KEY_CURRENT, next.currentDriver)
            .putBoolean(KEY_VOICE, next.voiceGuide)
            .putBoolean(KEY_CONSENTED, next.consented)
            .putInt(KEY_PARK_TIMEOUT, next.parkTimeoutMin)
            .putInt(KEY_MIN_TRIP, next.minTripKm)
            .putString(KEY_LABELS, JSONObject(next.purposeLabels.filterValues { it.isNotBlank() }).toString())
            .apply()
    }

    /** 차량 정보, 운전자, 설정, 동의를 모두 지워 처음 상태로 돌린다. */
    fun reset() {
        prefs.edit().clear().apply()
        _profile.value = Profile()
    }

    private fun read(): Profile {
        val drivers = runCatching {
            val array = JSONArray(prefs.getString(KEY_DRIVERS, "[]"))
            (0 until array.length()).map { i -> array.getJSONObject(i).let { Driver(it.optString("name"), it.optString("dept")) } }
        }.getOrDefault(emptyList())
        return Profile(
            vehicleModel = prefs.getString(KEY_MODEL, "").orEmpty(),
            plateNo = prefs.getString(KEY_PLATE, "").orEmpty(),
            drivers = drivers,
            currentDriver = prefs.getInt(KEY_CURRENT, 0),
            voiceGuide = prefs.getBoolean(KEY_VOICE, true),
            consented = prefs.getBoolean(KEY_CONSENTED, false),
            parkTimeoutMin = prefs.getInt(KEY_PARK_TIMEOUT, Profile.DEFAULT_PARK_TIMEOUT_MIN),
            minTripKm = prefs.getInt(KEY_MIN_TRIP, Profile.DEFAULT_MIN_TRIP_KM),
            purposeLabels = runCatching {
                val json = JSONObject(prefs.getString(KEY_LABELS, "{}").orEmpty())
                json.keys().asSequence().associateWith { json.optString(it) }
            }.getOrDefault(emptyMap()),
        )
    }

    private companion object {
        const val KEY_MODEL = "vehicle_model"
        const val KEY_PLATE = "plate_no"
        const val KEY_DRIVERS = "drivers"
        const val KEY_CURRENT = "current_driver"
        const val KEY_VOICE = "voice_guide"
        const val KEY_CONSENTED = "consented"
        const val KEY_PARK_TIMEOUT = "park_timeout_min"
        const val KEY_MIN_TRIP = "min_trip_km"
        const val KEY_LABELS = "purpose_labels"
    }
}
