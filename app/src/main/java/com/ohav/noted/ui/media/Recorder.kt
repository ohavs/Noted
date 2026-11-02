package com.ohav.noted.ui.media

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ohav.noted.data.Media
import com.ohav.noted.data.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Records the microphone into AAC (m4a) while measuring the level for the waveform. On Android 13+
 * the same audio is piped into the speech recognizer (on-device when it can), so the transcript
 * appears live while recording and the recording itself is kept beside it.
 */
@Stable
class Recorder(private val ctx: Context) {
    var active by mutableStateOf(false); private set
    var elapsed by mutableLongStateOf(0L); private set
    /** Live levels 0..1, one per 50 ms. */
    val levels = mutableStateListOf<Float>()
    var transcript by mutableStateOf(""); private set
    var partial by mutableStateOf(""); private set
    /** null = not available here (below Android 13 / no recognizer), else whether it's still listening. */
    var listening by mutableStateOf<Boolean?>(null); private set

    private val scope = MainScope()
    private var job: Job? = null
    @Volatile private var running = false
    private var file: File? = null
    private var name = ""
    private val all = ArrayList<Float>()

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var pipe: OutputStream? = null
    private var feed: Channel<ByteArray>? = null
    private var feedJob: Job? = null
    private var triedNetwork = false
    private var segmented = false

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (active) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, RATE * 2))
        }.getOrNull() ?: return false
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        name = Media.newName("m4a")
        val out = Media.file(ctx, name)
        file = out
        levels.clear(); all.clear(); transcript = ""; partial = ""; elapsed = 0L
        triedNetwork = false
        startRecognizer(onDevice = true)
        runCatching { rec.startRecording() }.onFailure { rec.release(); stopRecognizer(); return false }
        running = true
        active = true
        job = scope.launch(Dispatchers.IO) { runCatching { encode(rec, out) } }
        return true
    }

    /** Stops and returns the finished recording (null if nothing usable was captured). */
    suspend fun stop(): Voice? {
        if (!active) return null
        running = false
        job?.join()
        closePipe()
        // the last words arrive after the audio ends
        if (listening == true) withTimeoutOrNull(2500) { while (listening == true) delay(50) }
        stopRecognizer()
        active = false
        val f = file ?: return null
        val ms = elapsed
        if (ms < 400 || !f.exists() || f.length() == 0L) { f.delete(); return null }
        val text = (transcript + " " + partial).trim()
        return Voice(name, ms, bars(all, 56), text)
    }

    fun cancel() {
        if (!active) return
        running = false
        scope.launch {
            job?.join()
            closePipe()
            stopRecognizer()
            file?.delete()
            active = false
        }
    }

    fun release() { cancel(); scope.launch { delay(3000); scope.cancel() } }

    // ---- audio ------------------------------------------------------------------------------

    private suspend fun encode(rec: AudioRecord, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 4)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        var samples = 0L

        fun drain(end: Boolean) {
            var idle = 0
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++idle > 100) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start(); muxing = true
                    }
                    i >= 0 -> {
                        val ob = codec.getOutputBuffer(i)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (ob != null && info.size > 0 && muxing) {
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(track, ob, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun queue(bytes: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii < 0) { drain(false); continue }
                val ib = codec.getInputBuffer(ii) ?: continue
                ib.clear()
                val k = min(ib.remaining(), len - off)
                ib.put(bytes, off, k)
                codec.queueInputBuffer(ii, 0, k, samples * 1_000_000L / RATE, 0)
                off += k
                samples += k / 2
            }
        }

        val shorts = ShortArray(CHUNK)
        try {
            while (running) {
                val n = rec.read(shorts, 0, CHUNK)
                if (n <= 0) continue
                val bytes = ByteArray(n * 2)
                var sum = 0.0
                for (k in 0 until n) {
                    val v = shorts[k].toInt()
                    bytes[2 * k] = (v and 0xff).toByte()
                    bytes[2 * k + 1] = (v shr 8).toByte()
                    sum += v.toDouble() * v
                }
                feed?.trySend(bytes)
                queue(bytes, bytes.size)
                drain(false)
                val rms = sqrt(sum / n) / 32768.0
                val db = 20 * log10(max(rms, 1e-6))
                val level = ((db + 52) / 46).toFloat().coerceIn(0f, 1f)
                all += level
                val ms = samples * 1000 / RATE
                withContext(Dispatchers.Main) { levels.add(level); if (levels.size > 400) levels.removeAt(0); elapsed = ms }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            runCatching {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) codec.queueInputBuffer(ii, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
            runCatching { codec.stop() }
            codec.release()
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    // ---- live transcription -----------------------------------------------------------------

    private fun startRecognizer(onDevice: Boolean) {
        if (Build.VERSION.SDK_INT < 33) { listening = null; return }
        val canDevice = onDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        if (!canDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) { listening = null; return }
        runCatching {
            val p = ParcelFileDescriptor.createPipe()
            val sr = if (canDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            sr.setRecognitionListener(listener(canDevice))
            val ch = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)
            val os = ParcelFileDescriptor.AutoCloseOutputStream(p[1])
            pipe = os
            feed = ch
            // the pipe may block if the recognizer stalls; that must never hold up the recording
            feedJob = CoroutineScope(Dispatchers.IO).launch {
                runCatching { for (b in ch) { if (!isActive) break; os.write(b) } }
                runCatching { os.close() }
            }
            sr.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, p[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                },
            )
            recognizer = sr
            listening = true
        }.onFailure { closePipe(); listening = null }
    }

    private fun listener(onDevice: Boolean) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partial = first(partialResults)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            segmented = true
            append(first(segmentResults))
        }

        override fun onResults(results: Bundle?) {
            if (!segmented) append(first(results))
            partial = ""
            listening = false
        }

        override fun onEndOfSegmentedSession() {
            partial = ""
            listening = false
        }

        override fun onError(error: Int) {
            val lang = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            // on-device model missing for Hebrew → try the regular recognizer once
            if (onDevice && !triedNetwork && running && (lang || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER)) {
                triedNetwork = true
                stopRecognizer()
                startRecognizer(onDevice = false)
                return
            }
            if (partial.isNotBlank()) append(partial)
            partial = ""
            listening = false
        }
    }

    private fun append(s: String) {
        if (s.isBlank()) return
        transcript = (if (transcript.isEmpty()) s else "$transcript $s").trim()
        partial = ""
    }

    private fun first(b: Bundle?): String = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun closePipe() {
        feed?.close(); feed = null
        val os = pipe; pipe = null
        feedJob?.let { j -> scope.launch(Dispatchers.IO) { delay(300); j.cancel(); runCatching { os?.close() } } }
        feedJob = null
    }

    private fun stopRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        if (listening == true) listening = false
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 800 // 50 ms
        const val LANG = "he-IL"

        /** Peaks of [levels] in [n] bars, 0..100. */
        fun bars(levels: List<Float>, n: Int): List<Int> {
            if (levels.isEmpty()) return List(n) { 0 }
            return List(n) { i ->
                val a = i * levels.size / n
                val b = max(a + 1, (i + 1) * levels.size / n)
                var m = 0f
                for (k in a until min(b, levels.size)) m = max(m, levels[k])
                (m * 100).roundToInt()
            }
        }
    }
}

