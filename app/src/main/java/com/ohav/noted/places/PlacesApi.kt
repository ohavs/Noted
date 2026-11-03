package com.ohav.noted.places

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.ohav.noted.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/**
 * Google Places (New) over REST: suggestions while typing and the chosen place's point. The key
 * is locked to this app's package and signature, so every call says who it is (package +
 * certificate SHA-1). A typing session and its final lookup share one token — Google bills
 * them as a single lookup. Each device stops after [DAILY_CAP] calls a day (a runaway loop or a
 * very busy day can't run up a bill) and the search falls back to the on-device Geocoder.
 */
object PlacesApi {
    val enabled: Boolean get() = BuildConfig.MAPS_API_KEY.isNotEmpty()

    data class Suggestion(val id: String, val name: String, val address: String, val meters: Int?)

    fun session(): String = UUID.randomUUID().toString()

    /** Suggestions for [q] near [near]; null when the service couldn't be reached. */
    suspend fun autocomplete(ctx: Context, q: String, near: Location?, session: String): List<Suggestion>? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("input", q)
                .put("languageCode", "he")
                .put("regionCode", "il")
                .put("sessionToken", session)
            if (near != null) {
                val center = JSONObject().put("latitude", near.latitude).put("longitude", near.longitude)
                body.put("locationBias", JSONObject().put("circle", JSONObject().put("center", center).put("radius", 30_000.0)))
                body.put("origin", center)
            }
            val res = call(ctx, URL("$BASE/places:autocomplete"), body.toString(), null)
            val arr = res.optJSONArray("suggestions") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.getJSONObject(i).optJSONObject("placePrediction") ?: return@mapNotNull null
                val f = p.optJSONObject("structuredFormat")
                val main = f?.optJSONObject("mainText")?.optString("text").orEmpty()
                val rest = f?.optJSONObject("secondaryText")?.optString("text").orEmpty()
                val whole = p.optJSONObject("text")?.optString("text").orEmpty()
                Suggestion(
                    id = p.optString("placeId"),
                    name = main.ifEmpty { whole.substringBefore(",").trim() },
                    address = rest.ifEmpty { whole },
                    meters = if (p.has("distanceMeters")) p.optInt("distanceMeters") else null,
                ).takeIf { it.id.isNotEmpty() && it.name.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** The point behind a suggestion (ends the session). */
    suspend fun details(ctx: Context, s: Suggestion, session: String): Found? = withContext(Dispatchers.IO) {
        runCatching {
            val q = "languageCode=he&regionCode=il&sessionToken=" + URLEncoder.encode(session, "UTF-8")
            val res = call(ctx, URL("$BASE/places/${URLEncoder.encode(s.id, "UTF-8")}?$q"), null, "id,location,formattedAddress,types")
            val loc = res.getJSONObject("location")
            val types = res.optJSONArray("types")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Found(
                name = s.name,
                address = res.optString("formattedAddress").ifEmpty { s.address },
                lat = loc.getDouble("latitude"), lng = loc.getDouble("longitude"),
                types = types,
            )
        }.getOrNull()
    }

    private const val DAILY_CAP = 150

    /** Counts today's calls (local date); false once the cap is reached. */
    @Synchronized
    private fun spend(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("places_api", Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        val used = if (prefs.getString("day", null) == today) prefs.getInt("used", 0) else 0
        if (used >= DAILY_CAP) return false
        prefs.edit().putString("day", today).putInt("used", used + 1).apply()
        return true
    }

    private fun call(ctx: Context, url: URL, post: String?, fields: String?): JSONObject {
        check(spend(ctx)) { "places: daily cap" }
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
            c.setRequestProperty("X-Android-Package", ctx.packageName)
            cert(ctx)?.let { c.setRequestProperty("X-Android-Cert", it) }
            if (fields != null) c.setRequestProperty("X-Goog-FieldMask", fields)
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("places $code: ${text.take(200)}")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    @Volatile private var certCache: String? = null

    /** SHA-1 of the signing certificate, upper-case hex without separators (what the key is locked to). */
    private fun cert(ctx: Context): String? = certCache ?: runCatching {
        val pm = ctx.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()?.also { certCache = it }

    private const val BASE = "https://places.googleapis.com/v1"
}
