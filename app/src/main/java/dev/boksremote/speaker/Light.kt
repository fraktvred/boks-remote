package dev.boksremote.speaker

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow

/**
 * What Cycle, Sleep fade and scenes need of a connected Lightboks: [SpeakerController] in the app, a
 * fake in tests. Writes are fire-and-forget and land in the order they are made; [state] follows them.
 */
interface Light {
    val state: StateFlow<SpeakerState>
    /** True while the Drop pad holds or plays, when brightness and effect are only borrowed. */
    val dropping: Boolean
    fun setPalette(palette: LightPalette)
    /** The light's own default colours. */
    fun resetPalette()
    suspend fun fadePalette(from: LightPalette, to: LightPalette, durationMs: Long)
    fun setBrightness(value: Int)
    fun setEnergyLevel(level: EnergyLevel)
    fun powerOff()
    /** Writes that finish once the light has them, for whoever paces their own: the DMX bridge. */
    val output: LightOutput
}

/** [palette], or the light's default colours for null. */
fun Light.show(palette: LightPalette?) = if (palette != null) setPalette(palette) else resetPalette()

/** The palette on the light, or null while it runs its default colours. */
val SpeakerState.ownPalette: LightPalette? get() = palette?.takeUnless { paletteIsDefault }

/** Time as [SystemClock.elapsedRealtime] counts it, which keeps going while the phone sleeps. Virtual in tests. */
fun interface Clock {
    fun now(): Long

    companion object {
        val ELAPSED = Clock { SystemClock.elapsedRealtime() }
    }
}

/** Keeps the CPU awake for a timer minutes long, which a dozing phone would otherwise let slip. */
interface KeepAwake {
    /** Times out by itself after [timeoutMs], in case it is never released. */
    fun acquire(timeoutMs: Long)
    fun release()

    companion object {
        val NONE = object : KeepAwake {
            override fun acquire(timeoutMs: Long) {}
            override fun release() {}
        }
    }
}

class WakeLockKeepAwake(context: Context, tag: String) : KeepAwake {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        .apply { setReferenceCounted(false) }

    override fun acquire(timeoutMs: Long) = lock.acquire(timeoutMs)

    override fun release() {
        if (lock.isHeld) lock.release()
    }
}
