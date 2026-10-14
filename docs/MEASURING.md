# Measuring a speaker's EQ bands

Room tuning in [Boks Remote](../README.md) works best when the app knows where a model's six EQ bands sit. So far only the Mix has been measured; if you own another SOUNDBOKS, you can add yours:

1. Install a debug build (`./gradlew installDebug`) and grant it the microphone (`adb shell pm grant dev.boksremote android.permission.RECORD_AUDIO`).
2. Connect your speaker, connect the phone's audio to it over Bluetooth, and place the phone about 50 cm in front of it in a quiet room.
3. Tap **Calibrate bands (debug)** in the Sound profiles card. It plays test tones for about 4 minutes and then restores your EQ and volume.
4. Pull the result with `adb pull /sdcard/Android/data/dev.boksremote/files/bandcal.csv` and [open an issue](https://github.com/fraktvred/boks-remote/issues/new) with the file, your speaker model and its firmware version.
