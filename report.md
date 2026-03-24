# Open TimeLimit - App Architecture and Inner Workings Report

This report provides a detailed overview of the inner workings of the `opentimelimit-android` application. The app functions as a parental control and screen-time management tool, which enforces time limits, blocks applications, and prevents tampering.

## 1. Core Services (Daemons)

The app relies heavily on background services to continuously monitor the device's state and enforce limits.

*   **`BackgroundService`**: A persistent Android foreground service. It ensures the app's core logic keeps running in the background without being killed by the Android system. It displays an ongoing, low-priority notification showing the current status (e.g., how much time is remaining).
*   **`BackgroundActionService`**: A service designed to handle actions triggered by `PendingIntent`s, typically from notifications. Examples include revoking temporarily allowed apps or switching the device's default user.
*   **`AccessibilityService`**: A critical security component. This service allows the app to monitor UI events and, most importantly, execute global actions like `performGlobalAction(GLOBAL_ACTION_HOME)`. This prevents users from bypassing the lock screen by rapidly switching apps or using the "Recents" menu.
*   **`NotificationListener`**: Binds to the system's notification listener service to monitor or block notifications from apps that are currently restricted or blocked.
*   **`TimesWidgetService`**: Provides the data and views for the app's homescreen widgets.

## 2. Broadcast Receivers

Receivers listen to system-wide events to ensure the app functions properly across reboots and updates.

*   **`BootReceiver`**: Listens for `ACTION_BOOT_COMPLETED`. It ensures that the app's monitoring services (`BackgroundService` and logic loops) restart automatically when the device is turned on.
*   **`AdminReceiver`**: Acts as the Android Device Administrator. Having device admin rights makes it significantly harder for a child to uninstall the application or forcefully stop it. It also enables features like device lockdown.
*   **`UpdateReceiver`**: Listens for `MY_PACKAGE_REPLACED` to run necessary database migrations or logic updates immediately after the app is updated via an app store.

## 3. Core Logic & Scripts

The `logic` package contains the "brain" of the application, running continuously to enforce rules.

*   **`AppLogic` (and `DefaultAppLogic`)**: The central entry point for all business logic, tying together the database, platform APIs, and background tasks.
*   **`BackgroundTaskLogic`**: The most complex and vital script in the app. It runs infinite coroutine loops (`backgroundServiceLoop`, `syncDeviceStatusLoop`, `backupDatabaseLoop`) that:
    *   Determine the current foreground app using `UsageStatsManager`.
    *   Map the app to a specific category and check its remaining time limit.
    *   Subtract active usage time from the database.
    *   Trigger `LockActivity` if the time limit is reached or the app is explicitly blocked.
    *   Monitor system permissions to detect if the child has revoked required permissions (e.g., overlay or accessibility permissions).
*   **`WatchdogLogic`**: A self-monitoring script that runs in the background. It continuously checks if the database thread is responsive. If a deadlock or freeze is detected, it forces an app restart to prevent users from exploiting a frozen app to bypass limits.
*   **`RealTimeLogic`**: Hooks into system clock changes to ensure that time limits are adjusted or enforced correctly even if the user attempts to cheat by changing the device's time.
*   **`ManipulationLogic` & `AnnoyLogic`**: Detects if the app has been tampered with (e.g., force-stopped by a task killer or permissions revoked) and triggers annoyance screens or soft lockouts.

## 4. User Interface (UI Elements)

The app utilizes a Single-Activity architecture powered by Android Jetpack Navigation (`nav_graph.xml`).

### Activities
*   **`MainActivity`**: The primary host activity for the entire setup and management UI.
*   **`LockActivity`**: The screen that overlays the device when an app is blocked or a time limit is reached. It prevents interaction with the underlying application.
*   **`HomescreenActivity`**: A custom, rudimentary Android Launcher. Setting the app as the default launcher provides an extra layer of security against bypassing limits.
*   **`AnnoyActivity` / `UnlockAfterManipulationActivity`**: Deterrent screens shown to the user if the system detects that tampering has occurred.
*   **`DiagnoseExceptionActivity`**: A translucent activity used to display unhandled exceptions or internal errors to the user gracefully.

### Fragments (Managed by MainActivity)
*   **`LaunchFragment`**: The routing fragment that decides whether to show the setup screen, the parent dashboard, or the child overview based on the device's configuration.
*   **`Setup... Fragments`** (`SetupTermsFragment`, `SetupLocalModeFragment`, etc.): The onboarding flow guiding the user to grant necessary permissions (Accessibility, Usage Access, Device Admin) and configure the device.
*   **`OverviewFragment`**: The main dashboard.
*   **`ManageChildFragment` & `ManageCategoryFragment`**: The core interfaces for parents to set time limits, define blocked time areas (e.g., bedtime), and categorize applications.
*   **`ManageDeviceFragment`**: Allows parents to configure device-wide settings, enforce permissions, and toggle experimental features.
*   **`DiagnoseMainFragment`**: A suite of tools built into the app to help users troubleshoot why an app isn't being blocked, check clock synchronization, and test foreground app detection.
*   **`ParentModeFragment`**: A secure area requiring a PIN or U2F authentication to alter settings.

## Summary

`Open TimeLimit` is built as an aggressive, highly persistent monitoring tool. It uses a combination of Android's standard background services, accessibility APIs, and device administration rights to create a robust wall against tampering. The core engine (`BackgroundTaskLogic`) acts as the continuous enforcer, while the UI relies heavily on modern Android architecture (Jetpack Navigation, LiveData, and ViewModels) to allow parents to configure these tight restrictions.