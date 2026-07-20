# Implementation Plan: Integrate LifeUpCatcher Features into OpenTimeLimit

## Goal

Integrate LifeUpCatcher's self-discipline features (Shizuku app disabling, LifeUp broadcast integration, Work Profile lock, Force DNS) into OpenTimeLimit's category/rule system, creating a unified self-control app. All new features coexist with OpenTimeLimit's existing blocking mechanisms.

---

## Research Summary

| Discovery | Detail |
|-----------|--------|
| Shizuku in OpenTimeLimit | **Already declared as dependency** (`dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`) but **completely unused** in source code |
| Category flags system | `Category.flags: Long` + `CategoryFlags` object already exists (bitmask pattern). Currently only `HAS_BLOCKED_NETWORK_LIST = 1L` |
| Configuration system | `ConfigurationItem.kt` / `ConfigDao` uses typed key-value config table. Already has `RandomUnlockEnabled`/`RandomUnlockLength` |
| Blocking enforcement | `BackgroundTaskLogic` → `AppBaseHandling` classes → `LockActivity` overlay. Multiple blocking types (App, Activity levels) |
| Auth system | `ActivityViewModel` + `NewLoginFragment` with password (bcrypt) + biometric + U2F. Already has random unlock challenge |
| UI pattern | XML layouts + DataBinding + Fragments. Category settings in `CategorySettingsFragment` |
| LifeUp integration | **Does not exist** — entirely new feature |

---

## Proposed Changes

### Phase 1: Shizuku Infrastructure & App Disabling

#### 1.1 [NEW] `app/src/main/java/io/timelimit/android/integration/platform/shizuku/ShizukuIntegration.kt`
A singleton service that manages the Shizuku connection lifecycle, provides methods for:
- `isShizukuAvailable(): Boolean` — checks if Shizuku is running
- `requestPermission()` — requests Shizuku permission
- `executeCommand(command: String): String?` — runs shell commands via Shizuku
- `disablePackage(packageName: String): Boolean` — runs `pm disable-user --user 0 <pkg>`
- `enablePackage(packageName: String): Boolean` — runs `pm enable <pkg>`
- `setPrivateDns(hostname: String): Boolean` — sets global Private DNS
- `toggleWorkProfile(enable: Boolean): Boolean` — toggles work profile quiet mode
- `registerStateListener(listener: (Boolean) -> Unit)` — listen for Shizuku state changes

#### 1.2 [MODIFY] `app/src/main/java/io/timelimit/android/data/model/Category.kt`
Add new category flags for blocking type selection:
```kotlin
object CategoryFlags {
    const val HAS_BLOCKED_NETWORK_LIST = 1L
    // NEW flags for blocking technique
    const val BLOCKING_TYPE_MASK = 0b1110L          // bits 1-3 (values 0-7 to be shifted)
    const val BLOCKING_TYPE_NORMAL = 0L              // default - normal lock overlay
    const val BLOCKING_TYPE_SHIZUKU_DISABLE = 2L     // Shizuku app disabling
    const val BLOCKING_TYPE_WORK_PROFILE = 4L        // Work profile quiet mode
    const val BLOCKING_TYPE_FORCE_DNS = 6L           // Force Private DNS
    // ... other flags shift by 4 bits
}
```
Add helper extension properties:
```kotlin
val Category.blockingType: Long get() = flags and CategoryFlags.BLOCKING_TYPE_MASK
val Category.isShizukuDisableBlocking: Boolean get() = blockingType == CategoryFlags.BLOCKING_TYPE_SHIZUKU_DISABLE
val Category.isWorkProfileBlocking: Boolean get() = blockingType == CategoryFlags.BLOCKING_TYPE_WORK_PROFILE
val Category.isForceDns: Boolean get() = blockingType == CategoryFlags.BLOCKING_TYPE_FORCE_DNS
```

