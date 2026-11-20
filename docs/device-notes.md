# Device notes — Ace (Chinese ROM) / Shizuku

Field notes from wiring the blocker to a OnePlus Ace running ColorOS/Oplus software. Everything here
was observed on the device with `adb` (serial `100.127.53.38:5555`, model `PLR110`, Android 14). Keep it
in sync when the phone or Shizuku changes.

## Device owner is not available on this phone

`dpm set-device-owner` fails, and it is not a bug in Limited:

```
IllegalStateException: Not allowed to set the device owner because there are
already several users on the device.
    at DevicePolicyManagerService.enforceCanSetDeviceOwnerLocked(...)
```

`pm list users` shows user 0 (Owner) + **user 10 (Island)**. `enforceCanSetDeviceOwnerLocked` requires a
single user, so any managed profile — Island, Shelter, Island-style cloning, or a real work profile —
blocks device-owner provisioning outright. There is no flag or override.

Options, decided 20/11/2026: **keep Island, skip device owner, enforce through Shizuku.**
Consequences to live with:

- No `DevicePolicyManager` blocking APIs. Enforcement is shell-level (`pm disable-user` via Shizuku),
  which is weaker than DO's `setPackagesSuspended`.
- Device-owner-only features stay off: uninstall protection, device-wide policy, factory-reset guard.
- If the answer ever changes: destroy the work profile (that wipes it), reboot, then
  `adb shell dpm set-device-owner t1191.limited.com/io.timelimit.android.integration.platform.android.AdminReceiver`

Do note that the app **is** a registered device admin for user 0 (`dumpsys device_policy` lists the
`AdminReceiver`), which is what the "simple device admin" protection level uses — that half works.

## Shizuku lifecycle

- Runs as uid `shell` (`shizuku_server`, started over adb/wireless debugging). That is enough to
  `pm disable-user` / `pm enable` other apps.
- **Stock Shizuku dies on reboot** — it has to be re-started (wireless debugging re-pair, or root), and
  a blocker that silently loses its shell is a blocker that stops blocking. This device does not run
  stock Shizuku: it runs a forked build that re-arms itself after boot, so no manual step is needed
  here. Do not assume that for other devices.
- The app attaches cleanly. Verified from logcat after a force-stop/restart:
  `ShizukuServerLog: attachApplication from t1191.limited.com` and
  `ShizukuIntegration: Shizuku binder received`.
- Commands now run through a **Shizuku UserService** (`ShellUserService`, AIDL `IUserService`), which
  Shizuku starts in its own process as uid shell. That is the documented replacement for the private
  `newProcess` call the fork used to reach by reflection; Shizuku has announced `newProcess` will be
  removed. The reflection path survives only as a fallback for the window before the service connects.

  Verified on the Ace after install:

  ```
  I ShizukuServerLog: attachApplication from t1191.limited.com
  D ShizukuIntegration: Shizuku binder received
  D ShizukuIntegration: user service connected: uid=2000
  ```

  `uid=2000` is `id -u` executed *inside* the service, i.e. a real shell identity - so `pm disable-user`
  and `settings put global` have the privileges they need.

- **Nothing used to request Shizuku permission.** `requestPermission()` existed and was never called, so
  `isShizukuAvailable()` was permanently false and every command silently returned null. Now requested
  once per process when the binder arrives, and the granted callback re-binds the service.

## The `getTopApplicationInfo` denials are Shizuku's own, not Limited's

logcat fills up with this roughly once a second:

```
Permission Denial: getTopApplicationInfo from pid=26828, uid=10492
  requires oplus.permission.OPLUS_COMPONENT_SAFE
```

`10492` / pid 26828 is `moe.shizuku.privileged.api` — **the Shizuku manager app itself**, polling the
top app for its own UI. ColorOS gates that call behind an OPPO-only permission, so Shizuku's own
foreground display is refused. It does not mean our blocking is failing.

**Correction worth keeping:** this was first read as Limited's foreground detection being refused.
It is not. `CompatForegroundAppHelper` (which *does* call `ActivityManager.getRunningTasks`) is only
instantiated below API 21; on this device the chain is
`QForegroundAppHelper → {LollipopForegroundAppHelper | InstanceIdForegroundAppHelper}`, both built on
`UsageStatsManager` events, which only need the usage-stats permission the app already holds
(`current_usage_stats_permission: granted`). Foreground detection is **not** blocked by ColorOS.

## Foreground detection: a debugging hack shipped in the fork

