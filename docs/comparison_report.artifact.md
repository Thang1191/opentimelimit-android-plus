# LifeUpCatcher vs OpenTimeLimit — Detailed Comparison Report

## 1. Executive Summary

| Aspect | **LifeUpCatcher** | **OpenTimeLimit** |
|--------|------------------|-------------------|
| **Primary Purpose** | Personal focus/gamification enforcer integrated with LifeUp app | Parental control / screen time management |
| **Target User** | Self-discipline users (gamification) | Parents managing children's devices |
| **Project Age** | New (v1.0, code 1) | Mature (v7.1.0, code 217) |
| **License** | Proprietary (no visible license) | GPL v3 |
| **Code Size** | ~25 Kotlin files | ~200+ Kotlin/Java files |
| **UI Framework** | Jetpack Compose + Material 3 | XML Layouts + DataBinding + Views |
| **Theme** | Material 3 / Material You | AppCompat + Material Components + Dynamic Colors |

---

## 2. LifeUpCatcher — Deep Dive

### 2.1 What It Does
LifeUpCatcher is a **companion app** for the [LifeUp](https://play.google.com/store/apps/details?id=app.lifeup) gamification app. It receives **broadcast intents** from LifeUp (when a countdown item starts, stops, or completes) and enforces device-level blocking rules in response. It acts as a bridge between LifeUp's gamified habit/task system and Android's system-level controls.

### 2.2 Architecture

```
┌──────────────────────────────────────────────────┐
│                 UI Layer (Compose)                │
│  MainActivity → NavBar → Screens                 │
│  - ShopItemsScreen (monitored items CRUD)        │
│  - AppPicker       (app grouping)                │
│  - LauncherScreen  (custom launcher)             │
│  - DnsScreen       (DNS locking)                 │
│  - SleepScreen     (sleep config)                │
│  - LockOverlay     (challenge lock)              │
│  - DebugScreen     (debugging)                   │
├──────────────────────────────────────────────────┤
│             ViewModels (Hilt @HiltViewModel)      │
│  ShopItemsVM, AppPickerVM, DnsVM, LockVM,        │
│  LauncherVM, DebugVM                             │
├──────────────────────────────────────────────────┤
│              Data Layer                           │
│  Room DB (AppGroupEntity, MonitoredItemEntity)    │
│  DataStore Preferences (settings)                 │
│  Repositories: AppGroup, MonitoredItem,           │
│  Dns, Launcher, Settings, Shizuku                 │
├──────────────────────────────────────────────────┤
│            System Integration                     │
│  MainService (foreground), AccessibilityService,  │
│  BroadcastReceiver (LifeUp events), Shizuku       │
│  Health Connect (sleep data), WorkManager         │
└──────────────────────────────────────────────────┘
```

**Pattern**: MVVM with Repository pattern
**DI**: Hilt (`@AndroidEntryPoint`, `@HiltViewModel`, `@Inject`, `@Singleton`)
**Navigation**: Manual Compose tab-based (no Navigation Component)

### 2.3 Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin |
| Min SDK / Target SDK | 26 / 36 |
| UI | Jetpack Compose + Material 3 + Material Icons Extended |
| Database | Room (KSP) |
| Preferences | DataStore Preferences |
| DI | Hilt + Hilt Navigation Compose + Hilt WorkManager |
| System Privileges | Shizuku API (v13) |
| Health | Health Connect Client (1.1.0-alpha11) |
| Background | WorkManager (2.10.0) |
| JSON | Gson |

### 2.4 Key Components & Permissions

**Android Components** (from AndroidManifest):

| Component | Type | Purpose |
|-----------|------|---------|
| `MainActivity` | Activity | Main launcher, Compose UI host |
| `MainService` | Foreground Service | Core monitoring engine, app disabling via Shizuku, DNS enforcement, work profile toggling |
| `MyAccessibilityService` | Accessibility Service | Foreground app detection, HOME technique (forces home when blocked app opens) |
| `LifeUpBroadcastReceiver` | BroadcastReceiver | Listens for `app.lifeup.item.countdown.{start,stop,complete}` intents |
| `LauncherSwitchReceiver` | BroadcastReceiver | Switches between focus launcher and main launcher |
| `PermissionsRationaleActivity` | Activity | Health Connect permission rationale |
| `SleepCheckWorker` | WorkManager Worker | Periodic sleep check via Health Connect |

**Permissions**:
- `QUERY_ALL_PACKAGES` — enumerate installed apps
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` — keep service alive
- `POST_NOTIFICATIONS` — foreground service notification
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prevent background kill
- `MODIFY_QUIET_MODE` — toggle work profile
- `SCHEDULE_EXACT_ALARM` — sleep check scheduling
- `health.READ_SLEEP` — Health Connect sleep data

### 2.5 Blocking Techniques

LifeUpCatcher supports three blocking techniques, selectable per monitored item:

| Technique | Mechanism | Requires |
|-----------|-----------|----------|
| **HOME** | Accessibility Service detects foreground app → triggers `GLOBAL_ACTION_HOME` | Accessibility permission |
| **DISABLE** | Shizuku runs `pm disable-user --user 0 <pkg>` / `pm enable <pkg>` | Shizuku running |
| **WORK_PROFILE** | Toggles `UserManager.requestQuietModeEnabled()` for managed profile | Shizuku + MODIFY_QUIET_MODE |

Each monitored item has:
- A linked **app group** (set of package names)
- Per-**weekday** scheduling
- Custom **messages** (start, stop, force quit)

### 2.6 How It Works — Operational Flow

```
1. User creates App Groups (e.g., "Social Media" → [com.instagram, com.twitter, ...])
2. User creates Monitored Items (e.g., "Focus Mode" linked to "Social Media" group, DISABLE technique, Mon-Fri)
3. LifeUp countdown starts → broadcasts "app.lifeup.item.countdown.start"
4. LifeUpBroadcastReceiver receives it → relates to a MonitoredItem → sets item.isActive = true
5. MainService detects change via Room Flow → enforces blocking:
   - DISABLE: runs pm disable-user via Shizuku
   - HOME: AccessibilityService starts watching foreground packages
   - WORK_PROFILE: toggles quiet mode
6. When LifeUp countdown stops/completes → item.isActive = false → MainService reverses
```

### 2.7 Additional Features

**DNS Locking**: Forces the system-wide Private DNS setting to a user-specified hostname (e.g., `dns.google`). Uses Shizuku to run `settings put global private_dns_mode hostname` and `settings put global private_dns_specifier <hostname>`, then watches for changes via `ContentObserver`.

**Sleep Tracking**: Uses Health Connect to read sleep session data. A nightly WorkManager worker checks if the user slept enough. If so, sends an `app.lifeup.item.countdown.complete` broadcast (reward). If not, sends `app.lifeup.item.countdown.start` (punishment, blocking social media etc.). Configurable sleep threshold, reward/punishment amounts, and custom messages.

**Random Lock Challenge**: When enabled, locks the app with a random character string that the user must type exactly. Resets on window focus loss (prevents OCR/copy-paste bypass). Uses `FLAG_SECURE` to prevent screenshots when locked.

**Custom Launcher**: Can register as the default home launcher, switching between normal mode and a minimal focus mode.

---

## 3. OpenTimeLimit — Deep Dive

### 3.1 What It Does
OpenTimeLimit is an **open-source parental control app** that lets parents manage their children's screen time. It tracks app usage, enforces time limits per category, and supports a synced multi-device architecture where a parent device can configure rules for child devices. It is mature (version 7.1.0, since ~2019) and supports many advanced scenarios.

### 3.2 Architecture

```
┌───────────────────────────────────────────────────┐
│              UI Layer (XML + DataBinding)           │
│  MainActivity → NavHostFragment → ~30 Fragments    │
│  - Login / Setup / Overview / User Management      │
│  - Category Config / Time Limit Rules              │
│  - Child Tasks / Session Duration                  │
│  - Widget / Homescreen (custom launcher)           │
│  - Lock / Annoy / Manipulation Warnings            │
│  - Diagnose / Backdoor / Help                      │
├───────────────────────────────────────────────────┤
│          AppLogic (Central Orchestrator)            │
│  - Singleton, initialized via AndroidAppLogic      │
│  - Hosts all sub-logic modules:                    │
│    RealTimeLogic, BackgroundTaskLogic,              │
│    AppSetupLogic, DefaultUserLogic,                │
│    ManipulationLogic, SuspendAppsLogic,            │
│    AnnoyLogic, SyncInstalledAppsLogic,             │
│    WatchdogLogic, UsedTimeDeleter,                 │
│    DayChangeTracker                                │
├───────────────────────────────────────────────────┤
│              Data Layer (Room)                      │
│  15+ entities: User, Device, Category, App,        │
│  TimeLimitRule, UsedTimeItem, SessionDuration,     │
│  ChildTask, ConfigurationItem, WidgetConfig, ...   │
│  + derived data classes + migration system         │
├───────────────────────────────────────────────────┤
│         Integration Layer                           │
│  PlatformIntegration (abstract)                     │
│  ├─ AndroidIntegration (concrete)                  │
│  │  └─ BackgroundService (foreground)              │
│  │  └─ AccessibilityService                        │
│  │  └─ DeviceAdminReceiver                         │
│  │  └─ NotificationListenerService                 │
│  │  └─ UsageStatsManager                           │
│  └─ TimeApi (abstract) / RealTimeApi               │
│  Crypto: bcrypt, Curve25519                        │
│  Sync: JSON-based action/validation system         │
│  U2F: Authentication for device pairing            │
│  Barcode: QR code for device pairing (ZXing)       │
└───────────────────────────────────────────────────┘
```

**Pattern**: MVVM (LiveData-based) with centralized AppLogic singleton
**DI**: Manual (no framework — `DefaultAppLogic.with(context)` singleton pattern)
**Navigation**: Jetpack Navigation Component with Safe Args
**UI**: XML layouts + DataBinding + ViewBinding

### 3.3 Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin (with some Java interop) |
| Min SDK / Target SDK | 24 / 34 |
| UI | AppCompat, Material Components, XML DataBinding + ViewBinding |
| Navigation | Navigation Component (v2.7.7) + Safe Args |
| Database | Room (v2.6.1) — extensive schema with 15+ entities, custom migrations |
| Background | Foreground Service, custom coroutine-based loops |
| Crypto | jBCrypt (passwords), Curve25519 (sync auth) |
| Date/Time | ThreeTenABP (JSR-310 backport) |
| Barcode | ZXing (QR code generation) |
| Biometrics | AndroidX Biometric |
| Shizuku | v13 (privileged operations) |
| Paging | Paging 3 (lists) |
| UI Helpers | Flexbox Layout, TapTargetView (onboarding) |
| I/O | Okio |

### 3.4 Key Components & Permissions

**Android Components** (from AndroidManifest):

| Component | Type | Purpose |
|-----------|------|---------|
| `MainActivity` | Activity | Main launcher, NavHost |
| `LockActivity` | Activity | Full-screen lock overlay (separate task) |
| `HomescreenActivity` | Activity | Custom home screen launcher for child mode |
| `AnnoyActivity` | Activity | Annoying overlay when limits are exceeded |
| `UnlockAfterManipulationActivity` | Activity | Post-manipulation unlock screen |
| `WidgetConfigActivity` | Activity | Widget configuration |
| `DiagnoseExceptionActivity` | Activity | Exception reporting overlay |
| `BackgroundService` | Foreground Service | Core monitoring loop, usage tracking, limit enforcement |
| `BackgroundActionService` | Service | Deferred actions |
| `AccessibilityService` | Accessibility Service | Foreground app detection |
| `AdminReceiver` | Device Admin Receiver | Device admin for robust enforcement |
| `NotificationListener` | Notification Listener | Notification monitoring |
| `BootReceiver` | BroadcastReceiver | Auto-restart on boot |
| `UpdateReceiver` | BroadcastReceiver | Re-init after app update |
| `TimesWidgetProvider` | AppWidget | Home screen widget showing remaining time |
| `U2fBroadcastReceiver` | BroadcastReceiver | USB U2F key events |

**Permissions**:
- `RECEIVE_BOOT_COMPLETED` — restart after reboot
- `VIBRATE` — haptic feedback
- `SYSTEM_ALERT_WINDOW` — overlays (lock, annoy)
- `PACKAGE_USAGE_STATS` — app usage tracking
- `WAKE_LOCK` — keep device awake during enforcement
- `FOREGROUND_SERVICE` — keep background service alive
- `CALL_PHONE` — emergency calling from lock screen
- `QUERY_ALL_PACKAGES` — enumerate apps
- `ACCESS_WIFI_STATE` — WiFi-based category rules
- `ACCESS_FINE_LOCATION` — WiFi SSID detection (SDK 23+)
- `POST_NOTIFICATIONS` — notifications
- `NFC` — NFC-based U2F keys

### 3.5 Data Model (Key Entities)

```
User (id, name, password/bcrypt, type: parent/child, timezone, flags)
  └─ Device (id, name, currentUserId, model, addedAt, networkTime, ...)
       └─ Category (id, childId, title, maxTime, timeWarnings, flags, ...)
            ├─ CategoryApp (categoryId, packageName)
            ├─ TimeLimitRule (categoryId, dayOfWeek, maxTime, ...)
            ├─ CategoryNetworkId (categoryId, wifiSsid)
            └─ UsedTimeItem (categoryId, day, usedTime)
  └─ ChildTask (id, childId, title, duration, ...)
  └─ SessionDuration (childId, maxSession, breakDuration, ...)
  └─ UserLimitLoginCategory (childId, loginCategoryId, ...)
  └─ ConfigurationItem (key, value)
  └─ AppActivity (packageName, activityTitle, ...)
```

### 3.6 How It Works — Operational Flow

```
1. PARENT SETUP:
   - Parent installs app → creates parent user (password + U2F key)
   - Creates child user → configures categories, time limits, rules
   - Optional: syncs config to child device via QR code / manual entry

2. CHILD DEVICE:
   - Child device receives config (sync or manual setup)
   - BackgroundService starts:
     a) 100ms loop: checks foreground app → tracks usage time
     b) 1s loop: checks time limits → enforces blocking
   - Usage tracked via UsageStatsManager + AccessibilityService