#### 1.3 [NEW] `app/src/main/java/io/timelimit/android/logic/ShizukuBlockingLogic.kt`
New logic class (following pattern of `SuspendAppsLogic`, `AnnoyLogic`):
```kotlin
class ShizukuBlockingLogic(appLogic: AppLogic) {
    // Observes active categories with BLOCKING_TYPE_SHIZUKU_DISABLE
    // When category is blocked → runs pm disable-user on all apps in the category
    // When category is unblocked → runs pm enable on those apps
    // Tracks last applied state per package to avoid redundant operations
    // Handles Shizuku connection/disconnection gracefully
}
```

#### 1.4 [MODIFY] `app/src/main/java/io/timelimit/android/logic/AppLogic.kt`
Initialize `ShizukuBlockingLogic` in `AppLogic` init block (alongside existing `SuspendAppsLogic`, etc.)

#### 1.5 [MODIFY] `app/src/main/java/io/timelimit/android/logic/BackgroundTaskLogic.kt`
In the background loop: after determining blocking reasons for categories, if the blocking technique is Shizuku disable, delegate to `ShizukuBlockingLogic` instead of (or in addition to) showing the lock overlay.

#### 1.6 [MODIFY] `app/src/main/res/layout/fragment_category_settings.xml`
Add a dropdown/spinner labeled "Blocking Technique" to the category settings screen:
- **Normal** (default) — existing behavior (lock overlay, time tracking)
- **Shizuku App Disable** — disables apps via pm when blocked
- **Work Profile Lock** — toggles work profile quiet mode when blocked
- **Force DNS** — forces a specific Private DNS hostname

#### 1.7 [MODIFY] `app/src/main/java/io/timelimit/android/ui/manage/category/settings/CategorySettingsFragment.kt`
Bind the blocking technique dropdown to the category's flags. Add a hostname input field (visible only when "Force DNS" is selected). Add validation and wiring.

#### 1.8 [MODIFY] `app/src/main/java/io/timelimit/android/AndroidManifest.xml`
Add Shizuku provider entry (required for Shizuku to work):
```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:exported="true"
    android:multiprocess="false"
    android:enabled="true" />
```

---

### Phase 2: LifeUp Broadcast Receiver Integration

#### 2.1 [NEW] `app/src/main/java/io/timelimit/android/integration/lifeup/LifeUpIntegration.kt`
A class that manages the LifeUp broadcast receiver:
```kotlin
class LifeUpIntegration(appLogic: AppLogic) {
    // Listens for broadcasts: app.lifeup.item.countdown.{start, stop, complete}
    // Maps shop item names to TimeLimitRules via configuration
    // On "start": temporarily disables the linked rule (sets disableLimitsUntil or a flag)
    // On "stop"/"complete": re-enables the rule
    // Stores active LifeUp item states
}
```

#### 2.2 [MODIFY] `app/src/main/java/io/timelimit/android/data/model/TimeLimitRule.kt`
Add new fields to support LifeUp integration:
```kotlin
data class TimeLimitRule(
    // ... existing fields ...
    @ColumnInfo(name = "lifeup_shop_item_name")
    val lifeUpShopItemName: String = "",       // empty = LifeUp integration disabled
    @ColumnInfo(name = "lifeup_override_active")
    val lifeUpOverrideActive: Boolean = false  // true when LifeUp is overriding
)
```

> [!IMPORTANT]
> **How LifeUp integration works per rule**: When a LifeUp countdown item starts that matches `lifeUpShopItemName`, the rule is **temporarily overridden/disabled** — meaning the blocking enforced by that rule is suspended. When the LifeUp item stops or completes, the rule resumes normal enforcement. This is the inverse of LifeUpCatcher's behavior (which activates blocking on LifeUp start). The rationale: in a parental control context, LifeUp acts as a "reward unlock" — completing a task temporarily relaxes the rule.

