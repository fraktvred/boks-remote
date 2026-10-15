package dev.boksremote.speaker

import kotlinx.coroutines.CoroutineScope

/**
 * Scenes, palettes and EQ presets, with the [Stage] that uses them: the user's own set, kept on the
 * phone, or demo mode's, which starts from the defaults and goes with it. [store] gives each store its
 * place by name; [keepAwake] each timer its wake lock by tag.
 */
class Workspace(
    scope: CoroutineScope,
    store: (name: String) -> KeyValue,
    keepAwake: (tag: String) -> KeepAwake,
) {
    val palettes = PaletteLibrary(store("palettes"))
    val scenes = SceneStore(store("scenes"))
    val eqPresets = EqPresetStore(store("eq_presets"))
    /** What the scene light shows and who drives it. */
    val stage = Stage(
        scope, palettes, scenes, CycleConfigStore(store("cycle")), Clock.ELAPSED,
        keepAwake("BoksRemote:cycle"), keepAwake("BoksRemote:sleep"),
    )

    init {
        // Scenes name palettes too, so they follow a rename or delete; Cycle signs itself up.
        palettes.register(scenes)
    }
}
