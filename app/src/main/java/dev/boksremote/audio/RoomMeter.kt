package dev.boksremote.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import dev.boksremote.speaker.SpeakerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10

open class MeasurementException(message: String) : Exception(message)

/** The microphone clipped: the speaker is too loud for where the phone is. */
class TooLoudException : MeasurementException("Too loud for the microphone. Turn the volume down and try again.")

/**
 * Runs [block], turning the speaker down a step and trying again while the microphone overloads.
 * Close to a big speaker, a phone microphone clips well below listening volume. The speaker stays
 * at the volume that worked; callers put the user's volume back when they are done.
 */
suspend fun <T> SpeakerController.fitToMicrophone(block: suspend () -> T): T {
    while (true) {
        try {
            return block()
        } catch (e: TooLoudException) {
            val lower = ((state.value.volume ?: throw e) * 0.7).toInt()
            if (lower < MIN_MEASURE_VOLUME) throw e
            setVolume(lower)
            delay(500)
        }
    }
}

/** About 4 %; below this the speaker is too quiet to beat a room's background noise. */
private const val MIN_MEASURE_VOLUME = 10

/**
 * Measures the speaker through the phone's microphone with sine sweeps played over the current
 * media output (the speaker, over Bluetooth). Each sweep is deconvolved into an impulse response,
 * so the Bluetooth delay only moves the peak, and responses come out on [Spectrum.GRID].
 */
