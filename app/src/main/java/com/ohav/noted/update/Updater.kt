package com.ohav.noted.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ohav.noted.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Check for updates": compares the latest GitHub Release with the installed build, downloads the
 * APK and hands it to the system installer. Releases are tagged v<major>.<minor>.<versionCode>.
 */
class Updater(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val versionName: String, val versionCode: Int, val url: String, val sizeBytes: Long, val notes: String) : State
        data class Downloading(val target: Available, val progress: Float) : State
        data class Ready(val target: Available, val file: File) : State
        data class NeedsPermission(val target: Available, val file: File) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val installedVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(quiet: Boolean = false) {
        val s = _state.value
        if (s is State.Checking || s is State.Downloading) return
        if (!quiet) _state.value = State.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val c = (URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 10_000; readTimeout = 15_000
                }
                try {
                    if (c.responseCode == 404) return@runCatching State.UpToDate
                    if (c.responseCode !in 200..299) error("GitHub ${c.responseCode}")
                    val o = JSONObject(c.inputStream.bufferedReader().readText())
                    val tag = o.getString("tag_name").removePrefix("v")
                    val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
                    val assets = o.getJSONArray("assets")
                    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") }
                    if (code <= BuildConfig.VERSION_CODE || apk == null) State.UpToDate
                    else State.Available(tag, code, apk.getString("browser_download_url"), apk.optLong("size"), o.optString("body"))
                } finally { c.disconnect() }
            }.getOrElse { State.Failed(if (it is java.io.IOException) "אין חיבור לרשת" else "הבדיקה נכשלה") }
        }
        _state.value = if (quiet && result !is State.Available) State.Idle else result
    }

    suspend fun download(target: State.Available) {
        _state.value = State.Downloading(target, 0f)
        val file = File(context.cacheDir, "updates/noted-${target.versionCode}.apk")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.listFiles()?.forEach { it.delete() }
                file.parentFile?.mkdirs()
                var url = URL(target.url)
                var c: HttpURLConnection
                var hops = 0
                while (true) { // GitHub redirects to its asset CDN
                    c = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000 }
                    if (c.responseCode in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
                    break
                }
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val total = c.contentLengthLong.takeIf { it > 0 } ?: target.sizeBytes
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); read += n
                            if (total > 0 && read - lastEmit > total / 100) { lastEmit = read; _state.value = State.Downloading(target, read.toFloat() / total) }
                        }
                    }
                }
                c.disconnect()
            }.isSuccess
        }
        _state.value = if (ok) State.Ready(target, file) else State.Failed("ההורדה נכשלה")
    }

    /** Opens the installer, or the "install unknown apps" screen first if the permission is missing. */
    fun install(): Boolean {
        val s = _state.value
        val (target, file) = when (s) {
            is State.Ready -> s.target to s.file
            is State.NeedsPermission -> s.target to s.file
            else -> return false
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(target, file)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        _state.value = State.Ready(target, file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** Called when the app returns to the foreground — continues after the permission screen. */
    fun onResume() {
        if (_state.value is State.NeedsPermission && context.packageManager.canRequestPackageInstalls()) install()
    }
}
