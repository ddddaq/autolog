package com.autolog.app.place

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 이름을 붙인 장소 목록을 보관한다. */
class PlaceStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("places", Context.MODE_PRIVATE)
    private val _places = MutableStateFlow(read())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    fun update(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        val next = transform(_places.value)
        _places.value = next
        val array = JSONArray()
        next.forEach { place ->
            val item = JSONObject().put("name", place.name).put("raw", JSONArray(place.rawNames.toList()))
            if (place.latitude != null && place.longitude != null) item.put("lat", place.latitude).put("lng", place.longitude)
            array.put(item)
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
        _places.value = emptyList()
    }

    private fun read(): List<SavedPlace> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val raw = item.optJSONArray("raw") ?: JSONArray()
            SavedPlace(
                name = item.optString("name"),
                latitude = if (item.has("lat")) item.getDouble("lat") else null,
                longitude = if (item.has("lng")) item.getDouble("lng") else null,
                rawNames = (0 until raw.length()).map { raw.getString(it) }.toSet(),
            )
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "places"
    }
}