#### 2.3 [MODIFY] `app/src/main/java/io/timelimit/android/AndroidManifest.xml`
Add the LifeUp broadcast receiver:
```xml
<receiver android:name=".integration.lifeup.LifeUpBroadcastReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="app.lifeup.item.countdown.start" />
        <action android:name="app.lifeup.item.countdown.stop" />
        <action android:name="app.lifeup.item.countdown.complete" />
    </intent-filter>
</receiver>
```

#### 2.4 [MODIFY] `app/src/main/res/layout/fragment_edit_time_limit_rule_dialog.xml`
Add UI elements to the time limit rule edit dialog:
- A toggle switch: "Integrate with LifeUp"
- An input field: "LifeUp Shop Item Name" (visible only when toggle is on)

#### 2.5 [MODIFY] `app/src/main/java/io/timelimit/android/ui/manage/category/timelimit_rules/edit/EditTimeLimitRuleDialogFragment.kt`
Bind the new LifeUp fields. Save/update the rule with LifeUp integration data.

#### 2.6 [MODIFY] `app/src/main/java/io/timelimit/android/logic/BackgroundTaskLogic.kt`
When evaluating rule enforcement, check if `lifeUpOverrideActive == true` — if so, skip blocking for that rule.

---

### Phase 3: Work Profile Lock

#### 3.1 [NEW] `app/src/main/java/io/timelimit/android/logic/WorkProfileBlockingLogic.kt`
```kotlin
class WorkProfileBlockingLogic(appLogic: AppLogic) {
    // Detects work profile user handle
    // When category with BLOCKING_TYPE_WORK_PROFILE is blocked:
    //   - Toggles work profile quiet mode ON (disables work profile)
    // When category is unblocked:
    //   - Toggles work profile quiet mode OFF (enables work profile)
    // Monitors work profile state changes via ContentObserver
    // Requires MODIFY_QUIET_MODE permission (grants via Shizuku if possible)
}
```

> [!NOTE]
> The work profile blocking technique works like LifeUpCatcher's WORK_PROFILE technique. It relies on `UserManager.requestQuietModeEnabled()` which requires the `MODIFY_QUIET_MODE` permission. This permission is normally granted via Shizuku to non-system apps.

#### 3.2 [MODIFY] `app/src/main/java/io/timelimit/android/logic/AppLogic.kt`
Initialize `WorkProfileBlockingLogic`.

#### 3.3 [MODIFY] `app/src/main/java/io/timelimit/android/AndroidManifest.xml`
Already has `MODIFY_QUIET_MODE` permission? Check — if not, add it with `tools:ignore="ProtectedPermissions"`.

---

### Phase 4: Force DNS

#### 4.1 [NEW] `app/src/main/java/io/timelimit/android/logic/DnsBlockingLogic.kt`
```kotlin
class DnsBlockingLogic(appLogic: AppLogic) {
    // Reads the DNS hostname from a ConfigurationItem for the category
    // When Force DNS category is active and blocked:
    //   - Uses Shizuku to run: settings put global private_dns_mode hostname
    //   - Then: settings put global private_dns_specifier <hostname>
    // Registers a ContentObserver on Settings.Global.CONTENT_URI to detect changes
    // When DNS setting is changed externally, immediately reverts it
    // When category is unblocked, restores original DNS settings
}
```

#### 4.2 [NEW] `app/src/main/java/io/timelimit/android/data/model/CategoryNetworkId.kt` or configuration
Store the DNS hostname per-category. Since Force DNS is a per-category setting, we have two options:
- **Option A**: Add a new `forceDnsHostname: String` field to the Category entity (requires DB migration)
- **Option B** (Recommended): Use a `CategoryNetworkId`-like entity linked to the category, or use a `ConfigurationItem` keyed with the category ID. This avoids a DB migration.

**Chosen: Option B** — Use `ConfigurationItem` with keys like `dns_force_hostname_<categoryId>`.

#### 4.3 [MODIFY] `app/src/main/java/io/timelimit/android/logic/AppLogic.kt`
Initialize `DnsBlockingLogic`.

