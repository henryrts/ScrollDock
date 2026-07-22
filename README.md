# ScrollDock

ScrollDock is an offline Android accessibility app that adds a movable navigation bar to selected apps.

## Controls

- **Top**: repeatedly moves toward the start, stopping at the edge or after six seconds.
- **Page Up**: moves approximately 85% of one viewport upward.
- **Page Down**: moves approximately 85% of one viewport downward.
- **Bottom**: repeatedly moves toward the end, stopping at the edge or after six seconds.
- Hold Page Up or Page Down for continuous scrolling. Release to stop.
- Drag the handle to reposition the bar. Long-press it for collapse, temporary hiding, app hiding, settings, or disable actions.

## Privacy

- No `INTERNET` permission.
- No analytics, ads, accounts, screen capture, clipboard access, or content storage.
- Accessibility nodes are inspected only for structural scrolling capabilities. Text is never read, logged, stored, or transmitted.
- Commands run only after a direct control press and only inside explicitly selected apps.
- App backup is disabled.

See [PRIVACY.md](PRIVACY.md) for the complete privacy statement.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37
- Android SDK Build Tools 36.0.0
- Gradle 9.5.0, or Android Studio with a compatible embedded Gradle

```bash
gradle testDebugUnitTest assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Enable

1. Install the APK.
2. Open ScrollDock and accept the prominent accessibility disclosure.
3. Open Android accessibility settings and enable **ScrollDock navigation**.
4. Choose ChatGPT or other launcher apps.
5. Open the local test page before testing third-party apps.

## Architecture

- `ScrollAccessibilityService`: foreground scope, lifecycle, event observation.
- `OverlayController`: accessibility overlay, drag, collapse, menu, feedback.
- `ScrollableNodeResolver`: structural candidate scoring without reading text.
- `ScrollCommandExecutor`: semantic actions, gesture fallback, cancellation, safety limits.
- `GestureFallback`: safe central swipe gestures with keyboard avoidance.
- `Prefs`: local configuration and per-orientation overlay position.

## Current scope

Version 0.1 implements the functional MVP. Auto-fade, horizontal layout, button reordering, per-app profiles, German localization, import/export, and follow-latest mode remain later work.
