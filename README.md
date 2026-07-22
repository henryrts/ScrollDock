# ScrollDock

ScrollDock is an offline Android accessibility app that adds a movable navigation bar to selected apps.

## Version

Current app version: **0.2.1**

GitHub Actions reads `versionName` from `app/build.gradle.kts` and publishes the matching file name, such as `ScrollDock-v0.2.1.apk`.

## Controls

- **Top**: repeatedly moves toward the start until the edge is confirmed or the safety limit stops the command.
- **Page Up**: moves approximately one configured viewport upward.
- **Page Down**: moves approximately one configured viewport downward.
- **Bottom**: repeatedly moves toward the end until the edge is confirmed or the safety limit stops the command.
- Hold Page Up or Page Down for continuous scrolling. Release to stop.
- Optional **Previous message** and **Next message** buttons navigate structural conversation blocks.
- The handle menu includes previous/next user-message and assistant-response commands.
- Drag the handle to reposition the bar. Long-press it for target selection, method selection, collapse, temporary hiding, settings, or disable actions.

## Reliable navigation

ScrollDock correlates scroll events with the selected window and structural target instead of treating every screen scroll as command success. It also distinguishes:

- confirmed movement
- confirmed top or bottom edge
- unavailable target or blocked action
- safety timeout before an edge could be confirmed

## Scroll targets

Each app can use one of three methods:

- **Automatic target**: chooses the strongest visible vertical scroll container.
- **Locked target**: the user taps an outlined scrollable area and ScrollDock remembers its structural path and identifiers.
- **Gesture only**: skips semantic accessibility scrolling and performs a configured swipe.

## Per-app profiles

Profiles independently store:

- button size
- opacity
- page distance
- repeat interval
- collapsed state
- scroll method
- message-button visibility
- portrait and landscape positions
- locked target signature

## Privacy

- No `INTERNET` permission.
- No analytics, ads, accounts, screen capture, clipboard access, or content storage.
- Accessibility nodes are inspected only for structural scrolling and message-boundary capabilities. Node text is never read, logged, stored, or transmitted.
- Commands run only after a direct control press and only inside explicitly selected apps.
- App backup is disabled.

See [PRIVACY.md](PRIVACY.md) for the complete privacy statement.

## Install and enable from an APK

Android can block sensitive settings for apps installed from an APK. ScrollDock now opens a dedicated setup screen until its accessibility service is enabled.

1. Install the APK and open ScrollDock.
2. Accept the accessibility disclosure.
3. If Android displays **Restricted setting**, tap **Close**.
4. Open **Settings > Apps > ScrollDock**. Alternatively, hold the ScrollDock icon and tap **App info**.
5. Tap the **three-dot menu** in the upper-right.
6. Select **Allow restricted settings** and confirm with your PIN or fingerprint.
7. Open **Settings > Accessibility > Installed apps > ScrollDock**.
8. Turn the service on and tap **Allow**.
9. Choose ChatGPT or other apps inside ScrollDock.
10. Open the local test page before testing third-party apps.
11. For an app with multiple scrollable panes, long-press the handle and choose **Choose scroll area**.

Only allow restricted settings when you trust the APK source. ScrollDock cannot enable or bypass this Android protection itself.

## Why an APK update may not install over the old app

This is normally a signing-key problem, not an Android 16 problem. Android accepts an update only when:

- the package name is unchanged
- the new `versionCode` is higher or equal as allowed by the installer
- the update is signed by the same certificate as the installed app, or by a valid rotated key

A fresh GitHub Actions runner normally creates a new debug signing key. APKs produced by separate runs can therefore look like different developers to Android, even though both files are named ScrollDock. Android then refuses the update and requires the old app to be uninstalled.

The workflow now supports a persistent release key stored in GitHub Actions secrets. Once that key is configured:

1. Uninstall the previously debug-signed ScrollDock one final time.
2. Install the first persistently signed APK.
3. Later APKs signed with the same key and a higher `versionCode` can install over it without clearing the app first.

Without the signing secrets, CI still produces a debug APK for testing, but in-place updates are not guaranteed. See [docs/SIGNING.md](docs/SIGNING.md).

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Gradle 9.5.0, or Android Studio with a compatible embedded Gradle

```bash
gradle testDebugUnitTest assembleDebug
```

The local debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions copies the selected debug or signed release build to a version-matched artifact name and publishes a separate certificate report.

## Architecture

- `SetupActivity`: disclosure, sideloaded-app restricted-settings guidance, and accessibility setup gate.
- `ScrollAccessibilityService`: foreground scope, lifecycle, correlated scroll observations, temporary-hide restoration.
- `OverlayController`: accessibility overlay, drag, target picker, message controls, feedback.
- `ScrollableNodeResolver`: structural candidate scoring, locked-target recovery, node snapshots.
- `ScrollCommandExecutor`: semantic actions, gesture fallback, cancellation, edge and timeout handling.
- `MessageNavigator`: structural previous/next message navigation without reading message text.
- `GestureFallback`: safe central swipe gestures with keyboard avoidance.
- `Prefs`: local defaults and per-app profiles.
