#!/usr/bin/env python3
"""Retakes the README screenshots on a phone over adb.

Shoots demo mode's dummy Mix and Lightboks, so no hardware is needed, only a phone and macOS (crops
with sips). Builds and installs a release build (so no debug tools show), switches demo mode on,
steps through the app and writes docs/screenshots/<shot>.png, 540 px wide, without the status and
navigation bars.

Real devices nearby still show on the device list, by their team ID. To show stand-ins instead, set
them in local.properties:

    screenshot.names=<team ID>=Backyard Mix;<team ID>=Stage light

Usage: tools/screenshots.py [shot ...]   (default: all of them)

Custom EQ switches the dummy Mix to Custom for its shot and back to its sound profile afterwards.
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
        self.desc = html.unescape(re.search(r' content-desc="([^"]*)"', attrs).group(1))
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
    """The first view whose text, or else whose content description, is [text]."""
    def matches(s: str) -> bool:
        return s == text if exact else text in s
    return next((n for n in nodes() if matches(n.text) or matches(n.desc)), None)


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


SPEAKER = "Demo Mix"
LIGHT = "Demo Lightboks"


def open_device(name: str):
    """From the device list, opens a dummy and waits for its controls."""
    tap(name)
    # The list's quick settings have a Volume and a Brightness of their own, so first wait for the screen.
    wait_for("Back to device list")
    wait_for("Volume" if name == SPEAKER else "Brightness", timeout=40)


def take(shots: list[str], work: Path):
    adb("shell", "am", "force-stop", "dev.boksremote")
    adb("shell", "monkey", "-p", "dev.boksremote", "-c", "android.intent.category.LAUNCHER", "1")
    # A release build starts with demo mode off, and doesn't keep it across restarts.
    wait_for("More options")
    if not find(SPEAKER):
        tap("More options")
        tap("Demo mode")
    wait_for(SPEAKER)
    wait_for(LIGHT)
    if "devices" in shots:
        time.sleep(2)  # the dummies connect, then their quick settings show
        shot("devices", work)

    speaker_shots = [s for s in ("speaker", "custom-eq", "room-tuning") if s in shots]
    if speaker_shots:
        open_device(SPEAKER)
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
            tap("Close")  # Back would only step back through the wizard
        back()

    light_shots = [s for s in ("lightboks", "palettes", "palette-editor", "dmx-bridge") if s in shots]
    if light_shots:
        open_device(LIGHT)
        if "lightboks" in shots:
            shot("lightboks", work)
        if "palettes" in shots:
            # The palette's row opens them; its status line is the one fixed text in it.
            status = next(n for n in nodes() if n.text in ("Preset", "Your palette", "Not saved", "Chosen by the light"))
            x, y = status.center
            adb("shell", "input", "tap", str(x), str(y))
            wait_for("Colour palettes")
            shot("palettes", work)
            back()
        if "palette-editor" in shots:
            # The colour strip's swatches have no text; Main 1's label sits just below its swatch.
            label = wait_for("Main 1")
            x, _ = label.center
            adb("shell", "input", "tap", str(x), str(label.top - 60))
            wait_for("Live on the light")
            shot("palette-editor", work)
            back()  # discards the draft and puts the light's palette back
        if "dmx-bridge" in shots:
            # The card starts folded, near the end of the screen, too low to scroll into place until it unfolds.
            for _ in range(6):
                if find("DMX bridge"):
                    break
                adb("shell", "input", "swipe", "20", "1800", "20", "1000", "500")
                time.sleep(1)
            tap("DMX bridge")
            wait_for("Protocol")
            scroll_until("DMX bridge", 815)
            shot("dmx-bridge", work)
        back()


def main():
    shots = ["devices", "speaker", "custom-eq", "room-tuning", "lightboks", "palettes", "palette-editor", "dmx-bridge"]
    unknown = set(sys.argv[1:]) - set(shots)
    if unknown:
        sys.exit(f"unknown shots: {', '.join(sorted(unknown))}; choose from {', '.join(shots)}")
    shots = sys.argv[1:] or shots

    props = (ROOT / "local.properties").read_text() if (ROOT / "local.properties").exists() else ""
    names = re.search(r"^screenshot\.names=(.*)$", props, re.M)
    stand_ins = [f"-Pboksremote.screenshotNames={names.group(1).strip()}"] if names else []

    print("Building and installing a release build…")
    subprocess.run(["./gradlew", "-q", "installRelease", *stand_ins], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory() as work:
        take(shots, Path(work))
    print("Done. Reinstall your debug build with ./gradlew installDebug.")


if __name__ == "__main__":
    main()
