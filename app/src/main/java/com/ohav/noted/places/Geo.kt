package com.ohav.noted.places

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume

/** A point found by search, by the current location or by a pasted link. */
data class Found(val name: String, val address: String, val lat: Double, val lng: Double)

/**
 * Location, permissions and addresses — no API key: the fused provider for "where am I", the
 * platform [Geocoder] (Hebrew) for addresses and search, and a parser for pasted links.
 */
object Geo {
    private val HE = Locale.forLanguageTag("he-IL")

    fun hasFine(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarse(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Fences need "allow all the time" from Android 10 on; without it nothing fails — it just never rings. */
    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** Everything fences need to ring. */
    fun ready(ctx: Context) = hasFine(ctx) && hasBackground(ctx)

    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** A fresh precise fix (up to ~12 s), else the last known one; null without permission. */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasFine(ctx) && !hasCoarse(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val prio = if (hasFine(ctx)) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val cts = CancellationTokenSource()
        val fresh = runCatching {
            withTimeoutOrNull(12_000) { client.getCurrentLocation(prio, cts.token).await() }
        }.getOrNull()
        if (fresh == null) cts.cancel()
        return fresh ?: runCatching { client.lastLocation.await() }.getOrNull()
    }

    /** Last known location without waiting — for biasing search and the radar. */
    @SuppressLint("MissingPermission")
    suspend fun last(ctx: Context): Location? {
        if (!hasFine(ctx) && !hasCoarse(ctx)) return null
        return runCatching { LocationServices.getFusedLocationProviderClient(ctx).lastLocation.await() }.getOrNull()
    }

    fun distance(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val out = FloatArray(2)
        Location.distanceBetween(aLat, aLng, bLat, bLng, out)
        return out[0]
    }

    /** Initial bearing a → b, degrees clockwise from north. */
    fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val out = FloatArray(2)
        Location.distanceBetween(aLat, aLng, bLat, bLng, out)
        return out[1]
    }

    /** "250 מ׳" / "1.4 ק״מ" / "32 ק״מ". */
    fun fmtDistance(m: Float): String = when {
        m < 950 -> "${(m / 10).toInt() * 10} מ׳"
        m < 10_000 -> String.format(Locale.US, "%.1f ק״מ", m / 1000)
        else -> "${(m / 1000).toInt()} ק״מ"
    }

    fun fmtRadius(m: Int): String = if (m < 1000) "$m מ׳" else if (m % 1000 == 0) "${m / 1000} ק״מ" else String.format(Locale.US, "%.1f ק״מ", m / 1000f)

    // ------------------------------------------------------------------ addresses

    suspend fun reverse(ctx: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        val g = Geocoder(ctx, HE)
        val list: List<Address>? = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { k ->
                    g.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (k.isActive) k.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (k.isActive) k.resume(null) }
                    })
                }
            }
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { g.getFromLocation(lat, lng, 1) }.getOrNull()
        }
        return list?.firstOrNull()?.let { line(it) }
    }

    /** Address / business search, biased to ~60 km round [near] when we know where we are. */
    suspend fun search(ctx: Context, q: String, near: Location?): List<Found> {
        if (!Geocoder.isPresent() || q.isBlank()) return emptyList()
        val g = Geocoder(ctx, HE)
        val box = near?.let { doubleArrayOf(it.latitude - .55, it.longitude - .55, it.latitude + .55, it.longitude + .55) }
        suspend fun run(bounded: Boolean): List<Address> = if (Build.VERSION.SDK_INT >= 33) {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { k ->
                    val l = object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) { if (k.isActive) k.resume(addresses) }
                        override fun onError(errorMessage: String?) { if (k.isActive) k.resume(emptyList()) }
                    }
                    if (bounded && box != null) g.getFromLocationName(q, 8, box[0], box[1], box[2], box[3], l)
                    else g.getFromLocationName(q, 8, l)
                }
            } ?: emptyList()
        } else withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching {
                if (bounded && box != null) g.getFromLocationName(q, 8, box[0], box[1], box[2], box[3]) else g.getFromLocationName(q, 8)
            }.getOrNull().orEmpty()
        }
        val hits = run(true).ifEmpty { if (box != null) run(false) else emptyList() }
        return hits.filter { it.hasLatitude() && it.hasLongitude() }.map { a ->
            val full = line(a)
            val feature = a.featureName?.trim().orEmpty()
            // the geocoder's "feature" is often just the house number — then the street leads
            val named = feature.isNotEmpty() && feature.any { it.isLetter() } && !full.startsWith(feature)
            val name = if (named) feature else full.substringBefore(",").trim()
            val rest = if (named) full else full.substringAfter(",", "").trim()
            Found(name.ifEmpty { q.trim() }, rest, a.latitude, a.longitude)
        }.distinctBy { "%.4f,%.4f".format(Locale.US, it.lat, it.lng) }
            .sortedBy { f -> near?.let { distance(it.latitude, it.longitude, f.lat, f.lng) } ?: 0f }
    }

    private fun line(a: Address): String {
        val raw = a.getAddressLine(0)?.trim().orEmpty().ifEmpty {
            listOfNotNull(
                listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null },
                a.locality,
            ).joinToString(", ")
        }
        // the country adds nothing in a list of local places
        val country = a.countryName
        return raw.split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != country && it != "ישראל" && it != "Israel" && !it.all { ch -> ch.isDigit() || ch == ' ' } }
            .joinToString(", ")
    }

    // ------------------------------------------------------------------ pasted links

    private val PAIR = Regex("""(-?\d{1,2}\.\d{3,})\s*[,;\s]\s*(-?\d{1,3}\.\d{3,})""")
    private val AT = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val BANG = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val PARAM = Regex("""[?&](?:q|query|ll|center|destination|daddr|sll)=(-?\d{1,2}\.\d+)(?:,|%2C)(-?\d{1,3}\.\d+)""", RegexOption.IGNORE_CASE)
    private val GEO = Regex("""geo:(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val SHORT = Regex("""(maps\.app\.goo\.gl|goo\.gl/maps|waze\.com/ul/h|g\.co/kgs)""", RegexOption.IGNORE_CASE)

    /** A coordinate pair from a Google Maps / Waze / Apple Maps link, `geo:` or plain "lat, lng". */
    fun parse(text: String): Pair<Double, Double>? {
        val t = runCatching { URLDecoder.decode(text.trim(), "UTF-8") }.getOrDefault(text.trim())
        val m = BANG.find(t) ?: AT.find(t) ?: PARAM.find(t) ?: GEO.find(t) ?: PAIR.find(t) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lng = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) return null
        return lat to lng
    }

    /** A shortened link has no coordinates in it — worth saying so instead of failing quietly. */
    fun isShortLink(text: String) = SHORT.containsMatchIn(text)
}
