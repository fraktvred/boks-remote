package dev.boksremote.speaker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Where Cycle is. Each state holds only what it needs, so no mix of flags can contradict another. */
sealed interface CycleStatus {
    /** The palette playing or waited on. */
    val current: String? get() = null
    /** The palette that follows [current]. */
    val next: String? get() = null
    val running: Boolean get() = this is Running
    /** Paused, so the screen offers Resume. */
    val paused: Boolean get() = this is Paused
    /** The light shows [current]: always while running, and while paused until something else is put on it. */
    val showing: Boolean get() = this is Running || this is Paused && onLight

    /** Off; the next start begins from the top. */
    data object Stopped : CycleStatus

    /**
     * Playing [current] until [nextAt] ([Clock] time), then [next]. Both [current] and [nextAt] are null
     * only while it waits for the light to take its first palette.
     */
    data class Running(override val current: String?, override val next: String?, val nextAt: Long?) : CycleStatus

    /**
     * Waiting for Resume on [current], paused by the user, by a palette picked by hand or by the DMX bridge.
     * [onLight]: the light still shows [current], so Resume plays on from it with [remainingMs] (null for a
     * whole interval); otherwise something else went on the light and Resume goes on to [next].
     */
    data class Paused(override val current: String, override val next: String?, val onLight: Boolean, val remainingMs: Long?) : CycleStatus
}

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
 *
 * Cycle owns the rule that a palette picked by hand wins over it: every palette the user puts on the
 * light goes through [showByHand] or a [Preview], which pause it, so nothing has to remember to.
 */
