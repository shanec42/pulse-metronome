# Pulse Metronome Test Builds — Privacy Policy

**Effective date:** 2026-11-06

This policy covers the **test builds** of Pulse Metronome, which are given only to invited testers: "Pulse Metronome (watch test)" (`com.austinlinux.pulsemetronome.watchtest`), including its Wear OS watch app, and "Pulse Metronome (dev)". The public app has its own policy: [Pulse Metronome privacy policy](../privacy.md).

Test builds work like the public app, with two differences: they can use a **Wear OS watch** as the pulse source, and they keep a **debug capture** that you can choose to **upload** to the developer. In short: **nothing leaves your phone unless you tap Upload.**

## Information the app handles

- **Pulse readings**, from your pulse oximeter over Bluetooth or from your watch's heart-rate sensor, while a session runs.
- **Session history:** dates, durations, target and metronome BPM, and pulse readings sampled during each session.
- **Settings:** your Setup and Settings choices, the address of the oximeter you last connected to, and whether you use an oximeter or a watch.
- **Debug capture:** a diagnostic log of what the app does, kept on your phone for 7 days. It records app events, pulse readings, connection and session status, the phone's battery and temperature state, the app's own log messages, and the watch's log for each session. If you have given the app permission to read system logs (this needs a computer and adb), it also records related lines from the phone's system log, such as Bluetooth messages.

All of this is stored **only on your phone**, in the app's private storage, until you choose to share or upload the debug capture.

## The watch app

- The watch app reads your heart rate from the watch's sensor only while a session you started on the phone is running. It uses the watch's Health Services, which may count the session as a workout.
- Heart-rate readings and session status go from the watch to your phone over the watch's existing connection to the phone (Google Play services on both devices). They are not sent anywhere else.
- The watch keeps a short log of each session and sends it to your phone at the end of the session, where it becomes part of the debug capture.

## Debug capture uploads

- In Settings, **Upload** sends the current debug capture as a single file to the developer's Google Firebase Storage. It happens only when you tap Upload; nothing is uploaded automatically.
- The upload uses an anonymous Firebase sign-in and a random install ID created by the app. No name, email or account is attached. The file is sent encrypted (HTTPS).
- Uploaded files can be created by the app but not read back by it or by anyone else; only the developer can read them, and only to find and fix problems in the test builds.
- **Share** in Settings lets you send the capture yourself, with any app you choose.

## Information the app does not collect or share

- The app has **no account**, **no ads**, **no analytics** and **no tracking**.
- Apart from the uploads you start, the app does not send any information to the developer or to anyone else.
- The app does not sell, rent or share information with third parties. Uploaded captures are stored by Google Firebase on the developer's behalf.

## Device backups

Android's own device backup may include the app's stored data in your Google account backup. That backup is operated by Android and Google, not by this app. For details about how device backups are stored and used, check with Google, your phone's manufacturer or your carrier.

## Permissions

- **Bluetooth (nearby devices):** to find and connect to your pulse oximeter.
- **Notifications:** to show the running session and the end-of-session alarm.
- **Internet:** only to upload a debug capture when you tap Upload.
- **Heart rate / body sensors (watch):** to read your heart rate during a session.

Android requires these permissions before any app can use these features. They are not used for anything else.

## Deleting your data

Uninstalling the app from your phone and watch deletes all of its data from those devices. (Copies in a device backup are managed by Android/Google.) To have uploaded captures deleted, contact the developer (below).

## Not a medical device

Pulse Metronome is not a medical device and is not intended to diagnose, treat, prevent or monitor any medical condition. See the FAQ for more.

## Children

The app is not directed at children and does not knowingly collect information from anyone.

## Changes to this policy

If this policy changes, the updated version will be published at this address with a new effective date.

## Contact

Questions about this policy, or requests to delete uploaded captures: open an issue on GitHub: [github.com/shanec42/pulse-metronome/issues](https://github.com/shanec42/pulse-metronome/issues).
