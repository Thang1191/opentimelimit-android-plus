# Limited (t1191.limited.com)

This App allows setting time limits for the usage of Android phones/ devices.

It is a fork of the App [TimeLimit](https://timelimit.io) — via
[Open TimeLimit](https://github.com/Thang1191/opentimelimit-android-plus) — with all networking related
features removed, rebranded as **Limited** and extended so it can be driven by
[LifeUp](https://github.com/Ayagikei/LifeUp) countdown timers.

[![get it on fdroid badge](./get-it-on-fdroid-badge.png)](https://f-droid.org/app/io.timelimit.android.open)

### Building

Open it with Android Studio and press the Run button, or from a shell:

```sh
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. This fork builds with Gradle 8.7,
AGP 8.5.1 and Kotlin 1.9.22 — do not bump those in the same change as anything else.

### What differs from upstream

- **Identity**: `applicationId` and label are `t1191.limited.com` / "Limited", so it installs side by
  side with anything else built from the same tree.
- **LifeUp bridge**: LifeUp broadcasts `app.lifeup.item.countdown.start` / `.stop`; a rule whose
  `lifeup_shop_item_name` matches is suspended or restored from that. A stop waits 1.5s, because
  adding time is emitted as stop + start about a second apart and restoring on the stop alone makes
  blocking flicker. A deadline plus a countdown-service probe catch a countdown that ends without any
  broadcast.
- **Suspension instead of disabling**: the Shizuku technique runs `pm suspend` + `am force-stop`, so a
  blocked app keeps its icon (greyed out) instead of disappearing, and its process dies so a floating
  window cannot hover over the overlay.
- **Keep-alive**: a self-repeating alarm, two jobs and the service's own restart hooks bring the
  foreground service back after the ROM stops it out from under the user.
- **Blocking escalation**: device-admin `lockNow()` when an overlay loses to a vendor floating window,
  guarded by a focus probe and a 30s escape hatch so it cannot trap the user.

### Enabling the device owner permission

```adb shell dpm set-device-owner t1191.limited.com/io.timelimit.android.integration.platform.android.AdminReceiver```

Device owner is refused on a device that already has a secondary user (an Island / work profile counts),
so the Shizuku path is the supported one there.

### Documentation

Everything learned on real hardware lives in [`docs/`](./docs): `baseline.md` (the state the cleanup
started from), `codemods.md` (retired one-off rewrites), and `device-notes.md` (measured LifeUp event
stream, Shizuku/UserService notes, ColorOS floating windows, o-stop kills, suspension semantics,
responsiveness numbers).

### Screenshots

![overview screen](./app/src/main/play/en-US/listing/phoneScreenshots/1.png)

![time limit rule screen](./app/src/main/play/en-US/listing/phoneScreenshots/2.png)
