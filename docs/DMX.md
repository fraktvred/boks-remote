# DMX bridge (Lightboks)

Open the Lightboks and use **DMX bridge**: the phone receives one Art-Net or sACN universe over the network and drives the light over Bluetooth.

- **Connecting a desk.** Put the phone on the lighting network, or turn on its hotspot and join the desk to it, then send to the address the app shows. Art-Net desks can also find the phone by polling: it answers ArtPoll as the node "Boks Remote" on its universe.
- **Unattended running.** The bridge keeps running with the screen off, as a foreground service. The notification shows its state and has a Stop action. If the light drops out, the bridge reconnects by itself and re-sends the current look.
- **Patching.** Changing the universe, start address or personality while the bridge runs takes effect immediately. The patch is remembered between launches.

| Ch (from start address) | Full · 15 ch | Simple · 5 ch |
|---|---|---|
| 1 | Dimmer | Dimmer |
| 2 | Mode | Mode |
| 3–5 | Main 1 R/G/B | R/G/B (all palette slots) |
| 6–14 | Main 2, Main 3, Effect R/G/B | – |
| 15 | FX | – |

- **Mode:** 0–63 Chill · 64–127 Dance · 128–191 Rave · 192–255 Uplight (static colour).
- **FX** (runs while held): 0–31 off · 32–87 Strobe · 88–143 Blackout · 144–199 Chase · 200–255 Sparkle.

Dimmer, mode and FX follow as fast as Bluetooth allows. The light stores its palette across reboots, so each colour change may be a flash write. To spare the chip, a colour is only sent once it has held for 150 ms, and at most 4 times a second: fades jump to their end colour, while snaps and chase steps still go through. When the network goes quiet the light holds its last look.
