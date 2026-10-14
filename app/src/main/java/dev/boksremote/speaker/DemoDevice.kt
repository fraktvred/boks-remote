package dev.boksremote.speaker

import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.ProductModel

/**
 * The dummy devices of demo mode, to see and try a device's screen without one at hand: what each
 * shows in the device list, and the state it starts in. See [SpeakerController.demo].
 */
enum class DemoDevice(val advert: Advertisement, val initial: SpeakerState) {
    LIGHTBOKS(
        Advertisement("demo:lightboks", "Demo Lightboks", rssi = 0, Family.LIGHT, ProductModel.LIGHTBOKS, "1.2.0", security = null),
        SpeakerState(
            connection = ConnectionState.CONNECTED,
            family = Family.LIGHT,
            model = ProductModel.LIGHTBOKS,
            name = "Demo Lightboks",
            firmware = "1.2.0",
            battery = 80,
            teamUp = TeamUpMode.SOLO,
            brightness = 200,
            energyLevel = EnergyLevel.MEDIUM,
            paletteIsDefault = true,
            rpcAvailable = true,
        ),
    ),
    SPEAKER(
        Advertisement("demo:speaker", "Demo Mix", rssi = 0, Family.BIFROST, ProductModel.SB_MIX, "2.4.0", security = null),
        SpeakerState(
            connection = ConnectionState.CONNECTED,
            family = Family.BIFROST,
            model = ProductModel.SB_MIX,
            name = "Demo Mix",
            firmware = "2.4.0",
            battery = 64,
            volume = 110,
            stereoRole = StereoRole.MONO,
            teamUp = TeamUpMode.SOLO,
            eq = EqMode.DEFAULT,
            eqBands = EqCurve.FLAT,
            // Nothing plays, so its auto power-off counts down.
            activeInputs = emptyList(),
            autoOffMinutes = 30,
            rpcAvailable = true,
        ),
    ),
}
