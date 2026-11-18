package com.duragim.fakelocation

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenStreetMap Nominatim ile adres/yer adı araması.
 * Kullanım kuralı gereği saniyede en fazla bir istek atılır ve uygulama kendini tanıtır.
 */
object PlaceSearch {
    data class Result(val name: String, val detail: String, val lat: Double, val lng: Double)

    private const val BASE = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "DuragimSahteKonum/1.1 (Android)"
    private var lastRequest = 0L

    /** Önce Türkiye içinde arar, sonuç yoksa tüm dünyada. */
    suspend fun search(query: String): List<Result> =
        request(query, "tr").ifEmpty { request(query, null) }

    private suspend fun request(query: String, country: String?): List<Result> = withContext(Dispatchers.IO) {
        val wait = 1100 - (SystemClock.elapsedRealtime() - lastRequest)
        if (wait > 0) delay(wait)
        lastRequest = SystemClock.elapsedRealtime()

        val url = buildString {
            append(BASE).append("?format=jsonv2&limit=8&accept-language=tr&q=")
            append(URLEncoder.encode(query, "UTF-8"))
            if (country != null) append("&countrycodes=").append(country)
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val parts = o.optString("display_name").split(", ")
                val title = o.optString("name").ifBlank { parts.firstOrNull().orEmpty() }
                // "Mahalle, İlçe, İl" gibi kısa bir alt satır; ülke ve posta kodu atılır
                val detail = parts.drop(1)
                    .filterNot { it == "Türkiye" || it.endsWith("Bölgesi") || it.all(Char::isDigit) }
                    .take(3).joinToString(", ")
                Result(title, detail, o.getString("lat").toDouble(), o.getString("lon").toDouble())
            }
        } finally {
            c.disconnect()
        }
    }
}
