package com.ohav.noted.places

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.ohav.noted.BuildConfig
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Google Places (New) search through our Cloud Function (`functions/places.js`): the Places key
 * lives on the server only, so nothing in the APK can be pulled out and spent. Needs a signed-in
 * account (the function caps each person and everyone per day); otherwise — or when the service
 * is off / capped — callers fall back to the on-device Geocoder. A typing session and its final
 * lookup share one token, which Google bills as a single lookup.
 */
object PlacesApi {
    /** A Maps SDK key is in this build (the map itself; search availability is [search]). */
    val enabled: Boolean get() = BuildConfig.MAPS_API_KEY.isNotEmpty()

    data class Suggestion(val id: String, val name: String, val address: String, val meters: Int?)

    fun session(): String = UUID.randomUUID().toString()

    private fun search(ctx: Context): Boolean = runCatching {
        FirebaseApp.getApps(ctx).isNotEmpty() && FirebaseAuth.getInstance().currentUser != null
    }.getOrDefault(false)

    private suspend fun call(data: Map<String, Any?>): Map<*, *> =
        FirebaseFunctions.getInstance(REGION).getHttpsCallable("places")
            .apply { setTimeout(12, TimeUnit.SECONDS) }
            .call(data).await().data as Map<*, *>

    /** Suggestions for [q] near [near]; null when the service can't be used (→ Geocoder). */
    suspend fun autocomplete(ctx: Context, q: String, near: Location?, session: String): List<Suggestion>? {
        if (!search(ctx)) return null
        return runCatching {
            val req = mutableMapOf<String, Any?>("op" to "auto", "input" to q.take(120), "session" to session)
            if (near != null) { req["lat"] = near.latitude; req["lng"] = near.longitude }
            (call(req)["suggestions"] as? List<*>).orEmpty().mapNotNull { o ->
                val m = o as? Map<*, *> ?: return@mapNotNull null
                Suggestion(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    name = m["name"] as? String ?: return@mapNotNull null,
                    address = m["address"] as? String ?: "",
                    meters = (m["meters"] as? Number)?.toInt(),
                )
            }
        }.onFailure { Log.w("PlacesApi", "autocomplete: ${it.message}") }.getOrNull()
    }

    /** The point behind a suggestion (ends the session). */
    suspend fun details(ctx: Context, s: Suggestion, session: String): Found? {
        if (!search(ctx)) return null
        return runCatching {
            val m = call(mapOf("op" to "details", "id" to s.id, "session" to session))
            Found(
                name = s.name,
                address = (m["address"] as? String).orEmpty().ifEmpty { s.address },
                lat = (m["lat"] as Number).toDouble(), lng = (m["lng"] as Number).toDouble(),
                types = (m["types"] as? List<*>).orEmpty().filterIsInstance<String>(),
            )
        }.onFailure { Log.w("PlacesApi", "details: ${it.message}") }.getOrNull()
    }

    private const val REGION = "me-west1"
}
