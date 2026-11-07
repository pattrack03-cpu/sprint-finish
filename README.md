# Sprint Finish (Android)

A photo-finish sprint timer for Android, made for Top Sprint Speed. Put the phone on the finish line, start the race from the phone, record the finish at 120/240 fps, and mark each athlete's time on the photo-finish image.

## Install on your phone

1. On the Android phone, open this repo's **Releases** page (signed in to GitHub) and download the newest `SprintFinish-<n>.apk`.
2. Open the file. If asked, allow installs from your browser or Files app.
3. Allow Camera, Microphone and Notifications when the app asks.

Every push to `main` builds a new APK automatically (GitHub Actions → *Build APK*). All builds use the same signing key, so a new one installs over the old one and keeps your races.

## What it does

- **Start:** three start modes:
  - Touch: press and hold, release to start, with a pistol bang.
  - Gun: the microphone detects a starting pistol. Timing uses the audio sample position, with a distance correction for sound travel.
  - Voice: self start with "On your marks… Set…" and a random hold.
- **Offsets:** a separate offset for each start mode. "Released too early? Tap at the real start" moves the start to your tap, and every time shifts with it.
- **Camera auto setup** runs every time the camera opens:
  - Picks 240 fps (or 120) high-speed mode.
  - Lets exposure settle, then locks a fast shutter with matching ISO, and locks focus.
  - Checks what the phone actually applied: frame rate, shutter and brightness.
  - If anything fails, the app shows a warning and a notification, and asks before letting a race start.
- **Finish:** drag the gold line onto the finish line.
  - *Start recording* as runners get close, *Pause recording* after they cross. The race clock keeps running, and you can record as many clips as you need.
  - *Tap split* gives quick manual splits.
- **Review:** drag the photo-finish picture under the line (pinch to zoom, step one slice at a time).
  - Times are rounded up to 0.01, the track rule.
  - Enter lane and athlete, then *Mark time*.
  - Share the image (time ruler and marks drawn in), a CSV, or the `.bvid`.
- **Results:** saved races, with untimed `.bvid` recordings listed first. You can import a `.bvid` from another phone running Sprint Finish.
- **Settings:** frame rate 60/120/240, shutter Auto–1/4000, slice width 1–4 px, runner direction, image rotation, offsets, gun trigger level/distance/shot filter, start sound, voice hold.

## Known limits (first version)

- **SprintTimer files:** Sprint Finish's `.bvid` is its own layout. SprintTimer's `.bvid` layout isn't published, so files can't be swapped with SprintTimer yet. A real SprintTimer `.bvid` is needed to work that out.
- **Phone support:** 240 fps and manual shutter depend on the phone. Auto setup reports what your phone allows.
- **Start delay:** Android adds some delay to the start sound and to touch handling. Calibrate the offsets once per phone against a known start.

## Build it yourself

Open in Android Studio, or run `gradle assembleDebug` (Gradle 8.11, JDK 17, Android SDK 35).