3. TIME LIMIT ENFORCEMENT:
   - Category limit reached → LockActivity overlay shown
   - Can show "annoy" overlay (must tap dice icon to dismiss)
   - Device Admin prevents uninstall/bypass
   - HomescreenActivity can act as custom launcher

4. MANIPULATION DETECTION:
   - Detects clock changes, app reinstalls, force stops
   - Triggers manipulation warnings and logging

5. SYNC:
   - JSON-based action/validation protocol over local network
   - Curve25519 key exchange for authenticated sync
   - U2F hardware key support for secure authentication

6. PARENT MODE:
   - Separate UI flow for viewing child usage
   - Overview screens, time charts, rule editing
```

### 3.7 Key Sub-Systems

**BackgroundTaskLogic**: The heart of the app. Runs multiple coroutine loops:
- `backgroundServiceLoop()`: Main monitoring loop (checks foreground app, updates used time, enforces limits)
- `syncDeviceStatusLoop()`: Updates device status for sync
- `backupDatabaseLoop()`: Database backup scheduling
- `annoyUserOnManipulationLoop()`: Detects and handles manipulation
- `checkForceKilled()`: Recovery after force kill

**Sync System**: A custom JSON-based sync protocol using:
- `sync/actions/` — Action definitions (changes to sync)
- `sync/validation/` — Validation of incoming sync data
- `ApplyActionUtil` — Applies actions locally

**U2F**: FIDO U2F support for authenticating sync operations (supports USB, NFC, BLE keys).

**Crypto**: `barcode/` package for QR code generation/sharing of device keys.

---

## 4. Side-by-Side Comparison

### 4.1 Architecture & Code Quality

| Aspect | LifeUpCatcher | OpenTimeLimit |
|--------|--------------|---------------|
| Architecture Pattern | MVVM + Repository | MVVM + Centralized AppLogic |
| DI Framework | Hilt (modern, annotation-based) | Manual singleton (no framework) |
| Reactive Streams | Kotlin Flow + StateFlow | LiveData + MutableLiveData |
| State Management | StateFlow in ViewModels | LiveData observes Room DAOs |
| Async | Coroutines (viewModelScope, serviceScope) | Coroutines (custom Threads + runAsync helpers) |
| Testing | Basic (JUnit + Espresso skeleton) | Basic (JUnit + Room testing) |

### 4.2 UI Comparison

| Aspect | LifeUpCatcher | OpenTimeLimit |
|--------|--------------|---------------|
| Framework | **Jetpack Compose** (declarative) | **XML Views** (imperative) |
| Design System | Material 3 | Material Components + AppCompat |
| Theming | Compose `MaterialTheme` | XML styles/themes |
| Dynamic Colors | Built-in (Material 3) | `DynamicColors.applyToActivitiesIfAvailable()` |
| Navigation | Manual tab switching in Compose | Navigation Component + Safe Args |
| Screens | ~8 screens (tabs + dialogs) | ~30+ fragments |
| Custom Launcher | LauncherScreen (Compose) | HomescreenActivity (XML) |
| Widget | None | TimesWidgetProvider |

### 4.3 Blocking Techniques

| Technique | LifeUpCatcher | OpenTimeLimit |
|-----------|--------------|---------------|
| App Disable (pm disable) | ✅ Via Shizuku | ✅ Via Shizuku |
| Home Override | ✅ Accessibility Service | ✅ Custom Launcher (HomescreenActivity) |
| Lock Overlay | ✅ Random challenge overlay | ✅ Full LockActivity + Device Admin |
| Work Profile | ✅ Quiet mode toggle | ❌ |
| DNS Locking | ✅ Force Private DNS | ❌ (WiFi SSID-based categories instead) |
| Device Admin | ❌ | ✅ Robust enforcement |
| Usage Tracking | ❌ | ✅ UsageStatsManager + foreground loop |
| Session Limits | ❌ | ✅ Session duration + break scheduling |
| Time Earning (Tasks) | ❌ | ✅ Child task → time earning system |

### 4.4 External Trigger Mechanism

| Aspect | LifeUpCatcher | OpenTimeLimit |
|--------|--------------|---------------|
| Trigger Source | **LifeUp app** (broadcasts) | **Time-based rules** (internal) |
| Activation | Broadcast intents from external app | Scheduled by BackgroundTaskLogic |
| Deactivation | Another broadcast or time-based | Time limits exceeded, day change |
| Configuration | Manual per-item creation | Parent-configured via UI or sync |

### 4.5 Integration & Extensibility

| Feature | LifeUpCatcher | OpenTimeLimit |
|---------|--------------|---------------|
| Health Connect | ✅ Sleep tracking | ❌ |
| Shizuku | ✅ App disable + DNS + Work Profile | ✅ App disable (less extensive) |
| Device Sync | ❌ | ✅ Multi-device cryptographic sync |
| U2F | ❌ | ✅ USB/NFC/BLE hardware keys |
| QR Code Pairing | ❌ | ✅ ZXing-based device pairing |
| Biometrics | ❌ | ✅ Fingerprint/face auth |
| Widget | ❌ | ✅ Remaining time widget |
| Notification Listener | ❌ | ✅ |
| Multi-User (Parent/Child) | ❌ | ✅ Full parent/child model |
| Child Tasks | ❌ | ✅ Task → time earning |

---

## 5. Strengths & Gaps

### LifeUpCatcher Strengths
- ✅ Modern tech stack (Compose, Material 3, Hilt, Flow, DataStore)
- ✅ Clean, maintainable codebase (small, focused)
- ✅ Unique integration with LifeUp gamification app
- ✅ DNS locking feature (not in OpenTimeLimit)
- ✅ Health Connect sleep tracking with rewards/punishments
- ✅ Random lock challenge for self-discipline
- ✅ Work profile quiet mode toggling

### LifeUpCatcher Gaps
- ❌ No usage tracking (depends entirely on LifeUp broadcasts)
- ❌ No time limit rules (only binary active/inactive)
- ❌ No session duration management
- ❌ No device-to-device sync
- ❌ No multi-user support
- ❌ No Device Admin (less robust enforcement)
- ❌ No widget
- ❌ Manual navigation (could benefit from Navigation Component)

### OpenTimeLimit Strengths
- ✅ Mature, battle-tested codebase (7+ versions, GPL)
- ✅ Comprehensive parental control feature set
- ✅ Robust enforcement (Device Admin + Shizuku + Accessibility)
- ✅ Multi-device sync with cryptographic security
- ✅ U2F hardware key support
- ✅ Custom launcher mode for children
- ✅ Time earning through tasks
- ✅ Widget for remaining time
- ✅ Manipulation detection and anti-bypass measures
- ✅ Extensive configuration options (per-category, per-day, per-session)

### OpenTimeLimit Gaps
- ❌ XML-based UI (no Compose — harder to maintain going forward)
- ❌ No Hilt/DI framework (manual singletons)
- ❌ Older coroutines patterns (custom Threads class)
- ❌ No Health Connect integration
- ❌ No DNS locking
- ❌ No Compose — less modern UI development experience
- ❌ Complex codebase (steep learning curve for contributors)

---

## 6. Ideas for Cross-Pollination

Features that LifeUpCatcher could adopt from OpenTimeLimit:
- **Device Admin** for more robust enforcement
- **Session duration management** with breaks
- **Usage tracking** via UsageStatsManager
- **Widget** to show blocking status
- **Navigation Component** for structured navigation
- **Anti-manipulation detection**

Features that OpenTimeLimit could adopt from LifeUpCatcher:
- **Jetpack Compose + Material 3** migration
- **Hilt DI framework**
- **Health Connect integration** for sleep/time-of-day rules
- **DNS locking** as an additional restriction method
- **Flow-based reactive architecture** (over LiveData)
- **DataStore** for simpler preference management
