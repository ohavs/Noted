package com.ohav.noted.ui.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.OutputStream

/**
 * Words from a finished recording (one that came from the watch, where nothing listened live):
 * the AAC is decoded to 16-bit PCM and fed to the speech recognizer through a pipe — on the device
 * first, the regular recognizer if Hebrew isn't installed there. Android 13+ only; null otherwise.
 */
object FileTranscriber {
    suspend fun transcribe(ctx: Context, file: File, ms: Long): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val app = ctx.applicationContext
        val device = withContext(Dispatchers.Main) { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }
        val first = if (device) run(app, file, ms, onDevice = true) else null
        if (first is Outcome.Text) return first.text
        if (first is Outcome.Empty) return null
        if (!withContext(Dispatchers.Main) { SpeechRecognizer.isRecognitionAvailable(app) }) return null
        return (run(app, file, ms, onDevice = false) as? Outcome.Text)?.text
    }

    private sealed interface Outcome {
        data class Text(val text: String) : Outcome
        /** Heard nothing worth keeping. */
        data object Empty : Outcome
        /** This recognizer can't (language missing, client error): try the other one. */
        data object Failed : Outcome
    }

    private suspend fun run(ctx: Context, file: File, ms: Long, onDevice: Boolean): Outcome = coroutineScope {
        val fmt = withContext(Dispatchers.IO) { probe(file) } ?: return@coroutineScope Outcome.Failed
        val pipe = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        val done = CompletableDeferred<Outcome>()
        val words = StringBuilder()
        var segmented = false
        fun add(b: Bundle?) {
            val s = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (s.isNotEmpty()) { if (words.isNotEmpty()) words.append(' '); words.append(s) }
        }
        fun finish() = done.complete(if (words.isBlank()) Outcome.Empty else Outcome.Text(words.toString()))

        val sr = withContext(Dispatchers.Main) {
            val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onSegmentResults(segmentResults: Bundle) { segmented = true; add(segmentResults) }
                override fun onEndOfSegmentedSession() { finish() }
                override fun onResults(results: Bundle?) { if (!segmented) add(results); finish() }
                override fun onError(error: Int) {
                    val cant = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    if (words.isNotBlank()) finish() else done.complete(if (cant) Outcome.Failed else Outcome.Empty)
                }
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Recorder.LANG)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, fmt.rate)
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                },
            )
            r
        }
        val feed = launch(Dispatchers.IO) {
            runCatching { decode(file, out) }
            runCatching { out.close() }
        }
        // the recognizer works about as fast as the audio plays; past that it has stalled
        val result = withTimeoutOrNull(ms + 20_000L) { done.await() } ?: if (words.isNotBlank()) Outcome.Text(words.toString()) else Outcome.Failed
        feed.cancel()
        runCatching { out.close() }
        withContext(Dispatchers.Main) { runCatching { sr.destroy() } }
        runCatching { pipe[0].close() }
        result
    }

    private class Fmt(val rate: Int, val channels: Int)

    private fun probe(file: File): Fmt? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { Fmt(it.getInteger(MediaFormat.KEY_SAMPLE_RATE), it.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) }
        } catch (_: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /** AAC → mono 16-bit PCM into [out] (stereo is averaged down). */
    private fun decode(file: File, out: OutputStream) {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inEnd = false
        try {
            while (true) {
                if (!inEnd) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset); buf.get(bytes)
                        codec.releaseOutputBuffer(o, false)
                        out.write(if (channels <= 1) bytes else mono(bytes, channels))
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }

    private fun mono(b: ByteArray, ch: Int): ByteArray {
        val frames = b.size / (2 * ch)
        val out = ByteArray(frames * 2)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until ch) {
                val k = (f * ch + c) * 2
                sum += (b[k].toInt() and 0xFF) or (b[k + 1].toInt() shl 8)
            }
            val v = sum / ch
            out[f * 2] = v.toByte(); out[f * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }
}
