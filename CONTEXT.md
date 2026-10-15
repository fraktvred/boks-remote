# Boks Remote

An Android remote for SOUNDBOKS speakers and Lightboks lights over Bluetooth, with a bridge that lets DMX lighting consoles drive a Lightboks.

## Language

### Devices

**Lightboks**:
A SOUNDBOKS light, controlled over Bluetooth like a speaker but showing palettes, effects and brightness instead of playing sound.

**Device list**:
The nearby devices the app keeps connected while it is on screen, together with the open device.
_Avoid_: fleet, nearby list

**Open device**:
The one device whose screen is showing; it stays connected until it is closed.

**Held device**:
A Lightboks that something drives in the background — the DMX bridge or Cycle — so it stays connected, and keeps reconnecting, after the app leaves the screen.
_Avoid_: pinned, driven

**Demo device**:
A simulated speaker or Lightboks that behaves like a real one without hardware, down to what it says over Bluetooth, kept apart from the user's own devices.
_Avoid_: dummy, fake device

### What's on the light

**Workspace**:
One set of the user's palettes, scenes, EQ presets and the Stage that uses them; the user's own, and a separate one for demo devices.
_Avoid_: set, profile

**Scene light**:
The one Lightboks a Workspace's Stage manages: the one leased to the DMX bridge, or else the open Lightboks, or else the first one listed.

**Stage**:
What the scene light shows and who drives each of its channels — palette (the user or Cycle), brightness (the user or Sleep fade) and mode (the user) — unless a Lease gives all three to the DMX bridge.
_Avoid_: director, light session

**Channel**:
One part of the scene light's look that one driver at a time controls: palette, brightness or mode.

**Scene**:
A saved look of the scene light (palette, mode, brightness, and Cycle's state), kept true to the light while it is in use.

**Palette**:
A named set of colours the Lightboks blends between.

**Cycle**:
Playing a list of palettes on the scene light one after another, changing on an interval.

**Sleep fade**:
Fading the scene light's brightness down to off over a chosen number of minutes.

**Lease**:
The scene light lent to the DMX bridge: while it lasts, the bridge drives every channel and the light stays the scene light; ending it puts the scene back.
_Avoid_: lend, borrow (as nouns)

### DMX

**DMX bridge**:
Receives Art-Net or sACN from a lighting console on the network and drives a Lightboks from it.
