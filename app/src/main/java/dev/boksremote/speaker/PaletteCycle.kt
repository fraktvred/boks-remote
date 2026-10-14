package dev.boksremote.speaker

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Which palettes Cycle plays, by name and in the user's order, and how. */
data class CycleConfig(
    val names: List<String> = DEFAULT_NAMES,
    val intervalSeconds: Int = 60,
    val shuffle: Boolean = false,
) {
    companion object {
        /** No shorter than 30 s: each palette the light gets may be written to its flash (see DmxBridge). */
        val INTERVALS = listOf(30, 60, 120, 300, 600, 1800)
        const val MIN_PALETTES = 2
        val DEFAULT_NAMES = listOf("Synthwave", "Sunset", "Tropicana", "Aurora", "Galaxy", "Ultraviolet")

        fun intervalLabel(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"
    }
}

data class CycleStatus(
    val running: Boolean = false,
    /** Paused, by the user or by a palette picked by hand or the DMX bridge, so the screen offers Resume. */
    val paused: Boolean = false,
    val current: String? = null,
    val next: String? = null,
    /** The light shows [current]: always while running, and while paused until something else is put on it. */
    val showing: Boolean = false,
    /** [SystemClock.elapsedRealtime] of the next change. */
    val nextAt: Long = 0,
    /** While paused on [current], the time it still had; null for a whole interval. */
    val remainingMs: Long? = null,
)

/**
 * Cycle frozen at one moment, for a scene: its settings, whether it ran, the palette it showed and
 * how long that palette still had. [current] and [remainingMs] are null when unknown.
 */
data class CycleSnapshot(
    val config: CycleConfig,
    val running: Boolean,
    val current: String? = null,
    val remainingMs: Long? = null,
)

/**
 * Picks what Cycle plays next. In order it walks the list and wraps; shuffled it plays every palette
 * once, in random order, before any repeats, and never the same one twice in a row.
 */
class CycleOrder(private val random: Random = Random.Default) {
    private val played = mutableSetOf<String>()

    /** The palette to follow [current]; doesn't count as played until [played] is called. */
    fun pick(names: List<String>, shuffle: Boolean, current: String?): String? {
        if (names.isEmpty()) return null
        if (!shuffle) return names[(names.indexOf(current) + 1) % names.size]
        val others = names.filter { it != current }.ifEmpty { names }
        return others.filter { it !in played }.ifEmpty { others }.random(random)
    }

    fun played(name: String, names: List<String>) {
        played += name
        // Round over: start a new one. [pick] never repeats the current palette, so [name] can't come straight back.
        if (names.all { it in played }) played.clear()
    }
}

/**
 * Cycle: changes the Lightboks palette every [CycleConfig.intervalSeconds] through the user's chosen
 * palettes. Lives in [dev.boksremote.BoksApp] next to the DMX bridge so it keeps going with the
 * screen off, while the light-session service keeps the process in the foreground.
 */
class PaletteCycle(context: Context, private val scope: CoroutineScope, private val saved: PaletteStore) {
    private val prefs = context.getSharedPreferences("cycle", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(load())
    val config: StateFlow<CycleConfig> = _config
    private val _status = MutableStateFlow(CycleStatus())
    val status: StateFlow<CycleStatus> = _status

    private val order = CycleOrder()
    private var job: Job? = null
    private var light: SpeakerController? = null
    // The interval is minutes long; without a wake lock a dozing phone would let the timer slip.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BoksRemote:cycle")
        .apply { setReferenceCounted(false) }

    /** The cycle's palettes that still exist, in order: a deleted palette drops out. */
    fun palettes(): List<LightPalette> {
        val all = LightPalette.PRESETS + saved.palettes.value
        return _config.value.names.mapNotNull { n -> all.firstOrNull { it.name == n } }
    }

    fun update(config: CycleConfig) {
        val old = _config.value
        save(config)
        if (!_status.value.running) return
        // A new interval restarts the countdown from now; any other edit only changes what comes next.
        if (config.intervalSeconds != old.intervalSeconds) run(advance = false) else refreshNext()
    }

    /** Keeps the cycle pointing at a palette the user renamed. */
    fun rename(old: String, new: String) {
        _config.value.takeIf { old in it.names }?.let { c -> update(c.copy(names = c.names.map { if (it == old) new else it })) }
        fun renamed(name: String?) = if (name == old) new else name
        _status.update { s -> s.copy(current = renamed(s.current), next = renamed(s.next)) }
    }

    /** Drops a deleted palette, so a later one saved under its name doesn't join the cycle by accident. */
    fun remove(name: String) {
        _config.value.takeIf { name in it.names }?.let { c -> update(c.copy(names = c.names - name)) }
    }

    /** Undoes [remove]: [name] goes back to its old [index] in the cycle. */
    fun restore(name: String, index: Int) {
        val c = _config.value
        if (name !in c.names) update(c.copy(names = c.names.toMutableList().apply { add(index.coerceIn(0, size), name) }))
    }

    /** Where Cycle is now, as a scene keeps it. */
    fun snapshot(): CycleSnapshot {
        val s = _status.value
        val remaining = if (s.running) (s.nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0) else s.remainingMs
        return CycleSnapshot(_config.value, s.running, s.current, remaining)
    }

    /**
     * Puts Cycle back to [snapshot]: running, it shows the palette it showed and changes once that
     * palette's remaining time is up; otherwise it waits paused on it, and Resume carries on from there.
     * True if Cycle now runs and so shows its own palette; it can't with fewer than two palettes left.
     * [showing] says the light is left on the paused palette, so Resume plays on from it.
     */
    fun restore(light: SpeakerController, snapshot: CycleSnapshot, showing: Boolean = false): Boolean {
        halt()
        this.light = light
        save(snapshot.config)
        val names = palettes().map { it.name }
        val current = snapshot.current?.takeIf { it in names }
        val runs = snapshot.running && names.size >= CycleConfig.MIN_PALETTES
        if (runs) {
            // Nothing of the cycle that ran before carries over, not even its next pick.
            _status.value = CycleStatus(current = current)
            run(advance = current == null, resumeAt = current, firstDelayMs = snapshot.remainingMs)
        } else {
            _status.value = CycleStatus(
                paused = current != null,
                current = current,
                next = current?.let { order.pick(names, snapshot.config.shuffle, it) },
                showing = showing && current != null,
                remainingMs = snapshot.remainingMs,
            )
        }
        return runs
    }

    /** Paused on a palette still on the light, it plays on from that one with the time it had left. */
    fun start(light: SpeakerController) {
        this.light = light
        val s = _status.value
        if (!s.running && s.showing) run(advance = false, firstDelayMs = s.remainingMs) else run(advance = true)
    }

    fun skip() {
        if (_status.value.running) run(advance = true)
    }

    /**
     * Shows [name] now. Running, the cycle carries on from it with a whole interval; otherwise it waits
     * paused on it, so Resume carries on after it.
     */
    fun jumpTo(light: SpeakerController, name: String) {
        val list = palettes()
        val palette = list.firstOrNull { it.name == name } ?: return
        this.light = light
        if (_status.value.running) return run(advance = false, resumeAt = name)
        val names = list.map { it.name }
        order.played(name, names)
        light.setPalette(palette)
        _status.value = CycleStatus(paused = true, current = name, next = order.pick(names, _config.value.shuffle, name), showing = true)
    }

    /** The user's pause: the palette stays on the light and keeps its time left for Resume. */
    fun hold() {
        val s = _status.value
        if (!s.running) return
        halt()
        _status.value = s.copy(running = false, paused = true, remainingMs = (s.nextAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    /** A manual palette change wins over the cycle; it waits for Resume, which goes on to the next one. */
    fun pause() {
        // Also once paused: the palette it waits on is no longer on the light.
        if (!_status.value.let { it.running || it.paused }) return
        halt()
        _status.update { it.copy(running = false, paused = true, showing = false, remainingMs = null) }
    }

    fun stop() {
        halt()
        _status.value = CycleStatus()
    }

    private fun halt() {
        job?.cancel()
        job = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    /** [resumeAt] is shown first instead of a change, and stays for [firstDelayMs] rather than a whole interval. */
    private fun run(advance: Boolean, resumeAt: String? = null, firstDelayMs: Long? = null) {
        val light = light ?: return
        job?.cancel()
        job = scope.launch {
            var change = advance
            var resume = resumeAt
            var firstDelay = firstDelayMs
            while (true) {
                val list = palettes()
                if (list.size < CycleConfig.MIN_PALETTES) return@launch stop()
                val names = list.map { it.name }
                val cfg = _config.value
                val st = _status.value
                val now = if (resume != null && resume in names) {
                    light.state.first { it.connection == ConnectionState.CONNECTED }
                    order.played(resume, names)
                    light.setPalette(list.first { it.name == resume })
                    resume
                } else if (change || st.current !in names) {
                    val pick = st.next?.takeIf { it in names && it != st.current } ?: order.pick(names, cfg.shuffle, st.current)!!
                    // While the light is reconnecting the change waits for it rather than failing every interval.
                    light.state.first { it.connection == ConnectionState.CONNECTED }
                    order.played(pick, names)
                    light.setPalette(list.first { it.name == pick })
                    pick
                } else st.current!!
                resume = null
                val interval = firstDelay?.coerceIn(0, cfg.intervalSeconds * 1000L) ?: (cfg.intervalSeconds * 1000L)
                firstDelay = null
                // Times out by itself if this loop ever stops without releasing it.
                wakeLock.acquire(interval + WAKE_MARGIN_MS)
                _status.value = CycleStatus(
                    running = true,
                    current = now,
                    showing = true,
                    next = order.pick(names, cfg.shuffle, now),
                    nextAt = SystemClock.elapsedRealtime() + interval,
                )
                delay(interval)
                change = true
            }
        }
    }

    private fun save(config: CycleConfig) {
        _config.value = config
        prefs.edit()
            .putString(KEY_NAMES, config.names.joinToString("\n"))
            .putInt(KEY_INTERVAL, config.intervalSeconds)
            .putBoolean(KEY_SHUFFLE, config.shuffle)
            .apply()
    }

    private fun refreshNext() {
        val names = palettes().map { it.name }
        _status.update { it.copy(next = order.pick(names, _config.value.shuffle, it.current)) }
    }

    private fun load(): CycleConfig {
        val default = CycleConfig()
        val names = prefs.getString(KEY_NAMES, null)?.split('\n')?.filter { it.isNotBlank() } ?: default.names
        val interval = prefs.getInt(KEY_INTERVAL, default.intervalSeconds).takeIf { it in CycleConfig.INTERVALS } ?: default.intervalSeconds
        return CycleConfig(names, interval, prefs.getBoolean(KEY_SHUFFLE, default.shuffle))
    }

    private companion object {
        const val KEY_NAMES = "names"
        const val KEY_INTERVAL = "interval"
        const val KEY_SHUFFLE = "shuffle"
        const val WAKE_MARGIN_MS = 30_000L
    }
}