`QForegroundAppHelper` was upstream:

```kotlin
val canUseModern = DirectUsageStatsReader.instanceIdSupported
```

and the fork (`d0d80b5` "VERY BIG CHANGE", thang1191) replaced it with:

```kotlin
val canUseModern = false // FORCE LEGACY HELPER FOR DEBUGGING
```

So every API 29+ device — including this one, which does expose instance ids — was pinned to the legacy
usage-events path. Restored on 20/11/2026 as "instance-id helper when supported, legacy on any failure",
which is upstream behaviour plus a safety net rather than a blank fallback.

Proven on the Ace after install (`logcat -s QForegroundApp`, debug build):

```
D QForegroundApp: instance-id helper reported 2 app(s)
D QForegroundApp: instance-id helper reported 1 app(s)
```

So instance ids *are* supported on this ROM — the hack was hiding a working path, and the helper was
answering from the legacy path this whole time. Zero crashes after the switch.

## LifeUp countdown bridge: implicit broadcasts get dropped

LifeUp sends its countdown events from `datasource/repository/impl/b` (`sendBoardCastEvent`) with a
plain `Context.sendBroadcast(intent)` - **no package, no component**. Since API 26 an implicit broadcast
does not reach a *manifest*-registered receiver, so `LifeUpBroadcastReceiver`'s manifest entry only
worked sometimes (delivery depends on the app's temporary-allowlist state), which looked like "the
first countdown works and later ones do not".

The fix is a runtime registration in `AppLogic.registerLifeUpReceiver()` (plus keeping the manifest entry
for older builds). Both paths now fire - visible in logcat as two handling lines per broadcast, the
second reporting `0 rule(s) updated` because the state is already correct.

Verified with a probe rule (`lifeup_shop_item_name='VexProbe'`, huge `max_time` so it can never block):

```
D LifeUpIntegration: broadcast action=app.lifeup.item.countdown.start item=VexProbe extras=Bundle[{name=VexProbe}]
D LifeUpIntegration: app.lifeup.item.countdown.start 'VexProbe' -> overrideActive=true, 1 rule(s) updated
D LifeUpIntegration: broadcast action=app.lifeup.item.countdown.stop item=VexProbe extras=Bundle[{name=VexProbe}]
D LifeUpIntegration: app.lifeup.item.countdown.stop 'VexProbe' -> overrideActive=false, 1 rule(s) updated
```

Extras the item name can arrive in: `name` on current LifeUp (plus `item`/`title` in other versions),
and the event also carries `shop_item_id`-style numeric extras, `service_type`, `duration`, `remaining`.

## Blocking ColourOS floating windows without accessibility

`showAppLockScreen()` opens a plain full-screen activity and `OverlayUtil` adds a
`TYPE_APPLICATION_OVERLAY` view. A ColorOS floating window (小窗) lives in a layer above both, so on a
floated app the lock screen is drawn *behind* the float and the app stays usable. Accessibility is not
available on this device (banking apps refuse to run with it), and there is no window type a normal app
can request above the vendor float layer.

So the enforcement path escalates: `openLockscreen()` also calls `escalateToDeviceLock()`, which watches
whether the *same* app is still reported blocked. That check is self-verifying - a lock screen that
worked brings our own activity to the front, the blocked app stops being reported, and nothing happens.
If the app is still reported blocked 5s later the lock screen demonstrably lost, and the device is
locked with the device-admin `force-lock` policy (`<force-lock/>` was already declared in
`res/xml/admin.xml`, so no re-activation of the admin was needed). Nothing floats above a keyguard.

- `DEVICE_LOCK_GRACE` (1.5s): fast, because the block is what traps the user; the false-lock risk is
  handled by the focus probe below instead of by waiting.
- `DEVICE_LOCK_COOLDOWN` (10s): a float that survives the unlock must not become a lock loop.
- Skipped for soft blocking (experimental flag), where apps are deliberately not blocked.

### The soft lock, and why the first version caused it

First attempt waited 5s before locking. The user floated a blocked app: the overlay *did* cover the
float, but `openLockscreen()` runs every tick, so `LockActivity` was re-launched once per second, each
launch stealing focus back from the home gesture - and a full screen `TYPE_APPLICATION_OVERLAY` swallows
the edge-swipe itself. Nothing could be dismissed; the phone had to be restarted. Three fixes:

1. **One launch, not one per tick** - `LOCK_SCREEN_RELAUNCH_INTERVAL` (4s) guards `showAppLockScreen`.
2. **Escape hatch** - if the same app stays blocked for `BLOCK_RELEASE_TIMEOUT` (30s), blocking releases
   itself: overlay hidden, `LockActivity.currentInstances` finished, and nothing re-blocks for
   `BLOCK_RELEASE_COOLDOWN` (120s). An imperfect block always beats a device the user cannot use.
3. **Focus probe instead of guessing** - `PlatformIntegration.isOwnLockScreenInFocus()` runs
   `dumpsys window displays | grep -m1 -E 'mCurrentFocus|mFocusedApp'` through the Shizuku shell (~30ms;
   usage stats lag seconds and cannot answer this). If our own `LockActivity` holds focus the report is
   stale and no lock happens; `null` (cannot tell) is never read as "no".

Log lines: `escalateToDeviceLock: '<pkg>' still blocked after <n>ms` on a lock,
`releaseBlocking: '<pkg>' blocked for <n>ms without resolving` when the escape hatch fires.

### What LifeUp actually broadcasts (measured, 20/11/2026)

For one item, with developer-mode broadcasts enabled, the complete event stream was:

- start / resume / **add time** -> `app.lifeup.item.countdown.start`, extras `{name, time_left, item_id}`
- pause -> `app.lifeup.item.countdown.stop`, extras carry the leftover `time_left`
- **end / abandon -> nothing at all.** `app.lifeup.item.countdown.complete` never fired once.

That is why "pause" restored blocking but "end" and "add time" left the rule suspended: there was no
event to react to. Events for *other* items also arrive (e.g. `Sample: Watch a movie` with
`time_left=0`), so matching by name is required - dropping the name filter would suspend the wrong rule.

The fix is [LifeUpOverrideGuard]: every event carries `time_left`, so a suspension stores
`now + time_left` as a deadline and the background loop restores the rule once it passes. Verified:

```
start time_left=15000, no further broadcast
t+4s  override flag = 1, profile unlocked
t+26s override flag = 0, profile locked
      D BackgroundTaskLogic: restored 1 expired LifeUp override(s)
      D WorkProfileBlockingLogic: Setting work profile quiet mode to true
```

Deadlines alone were not good enough: `time_left` is the *remaining* time, so a countdown extended by
"add time" (observed 47601 -> 117744) pushed the deadline minutes or hours out, and ending it early
still left the profile unlocked until then.

Ending a countdown does tear down LifeUp's countdown service (`CommonCountDownTimerService` /
`CountDownTimerService`), so the guard now asks the device instead of guessing:

    dumpsys activity services net.sarasarasa.lifeup | grep -c CountDownTimerService || true