#### 4.4 [MODIFY] `app/src/main/res/layout/fragment_category_settings.xml`
Add a text input for DNS hostname, visible only when Force DNS blocking type is selected.

#### 4.5 [MODIFY] `app/src/main/java/io/timelimit/android/ui/manage/category/settings/CategorySettingsFragment.kt`
Bind the DNS hostname input, save/load via `ConfigurationItem`.

---

### Phase 5: Ensure Password Lock Compatibility

> [!NOTE]
> OpenTimeLimit already has a robust password lock system with:
> - Password authentication (bcrypt hashed in `User.password`)
> - Biometric authentication
> - U2F hardware key support
> - Random unlock challenge (`ConfigurationItemType.RandomUnlockEnabled/Length`)
> - Lock overlay (`LockActivity`) with separate task affinity
> - Login fragment (`NewLoginFragment`) used both for parent auth and lock screen unlock

#### 5.1 [VERIFY] Existing lock system with new blocking techniques
- Shizuku disable: When apps are disabled via `pm disable-user`, the lock screen is irrelevant because apps are not openable. However, the lock screen should show if the user tries to open the disabled app (via Settings or other means). **No changes needed.**
- Work profile lock: When work profile is quieted, apps are hidden. If the user re-enables the profile, the logic re-quiets it. The lock overlay should optionally inform the user why. **Minor enhancement.**
- Force DNS: No lock screen interaction needed. DNS is enforced at system level. **No changes needed.**
- LifeUp overrides: When a LifeUp item suspends a rule, if the user was locked out, they should be let back in. The lock screen should close when `lifeUpOverrideActive` becomes true. **Needs implementation.**

#### 5.2 [MODIFY] `app/src/main/java/io/timelimit/android/ui/lock/LockActivity.kt`
Add an observer for LifeUp override state. When the currently blocked category's rule is overridden by LifeUp, dismiss the lock screen.

#### 5.3 [MODIFY] `app/src/main/java/io/timelimit/android/ui/lock/LockModel.kt`
Include LifeUp override status in the lock screen content model, so the UI can show "This limit has been temporarily lifted by LifeUp" or similar messaging.

---

## Data Model Changes Summary

### Category Entity (`Category.kt`)
| Change | Detail |
|--------|--------|
| New flags | `BLOCKING_TYPE_MASK`, `BLOCKING_TYPE_NORMAL`, `BLOCKING_TYPE_SHIZUKU_DISABLE`, `BLOCKING_TYPE_WORK_PROFILE`, `BLOCKING_TYPE_FORCE_DNS` |
| New computed properties | `isShizukuDisableBlocking`, `isWorkProfileBlocking`, `isForceDns` |

### TimeLimitRule Entity (`TimeLimitRule.kt`)
| Change | Detail |
|--------|--------|
| New field | `lifeUpShopItemName: String` (default `""`) |
| New field | `lifeUpOverrideActive: Boolean` (default `false`) |

### ConfigurationItem Keys (new)
| Key | Type | Purpose |
|-----|------|---------|
| `dns_force_hostname_<categoryId>` | String | DNS hostname for Force DNS categories |
| `lifeup_integration_enabled` | Boolean | Global toggle for LifeUp integration |

### Database Migration
- `TimeLimitRule` table: add `lifeup_shop_item_name` (TEXT, default "") and `lifeup_override_active` (INTEGER, default 0)
- Version bump + migration path

---

## New Files Summary

| File | Purpose |
|------|---------|
| `integration/platform/shizuku/ShizukuIntegration.kt` | Shizuku lifecycle + command execution |
| `integration/lifeup/LifeUpIntegration.kt` | LifeUp broadcast handler + rule override logic |
| `integration/lifeup/LifeUpBroadcastReceiver.kt` | Android BroadcastReceiver for LifeUp intents |
| `logic/ShizukuBlockingLogic.kt` | App disabling enforcement via Shizuku |
| `logic/WorkProfileBlockingLogic.kt` | Work profile quiet mode enforcement |
| `logic/DnsBlockingLogic.kt` | Force DNS enforcement via Shizuku |

