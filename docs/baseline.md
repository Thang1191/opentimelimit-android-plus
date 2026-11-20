# Baseline (phase 0)

Recorded 20/11/2026, **before** any cleanup work. Everything below is what the tree built to
*at this commit*, so every later phase can be diffed against it.

| | |
|---|---|
| Commit | `72c5773` ("Limited: own app identity, archive the planning artifacts") |
| Tag | `cleanup-baseline` |
| Branch | `master` |
| `applicationId` | `t1191.limited.com` |
| `app_name` | Limited |
| `versionCode` / `versionName` | 217 / 7.1.0 |

## Toolchain

- Gradle 8.7 (wrapper), AGP 8.5.1, Kotlin 1.9.22, compileSdk 34, minSdk 24, targetSdk 34
- JDK 17 (`/usr/lib/jvm/java-17-openjdk-amd64`)
- `ANDROID_HOME=/home/thang1191/Android/Sdk` — no `local.properties`, the env var is enough
- Shizuku `dev.rikka.shizuku:api` / `:provider` 13.1.5

## Build

```
export ANDROID_HOME=/home/thang1191/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew assembleDebug --console=plain
```

- **BUILD SUCCESSFUL in 3m**, 40 actionable tasks, no errors
- Warnings: Kotlin deprecations only (`getSerializable`, `ProgressDialog`, `setTargetFragment`,
  `FLAG_DISMISS_KEYGUARD`, `setHasOptionsMenu`, `parentKey` shadowing, two no-op casts)

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

```
size    11 MB
sha256  2d88fc580c45ef71d4b2888a61b817f6b1685aff998aa48a397704cce9979148
package name='t1191.limited.com' versionCode='217' versionName='7.1.0'
application-label:'Limited'
```

## Device state at baseline (Ace, `100.127.53.38:5555`)

- Installed **alongside** upstream `io.timelimit.android.open` — both list in `pm list packages`,
  which is the whole point of the identity change
- Launched `t1191.limited.com/io.timelimit.android.ui.MainActivity` → onboarding tutorial renders,
  title bar "Limited", **0** `FATAL EXCEPTION` in logcat
- **No device owner** (`dumpsys device_policy` → `Device Owner Type: -1`); the only admin is
  `com.oasisfeng.island.fdroid`. So every block on this device has to go through **Shizuku**, which
  is installed (`moe.shizuku.privileged.api`) — the device-owner path is not a fallback here
- LifeUp broadcast bridge: the Ace already has `enable_developer_mode = true` and
  `broadcast_event = true`, and its broadcast history shows `app.lifeup.item.countdown.start/.stop`
  firing

## Reproducing the baseline

```
git checkout cleanup-baseline
./gradlew clean assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk   # must match above
```

A mismatch here means the toolchain moved, not the source — check the Gradle/AGP/JDK versions
before blaming a cleanup commit.
