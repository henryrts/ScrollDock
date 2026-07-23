# Privacy statement

ScrollDock operates locally on the Android device.

## Accessibility access

ScrollDock uses Android Accessibility APIs to:

1. determine whether an explicitly selected app is in the foreground;
2. locate a visible scrollable container using structural properties and supported action identifiers;
3. perform a scroll action after the user presses a ScrollDock control;
4. observe non-content scroll events to determine whether movement occurred;
5. inspect structural target metadata for compatibility diagnostics;
6. insert a user-saved Quick phrase into the currently focused editable field after the user taps that phrase.

Structural scrolling and diagnostics do not read displayed screen text. Quick phrase insertion temporarily reads only the focused editable field so existing text and cursor selection can be preserved. That field content is not logged, persisted, analyzed or transmitted.

## Data collected

None. ScrollDock has no server connection or telemetry.

## Network access

The application does not request the Android `INTERNET` permission and contains no analytics, advertising, crash-reporting or telemetry SDK.

## Local settings

The app stores selected package identifiers, overlay position, control size, opacity, page distance, timing, disclosure consent, pause state, collapse state, temporary hiding state and up to five Quick phrases. The latest compatibility diagnostic snapshot stores the selected package, structural class and view identifier, bounds, available action identifiers, chosen method, keyboard bounds, a hashed target signature, the last action result, failure code and timestamp. It does not store displayed screen text or input-field contents.

All current settings and diagnostics use the same local settings store, Android cloud backup is disabled, and **Clear local settings** removes that store.

## Permissions not requested

ScrollDock does not request screen capture, microphone, camera, location, contacts, notifications, clipboard, broad storage or broad installed-app visibility permissions.
