package com.ohav.noted.places

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ohav.noted.NotedApp
import com.ohav.noted.data.PlaceKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A paired Bluetooth device, as the device picker lists it. */
data class BtDevice(val address: String, val name: String, val kind: PlaceKind, val connected: Boolean)

/**
 * Bluetooth devices as "places": the car connecting is an arrival, it disconnecting a departure.
 * The system tells [BluetoothReceiver] about every link (ACL) going up or down — with the app
 * dead too, the two broadcasts are exempt from the background limits. [Bt] keeps what it last
 * heard per device, so a car stereo that drops and comes back within a minute isn't a new trip.
 */
object Bt {
    /** Addresses linked right now, as far as the broadcasts (and the profiles, on open) say. */
    private val _connected = MutableStateFlow<Set<String>>(emptySet())
    val connected: StateFlow<Set<String>> = _connected

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("bt", Context.MODE_PRIVATE)

    /** Android 12+: "nearby devices". Before that the install-time permission is enough. */
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val permission: String? get() = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null

    private fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun available(ctx: Context): Boolean = adapter(ctx) != null

    fun on(ctx: Context): Boolean = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    /** A car stereo / hands-free announces itself in its class; otherwise the name is the hint. */
    @SuppressLint("MissingPermission")
    fun kindOf(d: BluetoothDevice): PlaceKind {
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        val name = runCatching { d.name }.getOrNull().orEmpty()
        return kindOf(cls?.deviceClass, cls?.majorDeviceClass, name)
    }

    fun kindOf(deviceClass: Int?, major: Int?, name: String): PlaceKind {
        val n = name.lowercase()
        val carWords = listOf("car", "auto", "carplay", "android auto", "sync", "uconnect", "mylink", "mbux", "idrive", "toyota", "mazda", "kia", "hyundai", "skoda", "seat", "vw", "volkswagen", "tesla", "ford", "honda", "nissan", "subaru", "renault", "peugeot", "citroen", "chevrolet", "mitsubishi", "suzuki", "bmw", "audi", "mercedes", "volvo", "jeep", "byd", "mg ", "רכב", "אוטו")
        return when {
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> PlaceKind.Car
            carWords.any { n.contains(it) } -> PlaceKind.Car
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                listOf("buds", "airpods", "headphone", "earbud", "wh-", "wf-", "jabra", "bose qc").any { n.contains(it) } -> PlaceKind.Headphones
            deviceClass == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO ||
                listOf("speaker", "jbl", "boom", "soundlink", "sonos").any { n.contains(it) } -> PlaceKind.Speaker
            else -> PlaceKind.Gadget
        }
    }

    /**
     * The paired devices (cars first, then the connected ones), with who is linked now. The
     * profiles answer asynchronously — at most ~1.5s, then whatever the broadcasts said.
     */
    @SuppressLint("MissingPermission")
    suspend fun paired(ctx: Context): List<BtDevice> {
        if (!permitted(ctx)) return emptyList()
        val a = adapter(ctx) ?: return emptyList()
        val bonded = runCatching { a.bondedDevices?.toList() }.getOrNull().orEmpty()
        val now = connectedNow(ctx)
        return bonded.map { d ->
            BtDevice(d.address, runCatching { (if (Build.VERSION.SDK_INT >= 30) d.alias else null) ?: d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address, kindOf(d), d.address in now)
        }.sortedWith(compareBy<BtDevice>({ it.kind != PlaceKind.Car }, { !it.connected }, { it.name.lowercase() }))
    }

    /** Who is linked right now: audio / hands-free profiles, plus what the broadcasts last said. */
    @SuppressLint("MissingPermission")
    suspend fun connectedNow(ctx: Context): Set<String> {
        if (!permitted(ctx)) return emptySet()
        val a = adapter(ctx) ?: return emptySet()
        if (!on(ctx)) return emptySet()
        val out = HashSet<String>()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            val got = CompletableDeferred<List<BluetoothDevice>>()
            val ok = runCatching {
                a.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        got.complete(runCatching { proxy.connectedDevices }.getOrDefault(emptyList()))
                        runCatching { a.closeProfileProxy(p, proxy) }
                    }
                    override fun onServiceDisconnected(p: Int) { got.complete(emptyList()) }
                }, profile)
            }.getOrDefault(false)
            if (ok) withTimeoutOrNull(1500) { got.await() }?.forEach { out += it.address }
        }
        // a car may link only for calls or only for media; the broadcast state fills the gaps
        out += linked(ctx)
        _connected.value = out
        return out
    }

    /** What the broadcasts last said is linked (survives the process). */
    fun linked(ctx: Context): Set<String> =
        prefs(ctx).all.filter { (k, v) -> k.startsWith("on:") && v == true }.keys.map { it.removePrefix("on:") }.toSet()

    /**
     * Records a link going up / down; returns whether it is a real change to act on. A drop of
     * less than [FLAP_MS] that comes back is the same trip (a stereo restarting, a weak link).
     */
    fun record(ctx: Context, address: String, up: Boolean): Boolean {
        val p = prefs(ctx)
        val was = p.getBoolean("on:$address", false)
        val now = System.currentTimeMillis()
        val lastDown = p.getLong("down:$address", 0L)
        p.edit().putBoolean("on:$address", up).apply { if (!up) putLong("down:$address", now) }.apply()
        _connected.value = if (up) _connected.value + address else _connected.value - address
        if (up == was) return false
        if (up && now - lastDown < FLAP_MS) return false
        return true
    }

    const val FLAP_MS = 60_000L
}

/** Bluetooth links going up and down. Runs with the app dead (both broadcasts are exempt). */
class BluetoothReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? NotedApp ?: return
        val up = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val d: BluetoothDevice = (
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ) ?: return
        val address = d.address ?: return
        if (!Bt.record(context, address, up)) return
        val pending = goAsync()
        app.scope.launch {
            try {
                app.places.device(address, up)
            } finally {
                pending.finish()
            }
        }
    }
}
