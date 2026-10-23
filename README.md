# Pulse Metronome

A free, open-source Android **pacing aid for meditation and relaxation**. It connects to a Bluetooth pulse oximeter and plays a gentle metronome that starts near your pulse and slowly leads it slower.

> **Not a medical device.** Pulse Metronome does not diagnose, treat or monitor any condition, and must not be used to make medical decisions. Readings come from your oximeter with no guarantee of accuracy. The on-screen heart trace is an illustration, not a recording of your heart rhythm.

- [Frequently asked questions](docs/legal/faq.md)
- [Privacy policy](docs/legal/privacy.md)

## What it does

- **Paced sessions:** the metronome starts near your current pulse and eases down toward a target BPM over the ramp you choose.
- **Without a pulse meter:** the metronome follows a planned descent from a starting BPM you set.
- **Monitor mode:** the metronome simply keeps pace with your pulse.
- **History:** each session is saved on the phone with a graph of pulse and metronome. History can be exported as CSV, or switched off.
- **No account, no ads, no internet:** everything stays on your phone.

## Supported pulse oximeters

The Wellue KS-60FW and oximeters using the FS20F protocol are tested. The BerryMed BM1000 series, the EMAY SleepO2, the Wellue/Viatom O2Ring and Checkme O2, and oximeters that use the standard Bluetooth Pulse Oximeter Service are supported but haven't been tested. If yours isn't listed, it may still work using one of these protocols. See the [FAQ](docs/legal/faq.md) for details.

## Getting the app

Pulse Metronome is in closed testing on Google Play. A public listing will follow.

## Building from source

The build runs in Docker, so you only need Docker and Git:

```bash
git clone https://github.com/shanec42/pulse-metronome.git
cd pulse-metronome
./dgradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./dgradlew testDebugUnitTest    # unit tests
```

`./dgradlew` runs the Gradle wrapper inside the image defined in `docker/Dockerfile` (JDK 17, Android SDK 36).

Android Studio also works: open the project with JDK 17 and Android SDK 36 installed.

## Reporting a problem

Please [open an issue](https://github.com/shanec42/pulse-metronome/issues). For an oximeter that doesn't connect, include its make and model, and the name it shows in the app's picker.

## Licence

Pulse Metronome is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE).

## Credits

The metronome click sound is from [freewavesamples.com](https://freewavesamples.com), used royalty-free.
