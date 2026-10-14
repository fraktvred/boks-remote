#!/usr/bin/env python3
"""Retakes the README screenshots on a phone over adb.

Needs a SOUNDBOKS speaker and a Lightboks switched on near the phone, and macOS (crops with sips).
Builds and installs a release build (so no debug tools show), steps through the app and writes
docs/screenshots/<shot>.png, 540 px wide, without the status and navigation bars.

Devices advertise their team ID as their name. The shots show stand-ins from local.properties:

    screenshot.names=<team ID>=Backyard Mix;<team ID>=Stage light

Usage: tools/screenshots.py [shot ...]   (default: all of them)

Custom EQ switches the speaker to Custom for its shot and back to its sound profile afterwards.
Reinstall your debug build when done (./gradlew installDebug).
"""
import html
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "screenshots"
WIDTH = 540


def adb(*args: str, binary: bool = False):
    out = subprocess.run(["adb", *args], capture_output=True, check=True).stdout
    return out if binary else out.decode()


class Node:
    def __init__(self, attrs: str):
        self.text = html.unescape(re.search(r' text="([^"]*)"', attrs).group(1))
        self.checked = ' checked="true"' in attrs
        self.left, self.top, self.right, self.bottom = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', attrs).groups())
        self.center = ((self.left + self.right) // 2, (self.top + self.bottom) // 2)

    def contains(self, other: "Node") -> bool:
        x, y = other.center
        return self.left <= x <= self.right and self.top <= y <= self.bottom


def nodes() -> list[Node]:
    """The views on screen. uiautomator can't dump while something animates, like a scan spinner, so this retries."""
    for _ in range(40):
        if "dumped" in adb("shell", "uiautomator dump /sdcard/ui.xml 2>&1 || true"):
            xml = adb("shell", "cat", "/sdcard/ui.xml")
            return [Node(m.group(1)) for m in re.finditer(r"<node ([^>]*)>", xml)]
        time.sleep(1)
    sys.exit("the screen never settled enough to read")


def find(text: str, exact: bool = True) -> Node | None:
    return next((n for n in nodes() if (n.text == text if exact else text in n.text)), None)


def wait_for(text: str, exact: bool = True, timeout: float = 30) -> Node:
    end = time.time() + timeout
    while time.time() < end:
        if node := find(text, exact):
            return node
        time.sleep(1)
    sys.exit(f"not on screen: {text}")


def tap(text: str, exact: bool = True, timeout: float = 30):
    x, y = wait_for(text, exact, timeout).center
    adb("shell", "input", "tap", str(x), str(y))


def back():
    adb("shell", "input", "keyevent", "BACK")
    time.sleep(1.5)


def scroll_until(text: str, top: int):
    """Drags the screen until [text] sits at about y = top. Slow drags, so the list barely flings."""
    seen = False
    for _ in range(12):
        node = find(text)
        if node and abs(node.top - top) < 40:
            return
        if node:
            seen = True
            dy = max(-800, min(800, node.top - top))
        else:  # still below the screen, or scrolled past it off the top
            dy = -800 if seen else 800
        # Along the left margin, so the drag can't catch a slider or an EQ point.
        adb("shell", "input", "swipe", "20", "1400", "20", str(1400 - dy), "1500")
        time.sleep(1)
    sys.exit(f"couldn't scroll {text} into place")


def bars() -> tuple[int, int]:
    """Where the status bar ends and the navigation bar begins."""
    window = adb("shell", "dumpsys", "window")
    status = re.search(r"type=statusBars frame=\[\d+,\d+\]\[\d+,(\d+)\]", window)
    nav = re.search(r"type=navigationBars frame=\[\d+,(\d+)\]", window)
    return int(status.group(1)), int(nav.group(1))


def shot(name: str, work: Path):
    time.sleep(1.5)  # let animations finish
    raw = work / f"{name}.png"
    raw.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    top, bottom = bars()
    width = int(re.search(r"(\d+)x\d+", adb("shell", "wm", "size")).group(1))
    height = bottom - top
    subprocess.run(["sips", "-c", str(height), str(width), "--cropOffset", str(top), "0", str(raw)], check=True, capture_output=True)
    subprocess.run(["sips", "-z", str(height * WIDTH // width), str(WIDTH), str(raw), "--out", str(OUT / f"{name}.png")], check=True, capture_output=True)
    print(f"  {name}.png")


def open_device(kind: str):
    """From the device list, opens the speaker ("SOUNDBOKS") or the Lightboks and waits for its controls."""
    wait_for("Scan again", timeout=40)  # tapping during a scan races the list's updates
    tap(kind, exact=False)
    wait_for("Volume" if kind == "SOUNDBOKS" else "Brightness", timeout=40)


def take(shots: list[str], work: Path):
    adb("shell", "am", "force-stop", "dev.boksremote")
    adb("shell", "monkey", "-p", "dev.boksremote", "-c", "android.intent.category.LAUNCHER", "1")
    if "devices" in shots:
        # Taken mid-scan, with the spinner going; the screen can't be read until the scan ends, so
        # the check that both devices made it into the shot comes afterwards.
        time.sleep(8)
        shot("devices", work)
    wait_for("SOUNDBOKS", exact=False)
    wait_for("Lightboks", exact=False)

    speaker_shots = [s for s in ("speaker", "custom-eq", "room-tuning") if s in shots]
    if speaker_shots:
        open_device("SOUNDBOKS")
        if "speaker" in shots:
            shot("speaker", work)
        if "custom-eq" in shots:
            # The chosen profile's button is checked; its label sits inside it.
            screen = nodes()
            labels = [n for n in screen if n.text in ("Bass+", "Outdoor", "Indoor", "Custom")]
            profile = next(l.text for b in screen if b.checked for l in labels if b.contains(l))
            tap("Custom")
            scroll_until("Sound profiles & EQ", 640)
            shot("custom-eq", work)
            tap(profile)
        if "room-tuning" in shots:
            tap("Tune to this room")
            tap("Start")
            wait_for("Where is the speaker?")
            shot("room-tuning", work)
            back()
        back()

    light_shots = [s for s in ("lightboks", "palettes", "dmx-bridge") if s in shots]
    if light_shots:
        open_device("Lightboks")
        if "lightboks" in shots:
            shot("lightboks", work)
        if "palettes" in shots:
            tap("Palettes")
            wait_for("Colour palettes")
            shot("palettes", work)
            back()
        if "dmx-bridge" in shots:
            scroll_until("DMX bridge", 815)
            shot("dmx-bridge", work)
        back()


def main():
    shots = ["devices", "speaker", "custom-eq", "room-tuning", "lightboks", "palettes", "dmx-bridge"]
    unknown = set(sys.argv[1:]) - set(shots)
    if unknown:
        sys.exit(f"unknown shots: {', '.join(sorted(unknown))}; choose from {', '.join(shots)}")
    shots = sys.argv[1:] or shots

    props = (ROOT / "local.properties").read_text() if (ROOT / "local.properties").exists() else ""
    names = re.search(r"^screenshot\.names=(.*)$", props, re.M)
    if not names:
        sys.exit("add screenshot.names=<team ID>=<name shown>;… to local.properties, so no team IDs show")

    print("Building and installing a release build…")
    subprocess.run(["./gradlew", "-q", "installRelease", f"-Pboksremote.screenshotNames={names.group(1).strip()}"], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory() as work:
        take(shots, Path(work))
    print("Done. Reinstall your debug build with ./gradlew installDebug.")


if __name__ == "__main__":
    main()