## Modified Files Summary

| File | Change |
|------|--------|
| `AndroidManifest.xml` | Add Shizuku provider, LifeUp receiver |
| `data/model/Category.kt` | Add blocking type flags + helpers |
| `data/model/TimeLimitRule.kt` | Add LifeUp fields |
| `logic/AppLogic.kt` | Init new logic classes |
| `logic/BackgroundTaskLogic.kt` | Integrate new blocking techniques |
| `res/layout/fragment_category_settings.xml` | Add blocking type dropdown + DNS input |
| `res/layout/fragment_edit_time_limit_rule_dialog.xml` | Add LifeUp toggle + input |
| `ui/manage/category/settings/CategorySettingsFragment.kt` | Bind new UI elements |
| `ui/manage/category/timelimit_rules/edit/EditTimeLimitRuleDialogFragment.kt` | Bind LifeUp fields |
| `ui/lock/LockActivity.kt` | Handle LifeUp override dismiss |
| `ui/lock/LockModel.kt` | Add LifeUp status to model |

---

## User Review Required

> [!IMPORTANT]
> **LifeUp integration direction**: In this plan, LifeUp **overrides (temporarily disables)** a rule when a countdown starts. This differs from LifeUpCatcher's approach where LifeUp **activates** blocking. Please confirm this is the desired behavior:
> - **Option A (Plan's default)**: LifeUp countdown START → temporarily LIFT the rule (reward mode — "you did your task, enjoy some free time")
> - **Option B**: LifeUp countdown START → temporarily ACTIVATE the rule (punishment mode — "focus mode is on, these apps are blocked")

> [!IMPORTANT]
> **Shizuku dependency**: Shizuku is already in the build.gradle but unused. Users will need to install the Shizuku app separately. Should we add in-app guidance for installing/configuring Shizuku?

> [!WARNING]
> **Database migration**: Adding fields to `TimeLimitRule` requires a Room database migration. The existing migration chain must be extended carefully.

---

## Open Questions

1. **Blocking type per category vs per rule**: Currently the blocking technique (Shizuku disable, Work Profile, Force DNS) is at the **category level**. Should it be configurable per **time limit rule** within a category instead? E.g., Monday rule uses normal blocking, Tuesday rule uses Shizuku disable.

2. **DNS hostname fallback**: When the Force DNS category is unblocked, should we restore the original DNS setting, or set to "automatic"? What if the category has multiple rules with different DNS hostnames?

3. **Shizuku disabled app state tracking**: When Shizuku disables an app, should we track which category "owns" that disabled state? What happens if two categories both target the same app with different blocking techniques?

4. **LifeUp shop item name matching**: Should the matching be exact string match, case-insensitive, or support wildcards? What happens if the user changes the shop item name in LifeUp?

5. **Work profile handling**: If multiple categories use work profile blocking but have different schedules (e.g., one active Mon-Fri, another Sat-Sun), how should conflicts be resolved? The work profile is a binary state — it's either quiet or not.

---

## Verification Plan

### Automated Tests
- Unit tests for `ShizukuBlockingLogic` (mock Shizuku commands)
- Unit tests for `LifeUpIntegration` (simulate broadcast intents)
- Unit tests for `DnsBlockingLogic` (mock ContentObserver)
- Database migration test for new TimeLimitRule fields
- Category flag serialization/deserialization tests

### Manual Verification
1. Install Shizuku app, start Shizuku, verify app disabling/enabling works for a test category
2. Set up a LifeUp shop item, trigger countdown start/stop, verify rule override behavior
3. Configure Force DNS category, verify Private DNS is set and cannot be changed
4. Test work profile quiet mode toggle with a work profile
5. Verify password lock still appears and works alongside new blocking types
6. Test all blocking techniques work with existing LockActivity
