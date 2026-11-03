package com.ohav.noted.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Per-note privacy, kept on this device only (never synced or shared): blur the title and/or the
 * body on the home stack, and lock the note behind one app code (+ fingerprint).
 * A locked note opens for one visit: [unlock] it, and it locks again once the editor lets go ([relock]).
 */
class Privacy(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("privacy", Context.MODE_PRIVATE)
    private val app = context.applicationContext

    private val _flags = MutableStateFlow(load())
    /** note id → [BLUR_TITLE] | [BLUR_BODY] | [LOCK]. */
    val flags: StateFlow<Map<String, Int>> = _flags

    /** The locked note open right now (unlocked for this visit), or null. */
    var unlocked by mutableStateOf<String?>(null)
        private set

    private fun load(): Map<String, Int> = prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith("n:") && v is Int && v != 0) k.removePrefix("n:") to v else null
    }.toMap()

    fun of(id: String): Int = _flags.value[id] ?: 0
    fun locked(id: String) = of(id) and LOCK != 0

    /** Whether the note can be shown now: not locked, or unlocked for this visit. */
    fun open(id: String) = !locked(id) || unlocked == id

    fun set(id: String, flags: Int) {
        prefs.edit().apply { if (flags == 0) remove("n:$id") else putInt("n:$id", flags) }.apply()
        _flags.value = _flags.value.toMutableMap().apply { if (flags == 0) remove(id) else put(id, flags) }
    }

    fun setBlur(id: String, title: Boolean, body: Boolean) =
        set(id, (of(id) and LOCK) or (if (title) BLUR_TITLE else 0) or (if (body) BLUR_BODY else 0))

    fun setLocked(id: String, on: Boolean) = set(id, if (on) of(id) or LOCK else of(id) and LOCK.inv())

    fun unlock(id: String) { unlocked = id }
    fun relock() { unlocked = null }

    // ------------------------------------------------------------ the code

    val hasCode: Boolean get() = prefs.contains("code")

    /** Fingerprint unlocks too (on by default where the phone has one). */
    var biometric: Boolean
        get() = prefs.getBoolean("bio", true)
        set(v) { prefs.edit().putBoolean("bio", v).apply() }

    fun setCode(code: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("code", b64(salt) + ":" + b64(hash(code, salt)))
            .remove("fails").remove("waitUntil")
            .apply()
    }

    /** Millis left before another try is allowed (after [MAX_FAILS] wrong codes in a row). */
    fun waitLeft(): Long {
        val until = prefs.getLong("waitUntil", 0L)
        val left = until - SystemClock.elapsedRealtime()
        // a reboot resets elapsed time: never wait longer than one full wait
        return if (left in 1..WAIT_MS) left else 0L
    }

    fun check(code: String): Boolean {
        if (waitLeft() > 0) return false
        val stored = prefs.getString("code", null) ?: return false
        val (salt, h) = stored.split(":").let { unb64(it[0]) to unb64(it[1]) }
        val ok = MessageDigest.isEqual(hash(code, salt), h)
        if (ok) prefs.edit().remove("fails").remove("waitUntil").apply()
        else {
            val fails = prefs.getInt("fails", 0) + 1
            prefs.edit().apply {
                if (fails >= MAX_FAILS) { putInt("fails", 0); putLong("waitUntil", SystemClock.elapsedRealtime() + WAIT_MS) }
                else putInt("fails", fails)
            }.apply()
        }
        return ok
    }

    /** The phone can do a fingerprint / face check at all (platform BiometricPrompt, API 28+). */
    fun biometricAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        return when {
            Build.VERSION.SDK_INT >= 30 -> app.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
                ?.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
            Build.VERSION.SDK_INT >= 29 -> legacyCanAuthenticate()
            else -> app.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        }
    }

    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresApi(29)
    private fun legacyCanAuthenticate(): Boolean =
        app.getSystemService(android.hardware.biometrics.BiometricManager::class.java)?.canAuthenticate() ==
            android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS

    private fun hash(code: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(code.toCharArray(), salt, 20_000, 256)).encoded

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        const val BLUR_TITLE = 1
        const val BLUR_BODY = 2
        const val LOCK = 4
        const val CODE_LEN = 4
        const val MAX_FAILS = 5
        const val WAIT_MS = 30_000L
    }
}
