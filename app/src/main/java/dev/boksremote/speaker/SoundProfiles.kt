package dev.boksremote.speaker

import dev.boksremote.protocol.ProductModel

/**
 * A built-in sound profile as the official app presents it. The speaker only knows the wire mode
 * (default/loud/quiet); what each one is called and how it sounds depends on the model. [bass],
 * [mid] and [treble] are the official app's 0..100 character bars, not real band gains.
 */
data class SoundProfile(
    val mode: EqMode,
    val title: String,
    val description: String? = null,
    val bass: Int? = null,
    val mid: Int? = null,
    val treble: Int? = null,
    val batteryHours: Int? = null,
)

object SoundProfiles {
    private const val BASS_PLUS = "Maximum bass. Minimum distortion. Better to ask for forgiveness than permission, right?"
    private const val OUTDOOR = "Best experienced outdoors. Sound sculpted for mad moments in the wild, wherever you end up."
    private const val INDOOR = "Made for indoor listening. Turn kitchens, lounges, heck–even bathrooms–into legit party spaces."
    private const val POWER = "More power for less power. Amazingly loud with ultimate battery efficiency."

    /** In the official app's tile order: loud, default, quiet. */
    fun forModel(model: ProductModel?): List<SoundProfile> = when (model) {
        ProductModel.SB_MIX -> listOf(
            SoundProfile(EqMode.LOUD, "Bass+", BASS_PLUS, 95, 80, 85, 5),
            SoundProfile(EqMode.DEFAULT, "Outdoor", OUTDOOR, 70, 55, 70, 8),
            SoundProfile(EqMode.QUIET, "Indoor", INDOOR, 65, 55, 60, 8),
        )
        ProductModel.SB_RIDER -> listOf(
            SoundProfile(EqMode.LOUD, "Bass+", BASS_PLUS, 95, 80, 85, 6),
            SoundProfile(EqMode.DEFAULT, "Outdoor", OUTDOOR, 70, 55, 70, 7),
            SoundProfile(EqMode.QUIET, "Indoor", INDOOR, 65, 55, 60, 8),
        )
        ProductModel.SB3 -> listOf(
            SoundProfile(EqMode.LOUD, "Bass+", BASS_PLUS, 100, 60, 70, 3),
            SoundProfile(EqMode.DEFAULT, "Power", POWER, 70, 50, 60, 5),
            SoundProfile(EqMode.QUIET, "Indoor", INDOOR, 50, 30, 30, 7),
        )
        ProductModel.SB_GO -> listOf(
            SoundProfile(EqMode.LOUD, "Bass+", BASS_PLUS, 100, 60, 70, 6),
            SoundProfile(EqMode.DEFAULT, "Power", POWER, 70, 50, 60, 10),
            SoundProfile(EqMode.QUIET, "Indoor", INDOOR, 50, 30, 30, 14),
        )
        ProductModel.SB4 -> listOf(
            SoundProfile(EqMode.LOUD, "Stage", "Powerful sound for live performance and vocal presence.", 100, 70, 60, 4),
            SoundProfile(EqMode.DEFAULT, "Dancefloor", "Immersive sound for parties and events.", 80, 60, 70, 6),
            SoundProfile(EqMode.QUIET, "Lounge", "Balanced sound for hangouts and solo listening.", 60, 50, 50, 8),
        )
        // The official app has no profiles for newer models; fall back to the wire names.
        else -> listOf(
            SoundProfile(EqMode.LOUD, "Loud"),
            SoundProfile(EqMode.DEFAULT, "Default"),
            SoundProfile(EqMode.QUIET, "Quiet"),
        )
    }
}

/**
 * The speaker's user-defined EQ: six bands from bass to treble, each -10..+10 dB in whole dB.
 * On the wire every band is one byte, 1..101 in 0.2 dB steps with 51 as flat, the same for the
 * Bifrost RPC and the legacy characteristic.
 */
object EqCurve {
    const val BANDS = 6
    const val MIN_DB = -10
    const val MAX_DB = 10
    val FLAT: List<Int> = List(BANDS) { 0 }

    fun toWire(bandsDb: List<Int>): ByteArray =
        ByteArray(BANDS) { i -> ((bandsDb.getOrElse(i) { 0 }.coerceIn(MIN_DB, MAX_DB) + 10) * 5 + 1).toByte() }

    /** Missing bands and 0 (never set) read as flat. */
    fun fromWire(bytes: ByteArray): List<Int> = List(BANDS) { i ->
        val idx = bytes.getOrNull(i)?.toInt()?.and(0xFF)?.takeIf { it != 0 } ?: 51
        Math.round((idx - 1) / 5f - 10).coerceIn(MIN_DB, MAX_DB)
    }
}
