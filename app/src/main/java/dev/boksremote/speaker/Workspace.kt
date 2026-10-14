package dev.boksremote.speaker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Scenes, palettes, EQ presets, Cycle and Sleep fade, with the scene in use kept true to [light]: the
 * user's own set, kept on the phone, or demo mode's, which starts from the defaults and goes with it.
 * [store] gives each store its place by name; [keepAwake] each timer its wake lock by tag.
 */
class Workspace(
    scope: CoroutineScope,
    store: (name: String) -> KeyValue,
    keepAwake: (tag: String) -> KeepAwake,
) {
    /** The device this set acts on; null while none of its own is open. */
    val light = MutableStateFlow<Light?>(null)
    val palettes = PaletteLibrary(store("palettes"))
    val scenes = SceneStore(store("scenes"))
    val eqPresets = EqPresetStore(store("eq_presets"))
    val cycle = PaletteCycle(scope, palettes, CycleConfigStore(store("cycle")), Clock.ELAPSED, keepAwake("BoksRemote:cycle"))
    val sleep = SleepFade(scope, Clock.ELAPSED, keepAwake("BoksRemote:sleep"))
    /** The scene in use, kept true to the light. */
    val session = SceneSession(scope, light, scenes, cycle, sleep)

    init {
        // Scenes name palettes too, so they follow a rename or delete; Cycle signs itself up.
        palettes.register(scenes)
    }
}