/** One player for every recording in the app; starting another stops the current one. */
object VoicePlayer {
    var current by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    /** 0..1 of the current recording. */
    var progress by mutableFloatStateOf(0f); private set

    private var mp: MediaPlayer? = null
    private val scope = MainScope()
    private var tick: Job? = null

    fun toggle(ctx: Context, name: String, startAt: Float? = null) {
        val p = mp
        if (current == name && p != null) {
            if (startAt != null) seek(startAt)
            if (playing) { p.pause(); playing = false; tick?.cancel() } else { p.start(); playing = true; follow() }
            return
        }
        stop()
        val f = Media.file(ctx, name)
        if (!f.exists()) return
        runCatching {
            val n = MediaPlayer()
            n.setDataSource(f.path)
            n.setOnCompletionListener { playing = false; progress = 0f; tick?.cancel(); runCatching { it.seekTo(0) } }
            n.prepare()
            if (startAt != null) n.seekTo((startAt * n.duration).roundToInt())
            n.start()
            mp = n
            current = name
            playing = true
            follow()
        }
    }

    /** Plays [name] from [f] (0..1), whether or not it was the current one. */
    fun playAt(ctx: Context, name: String, f: Float) {
        if (current == name && mp != null) { seek(f); if (!playing) toggle(ctx, name) } else toggle(ctx, name, startAt = f)
    }

    fun seek(f: Float) {
        val p = mp ?: return
        runCatching { p.seekTo((f.coerceIn(0f, 1f) * p.duration).roundToInt()); progress = f.coerceIn(0f, 1f) }
    }

    private fun follow() {
        tick?.cancel()
        tick = scope.launch {
            while (isActive) {
                mp?.let { p -> runCatching { if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration } }
                delay(16)
            }
        }
    }

    fun stop() {
        tick?.cancel()
        mp?.let { runCatching { it.stop() }; it.release() }
        mp = null
        current = null
        playing = false
        progress = 0f
    }
}
