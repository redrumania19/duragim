package com.duragim.fakelocation

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** "Ev", "İş" gibi isimli konumları cihazda saklar. */
class SavedPlaces(private val prefs: SharedPreferences) {
    data class Place(val name: String, val lat: Double, val lng: Double)

    fun all(): List<Place> {
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Place(o.optString("n"), o.optDouble("lat"), o.optDouble("lng"))
        }
    }

    /** Aynı isimde bir yer varsa üzerine yazar. */
    fun save(p: Place) = write(all().filterNot { it.name.equals(p.name, ignoreCase = true) } + p)

    fun delete(name: String) = write(all().filterNot { it.name == name })

    private fun write(list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("lat", it.lat).put("lng", it.lng)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "places" }
}