class RoomMeter(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sweep = Spectrum.logSweep(SWEEP_S)

    /** The microphone to record with; null is the phone's own. */
    var mic: AudioDeviceInfo? = null

    /**
     * Plugged-in microphones that can measure: USB (measurement microphones like a UMIK-1 or
     * iMM-6C) and wired. Bluetooth headsets are left out: their microphones only run in call mode,
     * which also drops the music to call quality.
     */
    fun externalMics(): List<AudioDeviceInfo> =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in EXTERNAL_MIC_TYPES }

    /** True when media plays through a Bluetooth speaker; measuring the phone's own speaker is pointless. */
    fun outputIsBluetooth(): Boolean = audio.getAudioDevicesForAttributes(MEDIA).firstOrNull()?.type.let {
        it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    /** What a [session] block can do while the output stream stays open. */
    interface Session {
        /** Plays [sweeps] sweeps and returns their power-averaged response in dB. */
        suspend fun measure(sweeps: Int = 1): DoubleArray

        /**
         * Background noise in the same units as [measure]: a silent recording run through the same
         * deconvolution and window. A response is only trustworthy where it clearly beats this.
         */
        suspend fun noiseFloor(): DoubleArray
    }

    /** One measurement in its own session. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun measure(sweeps: Int = 2): DoubleArray = session { measure(sweeps) }

    /**
     * Keeps the output stream playing (silence between sweeps) across several measurements. Bluetooth
     * audio takes a moment to wake up and can clip the start of a sound, which for a sweep is the bass.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun <T> session(block: suspend Session.() -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val queue = Channel<FloatArray>(Channel.UNLIMITED)
            val track = AudioTrack.Builder()
                .setAudioAttributes(MEDIA)
                .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val silence = FloatArray(CHUNK)
            val player = launch {
                var current: FloatArray? = null
                var pos = 0
                while (currentCoroutineContext().isActive) {
                    if (current == null) { current = queue.tryReceive().getOrNull(); pos = 0 }
                    val n: Int
                    val written = if (current != null) {
                        n = minOf(CHUNK, current.size - pos)
                        track.write(current, pos, n, AudioTrack.WRITE_BLOCKING)
                    } else {
                        n = CHUNK
                        track.write(silence, 0, n, AudioTrack.WRITE_BLOCKING)
                    }
                    if (written < 0) break // released
                    if (current != null) { pos += n; if (pos >= current.size) current = null }
                }
            }
            try {
                track.play()
                delay(WAKE_MS)
                object : Session {
                    override suspend fun measure(sweeps: Int): DoubleArray {
                        val responses = List(sweeps) {
                            val recorded = recordWhile { queue.trySend(sweep) }
                            checkLevel(recorded)
                            val ir = Spectrum.impulseResponse(recorded, sweep)
                            Spectrum.fdwResponseDb(ir, Spectrum.peakIndex(ir, MAX_DELAY))
                        }
                        // Average in power, so one disturbed sweep doesn't drag the result around.
                        return DoubleArray(responses[0].size) { g -> 10 * log10(responses.sumOf { Math.pow(10.0, it[g] / 10) } / sweeps) }
                    }

                    override suspend fun noiseFloor(): DoubleArray {
                        val recorded = recordWhile { }
                        val ir = Spectrum.impulseResponse(recorded, sweep)
                        // No peak to find: window from the typical delay so the length matches a measurement.
                        return Spectrum.fdwResponseDb(ir, peak = TYPICAL_DELAY)
                    }
                }.block()
            } finally {
                player.cancel()
                track.pause()
                track.flush()
                track.release()
            }
        }
    }

    /** Records long enough for the sweep plus the output buffer, Bluetooth delay and room decay. */
    private suspend fun recordWhile(start: () -> Unit): FloatArray = coroutineScope {
        val samples = ((SWEEP_S + TAIL_S) * Spectrum.SAMPLE_RATE).toInt()
        val recording = async(Dispatchers.IO) { recordSamples(samples) }
        delay(50) // let the recorder start first, so the recording begins before the sweep
        start()
        recording.await()
    }

    private fun checkLevel(recorded: FloatArray) {
        if (Spectrum.rmsDbfs(recorded) < -65) throw MeasurementException("Couldn't hear the speaker. Turn the volume up and try again.")
        if (recorded.count { kotlin.math.abs(it) > 0.99f } > recorded.size / 1000) {
            throw TooLoudException()
        }
    }

    @SuppressLint("MissingPermission") // every caller requires it
    private fun recordSamples(count: Int): FloatArray {
        // UNPROCESSED skips noise suppression and AGC, which would bend the measured response.
        val source = if (audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuffer = AudioRecord.getMinBufferSize(Spectrum.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val recorder = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
            .setBufferSizeInBytes(maxOf(minBuffer, 4 * Spectrum.SAMPLE_RATE / 4))
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) throw MeasurementException("Microphone unavailable")
        val out = FloatArray(count)
        try {
            mic?.let { wanted ->
                if (externalMics().none { it.id == wanted.id }) {
                    throw MeasurementException("${wanted.productName} isn't connected any more. Plug it back in, or pick the phone's microphone.")
                }
                recorder.setPreferredDevice(wanted)
            }
            recorder.startRecording()
            // Android may still record from another microphone; mixing microphones within one
            // tuning would leave their difference in the result.
            mic?.let { wanted ->
                if (recorder.routedDevice?.id != wanted.id) throw MeasurementException("Couldn't record from ${wanted.productName}. Unplug it and plug it back in.")
            }
            var read = 0
            while (read < count) {
                val n = recorder.read(out, read, count - read, AudioRecord.READ_BLOCKING)
                if (n < 0) throw MeasurementException("Microphone error $n")
                read += n
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        return out
    }

    private fun format(channel: Int) = AudioFormat.Builder()
        .setSampleRate(Spectrum.SAMPLE_RATE)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .setChannelMask(channel)
        .build()

    private companion object {
        const val SWEEP_S = 3.0
        /** Output buffer + Bluetooth delay (typically 0.2-0.4 s) + the room's decay, with margin. */
        const val TAIL_S = 1.8
        const val WAKE_MS = 1_500L
        const val CHUNK = 1024
        /** The direct sound has to arrive within this; later peaks would be reflections. */
        const val MAX_DELAY = (1.2 * Spectrum.SAMPLE_RATE).toInt()
        const val TYPICAL_DELAY = (0.3 * Spectrum.SAMPLE_RATE).toInt()
        val EXTERNAL_MIC_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )
        val MEDIA: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }
}
