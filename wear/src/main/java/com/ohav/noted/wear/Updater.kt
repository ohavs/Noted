package com.ohav.noted.wear

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates" on the watch: the latest GitHub release's noted-watch-*.apk against the
 * installed version, downloaded and handed to the system installer. Wear OS builds without an
 * installer screen refuse — then the watch says to install the file from the phone.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val code: Int, val url: String, val size: Long) : State
        data class Downloading(val progress: Float) : State
        data object Installing : State
        data class Failed(val why: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    suspend fun check() {
        _state.value = State.Checking
        _state.value = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 15_000; c.readTimeout = 15_000
                val o = JSONObject(c.inputStream.use { it.readBytes().decodeToString() })
                val tag = o.getString("tag_name")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                val assets = o.getJSONArray("assets")
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").let { n -> n.endsWith(".apk") && "watch" in n } }
                if (apk == null || code <= BuildConfig.VERSION_CODE) State.UpToDate
                else State.Available(tag.removePrefix("v"), code, apk.getString("browser_download_url"), apk.optLong("size"))
            }.getOrElse { State.Failed("אין חיבור לאינטרנט") }
        }
    }

    suspend fun install(ctx: Context, a: State.Available) {
        _state.value = State.Downloading(0f)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(ctx.cacheDir, "updates/noted-watch-${a.code}.apk").apply { parentFile?.mkdirs() }
                val c = URL(a.url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                val total = c.contentLengthLong.takeIf { it > 0 } ?: a.size
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            if (total > 0) _state.value = State.Downloading((got.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val pi = ctx.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    s.openWrite("noted", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val p = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
                    s.commit(p.intentSender)
                }
                f.delete()
            }.isSuccess
        }
        _state.value = if (ok) State.Installing else State.Failed("אפשר להתקין את noted-watch-${a.version}.apk מהטלפון")
    }

    internal fun failed(why: String) { _state.value = State.Failed(why) }
}

/** The installer's answer: asks the person to confirm, or reports why it didn't go. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Updater.failed("השעון לא מאפשר התקנה מכאן — התקינו מהטלפון") }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Updater.failed("ההתקנה לא הושלמה — התקינו מהטלפון")
        }
    }
}