run through the Shizuku shell UserService, throttled to one call per 10s and skipped for the first 15s
after a start (the service may still be coming up). Service gone + rule still suspended -> restore.

Order of trust:
1. `stop` event -> instant (pause)
2. countdown service gone -> within ~10s (end / abandon / task kill)
3. deadline from `time_left` -> exact for a countdown that just runs out, and the safety net when
   Shizuku is unavailable (the probe is optimistic: no answer means "assume still running").

Verified: a rule suspended with a deadline 20 minutes out was restored as soon as the probe ran, and
the profile returned to QUIET_MODE.

Gotcha for the UI: the rule dialog writes the literal placeholder `New Item` when the integration toggle
is switched on. A rule whose name stays `New Item` never matches a real LifeUp item - the item name has
to be typed exactly as LifeUp shows it.

## What works from `adb shell` on this ROM

Verified working (used for checks above):

- `dumpsys activity activities` → `topResumedActivity` (an alternative foreground source if usage stats
  ever break)
- `dumpsys device_policy`, `dpm list-owners`, `pm list users`, `pm list packages --user N`
- `run-as t1191.limited.com` — the DB is at `databases/db` (SQLite, no extension) and the app list cache
  at `databases/config.json`
- `run-as ... cat` for pulling the DB; the app runs as a notification listener (`NotificationListenerLog`)

## Good to know

- The DB records `enable_activity_level_blocking: 0` and `current_protection_level: simple device admin`
  — activity-level blocking is off, which is the cheap safe default on a ROM this aggressive.
- `AndroidManifest` declares `AdminReceiver` with `BIND_DEVICE_ADMIN` + `@xml/admin`, and the debug APK
  carries no `testOnly` flag (`flags=[DEBUGGABLE HAS_CODE ALLOW_CLEAR_USER_DATA]`), so nothing in the
  build blocks device-owner provisioning — only Island does.