class PaletteCycle(
    private val scope: CoroutineScope,
    private val library: PaletteLibrary,
    private val store: CycleConfigStore,
    private val clock: Clock = Clock.ELAPSED,
    private val awake: KeepAwake = KeepAwake.NONE,
    random: Random = Random.Default,
) : PaletteLibrary.User {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<CycleConfig> = _config
    private val _status = MutableStateFlow<CycleStatus>(CycleStatus.Stopped)
    val status: StateFlow<CycleStatus> = _status
    private val _lent = MutableStateFlow(false)
    /** True while the light is lent to the DMX bridge: Cycle waits paused and won't start. */
    val lent: StateFlow<Boolean> = _lent

    private val order = CycleOrder(random)
    private var job: Job? = null
    private var light: Light? = null
    /** The palette a fade is on its way to, while it is; stopping Cycle mid-fade lands on it rather than in between. */
    private var fadingTo: LightPalette? = null

    init {
        library.register(this)
    }

    /** The cycle's palettes that still exist, in order: a deleted palette drops out. */
    fun palettes(): List<LightPalette> = _config.value.names.mapNotNull(library::resolve)

    /** Whether Start (or Resume) would play: enough palettes, and the light isn't lent out. */
    fun canStart(): Boolean = !_lent.value && palettes().size >= CycleConfig.MIN_PALETTES

    /** 1-based place of the palette playing in [palettes], or 0. */
    fun position(): Int = palettes().indexOfFirst { it.name == _status.value.current } + 1

    fun update(config: CycleConfig) {
        val old = _config.value
        save(config)
        if (!_status.value.running) return
        // A new interval restarts the countdown from now; any other edit only changes what comes next.
        if (config.intervalSeconds != old.intervalSeconds) run(advance = false) else refreshNext()
    }

    /** Keeps the cycle pointing at a palette the user renamed. */
    override fun renamed(old: String, new: String) {
        _config.value.takeIf { old in it.names }?.let { c -> update(c.copy(names = c.names.map { if (it == old) new else it })) }
        fun renamed(name: String?) = if (name == old) new else name
        _status.update { s ->
            when (s) {
                is CycleStatus.Running -> s.copy(current = renamed(s.current), next = renamed(s.next))
                is CycleStatus.Paused -> s.copy(current = renamed(s.current)!!, next = renamed(s.next))
                CycleStatus.Stopped -> s
            }
        }
    }

    /** Drops a deleted palette; the undo puts it back at its old place in the cycle. */
    override fun removed(name: String): (String) -> Unit {
        val index = _config.value.names.indexOf(name)
        if (index < 0) return {}
        update(_config.value.let { c -> c.copy(names = c.names - name) })
        return { back ->
            val c = _config.value
            if (back !in c.names) update(c.copy(names = c.names.toMutableList().apply { add(index.coerceIn(0, size), back) }))
        }
    }

    /** Where Cycle is now, as a scene keeps it. */
    fun snapshot(): CycleSnapshot = when (val s = _status.value) {
        is CycleStatus.Running -> CycleSnapshot(_config.value, true, s.current, s.nextAt?.let { (it - clock.now()).coerceAtLeast(0) })
        is CycleStatus.Paused -> CycleSnapshot(_config.value, false, s.current, s.remainingMs)
        CycleStatus.Stopped -> CycleSnapshot(_config.value, false)
    }

    /**
     * Puts Cycle back to [snapshot]: running, it shows the palette it showed and changes once that
     * palette's remaining time is up; otherwise it waits paused on it, and Resume carries on from there.
     * True if Cycle now runs and so shows its own palette; it can't with fewer than two palettes left,
     * nor while the light is lent out. [showing] says the light is left on the paused palette, so
     * Resume plays on from it.
     */
    fun restore(light: Light, snapshot: CycleSnapshot, showing: Boolean = false): Boolean {
        halt()
        this.light = light
        save(snapshot.config)
        val names = palettes().map { it.name }
        val current = snapshot.current?.takeIf { it in names }
        val runs = snapshot.running && canStart()
        if (runs) {
            // Nothing of the cycle that ran before carries over, not even its next pick.
            _status.value = CycleStatus.Running(current, next = null, nextAt = null)
            run(advance = current == null, resumeAt = current, firstDelayMs = snapshot.remainingMs)
        } else {
            _status.value = current?.let {
                paused(it, next = order.pick(names, snapshot.config.shuffle, it), onLight = showing, remainingMs = snapshot.remainingMs)
            } ?: CycleStatus.Stopped
        }
        return runs
    }

    /** Paused on a palette still on the light, it plays on from that one with the time it had left. */
    fun start(light: Light) {
        if (!canStart()) return
        this.light = light
        val s = _status.value
        if (s is CycleStatus.Paused && s.onLight) run(advance = false, firstDelayMs = s.remainingMs) else run(advance = true)
    }

    fun skip() {
        if (_status.value.running) run(advance = true)
    }

    /**
     * Shows [name] now. Running, the cycle carries on from it with a whole interval; otherwise it waits
     * paused on it, so Resume carries on after it.
     */
    fun jumpTo(light: Light, name: String) {
        if (_lent.value) return
        val list = palettes()
        val palette = list.firstOrNull { it.name == name } ?: return
        this.light = light
        if (_status.value.running) return run(advance = false, resumeAt = name)
        val names = list.map { it.name }
        order.played(name, names)
        light.setPalette(palette)
        _status.value = paused(name, next = order.pick(names, _config.value.shuffle, name), onLight = true, remainingMs = null)
    }

    /** The user's pause: the palette stays on the light and keeps its time left for Resume. */
    fun hold() {
        val s = _status.value as? CycleStatus.Running ?: return
        halt(finishFade = true)
        _status.value = s.current?.let { freeze(s, it) } ?: CycleStatus.Stopped
    }

    fun stop() {
        halt(finishFade = true)
        _status.value = CycleStatus.Stopped
    }

    /**
     * Puts [palette] on the light as the user's own pick, or the light's default colours for null. It
     * wins over Cycle, which waits for Resume and then goes on to the next palette.
     */
    fun showByHand(light: Light, palette: LightPalette?) {
        pause()
        light.show(palette)
    }

    /** New colours for the palette the light already shows, say after an edit; Cycle, if running, isn't interrupted. */
    fun updateShown(light: Light, palette: LightPalette) = light.setPalette(palette)

    /** Lends the light to the DMX bridge: Cycle pauses and won't start until [giveBack]. */
    fun lend() {
        pause()
        _lent.value = true
    }

    fun giveBack() {
        _lent.value = false
    }

    /** A palette editor's session on [light]: previews pause Cycle, and cancelling puts back what was there. */
    fun preview(light: Light) = Preview(light)

    inner class Preview internal constructor(private val light: Light) {
        /** Null when the light ran its default colours. */
        private val before = light.state.value.ownPalette
        private var previewing = false
        /** Cycle as the first preview found it. */
        private var found: CycleStatus = CycleStatus.Stopped
        /** When it was running: the palette it was on, with the time that palette had left. */
        private var frozen: CycleStatus.Paused? = null

        fun show(colors: List<HsvColor>) {
            if (!previewing) {
                previewing = true
                found = _status.value
                frozen = (found as? CycleStatus.Running)?.let { r -> r.current?.let { freeze(r, it) } }
                pause()
            }
            light.setPalette(LightPalette("Custom", colors))
        }

        /** Undoes the previews: a running Cycle plays on from the very palette it was on, with the time it had left. */
        fun cancel() {
            if (!previewing) return
            previewing = false
            this@PaletteCycle.light = light
            val frozen = frozen
            when {
                found.running && canStart() && frozen != null -> {
                    _status.value = frozen
                    run(advance = false, resumeAt = frozen.current, firstDelayMs = frozen.remainingMs)
                }
                found.running && canStart() -> run(advance = true)
                else -> {
                    _status.value = frozen ?: found.takeUnless { it.running } ?: CycleStatus.Stopped
                    light.show(before)
                }
            }
        }

        /** Leaves [palette] on the light: a palette picked by hand, so Cycle stays paused. */
        fun keep(palette: LightPalette) {
            previewing = false
            showByHand(light, palette)
        }

        /** New colours for the palette the light already shows; Cycle, if running, isn't interrupted. */
        fun refresh(palette: LightPalette) {
            previewing = false
            updateShown(light, palette)
        }
    }

    /** [s] paused where it is, its palette left on the light with the time it had. */
    private fun freeze(s: CycleStatus.Running, current: String) =
        paused(current, s.next, onLight = true, remainingMs = s.nextAt?.let { (it - clock.now()).coerceAtLeast(0) })

    private fun paused(current: String, next: String?, onLight: Boolean, remainingMs: Long?) =
        CycleStatus.Paused(current, next, onLight, remainingMs.takeIf { onLight })

    /** A palette went on the light by hand; Cycle waits for Resume, which goes on to the next one. */
    private fun pause() {
        // Also once paused: the palette it waits on is no longer on the light.
        when (val s = _status.value) {
            CycleStatus.Stopped -> return
            is CycleStatus.Running -> {
                halt()
                _status.value = s.current?.let { paused(it, s.next, onLight = false, remainingMs = null) } ?: CycleStatus.Stopped
            }
            is CycleStatus.Paused -> {
                halt()
                _status.value = paused(s.current, s.next, onLight = false, remainingMs = null)
            }
        }
    }

    /** [finishFade]: a fade cut short puts its end palette on the light; otherwise something else is about to. */
    private fun halt(finishFade: Boolean = false) {
        job?.cancel()
        job = null
        fadingTo?.let { if (finishFade) light?.setPalette(it) }
        fadingTo = null
        awake.release()
    }

    /** [resumeAt] is shown first instead of a change, and stays for [firstDelayMs] rather than a whole interval. */
    private fun run(advance: Boolean, resumeAt: String? = null, firstDelayMs: Long? = null) {
        val light = light ?: return
        job?.cancel()
        // A Skip mid-fade fades on from wherever the light got to.
        fadingTo = null
        _status.update { CycleStatus.Running(it.current, it.next, nextAt = null) }
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
                    st.next?.takeIf { it in names && it != st.current } ?: order.pick(names, cfg.shuffle, st.current)!!
                } else st.current!!
                // The palette to put on the light when this round changes it; a resumed one is on already.
                val changeTo = if (now != resume && (change || st.current !in names)) list.first { it.name == now } else null
                resume = null
                val interval = firstDelay?.coerceIn(0, cfg.intervalSeconds * 1000L) ?: (cfg.intervalSeconds * 1000L)
                firstDelay = null
                if (changeTo != null) {
                    // While the light is reconnecting the change waits for it rather than failing every interval.
                    light.state.first { it.connection == ConnectionState.CONNECTED }
                    order.played(now, names)
                }
                val nextAt = clock.now() + interval
                awake.acquire(interval + WAKE_MARGIN_MS)
                // The new palette counts from the moment its fade begins, so a fade doesn't stretch the interval.
                _status.value = CycleStatus.Running(now, order.pick(names, cfg.shuffle, now), nextAt)
                if (changeTo != null) {
                    val from = light.state.value.ownPalette
                    if (cfg.transition == CycleTransition.FADE && from != null && from.colors != changeTo.colors) {
                        fadingTo = changeTo
                        light.fadePalette(from, changeTo, FADE_MS)
                        fadingTo = null
                    } else light.setPalette(changeTo)
                }
                delay((nextAt - clock.now()).coerceAtLeast(0))
                change = true
            }
        }
    }

    private fun save(config: CycleConfig) {
        _config.value = config
        store.save(config)
    }

    private fun refreshNext() {
        val names = palettes().map { it.name }
        _status.update { s -> if (s is CycleStatus.Running) s.copy(next = order.pick(names, _config.value.shuffle, s.current)) else s }
    }

    companion object {
        /** Long enough to read as a fade, short against the shortest interval. */
        const val FADE_MS = 3_000L
        private const val WAKE_MARGIN_MS = 30_000L
    }
}
