# ScrollDock

ScrollDock is an offline Android accessibility app that adds a movable navigation bar to selected apps.

## Version

Current app version: **0.2.0**

GitHub Actions reads `versionName` from `app/build.gradle.kts` and publishes the matching file name, such as `ScrollDock-v0.2.0.apk`.

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

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Gradle 9.5.0, or Android Studio with a compatible embedded Gradle

```bash
gradle testDebugUnitTest assembleDebug
```

The local debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions copies it to a version-matched artifact name.

## Enable

1. Install the APK.
2. Open ScrollDock and accept the accessibility disclosure.
3. Enable **ScrollDock navigation** in Android Accessibility settings.
4. Choose ChatGPT or other launcher apps.
5. Open the local test page before testing third-party apps.
6. For an app with multiple scrollable panes, long-press the handle and choose **Choose scroll area**.

## Architecture

- `ScrollAccessibilityService`: foreground scope, lifecycle, correlated scroll observations, temporary-hide restoration.
- `OverlayController`: accessibility overlay, drag, target picker, message controls, feedback.
- `ScrollableNodeResolver`: structural candidate scoring, locked-target recovery, node snapshots.
- `ScrollCommandExecutor`: semantic actions, gesture fallback, cancellation, edge and timeout handling.
- `MessageNavigator`: structural previous/next message navigation without reading message text.
- `GestureFallback`: safe central swipe gestures with keyboard avoidance.
- `Prefs`: local defaults and per-app profiles.
