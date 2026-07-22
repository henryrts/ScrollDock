# ScrollDock product roadmap

Priorities are ordered by user impact, reliability, and fit with the offline accessibility scope.

## P0: Distribution and trust

### Stable signed releases and update verification

- Require a persistent signing key for public release artifacts.
- Publish the certificate SHA-256 digest with every APK.
- Show a clear warning when CI falls back to temporary debug signing.
- Add a release checklist that verifies package name, `versionCode`, version filename, and certificate continuity.

Impact: removes repeated uninstall/setup cycles and creates a trustworthy update path.

## P0: Compatibility diagnostics

Add a local diagnostics screen that reports, without collecting text:

- current foreground package
- selected scroll method and target signature
- available accessibility scroll actions
- semantic or gesture method used
- movement, edge, failure, or timeout result
- keyboard and overlay bounds

Allow copying a sanitized report.

Impact: makes app-specific failures reproducible and sharply reduces blind debugging.

## P1: Follow latest

For chat apps:

- remain at the newest content only while already near the bottom
- pause when the user scrolls upward
- show a compact Return to latest control
- never force the user downward while reading older content

Impact: improves live-response reading without sacrificing control.

## P1: Reading-position bookmarks

- Save one automatic last-reading position per selected app.
- Add an optional manual bookmark.
- Restore using structural anchors with safe page-distance fallback.
- Never store visible text.

Impact: helps users resume long chats, documents, and feeds after switching apps.

## P1: Quick Settings tile

Provide a tile for:

- enable or disable the overlay
- pause for ten minutes
- restore immediately

Impact: reduces repeated navigation to app settings and improves one-handed use.

## P2: Adaptive compact controls

- Switch between full, two-button, and handle-only layouts.
- Collapse automatically while the keyboard is visible.
- Restore after the keyboard closes.
- Support left-edge and right-edge presets.

Impact: reduces obstruction while keeping navigation reachable.

## Deferred

Do not prioritize accounts, cloud sync, advertising, subscriptions, social features, or content analytics. They conflict with the narrow offline accessibility utility and do not improve core navigation reliability.
