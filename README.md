# ScrollDock

ScrollDock is an offline Android accessibility app that adds compact movable navigation and one-tap Quick prompt controls to selected apps.

## Version

Current app version: **0.3.4**

GitHub Actions reads `versionName` from `app/build.gradle.kts` and publishes the matching file name, such as `ScrollDock-v0.3.4.apk`.

## First release feature set

### Compatibility diagnostics

The diagnostics screen shows structural metadata for the current scroll target:

- foreground package
- requested scroll direction
- selected node class and view ID
- node bounds
- available scroll actions
- configured scroll method
- keyboard bounds
- hashed target signature
- last action result, failure code and failure reason

A scroll is marked successful only after the existing command engine confirms movement for the selected target. The report excludes screen text, input contents and clipboard contents.

### AI Apps

ChatGPT, Claude, Gemini, DeepSeek and Kimi are enabled by default once. Each recommended app can be removed. Other installed apps are managed from a separate picker.

Verified Android package identifiers:

- ChatGPT: `com.openai.chatgpt`
- Claude: `com.anthropic.claude`
- Gemini: `com.google.android.apps.bard`
- DeepSeek: `com.deepseek.chat`
- Kimi: `com.moonshot.kimichat`

### Quick prompts

Store up to five local prompts. They stay hidden until the compact **P** button below **Super Down** is tapped.

- maximum label length: 16 characters
- maximum phrase length: 5,000 characters
- inserted at the focused field's cursor or selection
- existing text outside the selection is preserved
- never presses Send
- blocked in password fields
- no cloud sync or clipboard history

### Quick Settings tile

The tile supports four states:

- **ScrollDock on**: tap to pause
- **ScrollDock paused**: tap to resume
- **ScrollDock hidden**: tap to restore controls
- **Setup ScrollDock**: opens Accessibility settings

On Android 13 and later, ScrollDock can request that the tile be added. Earlier versions require adding it manually from the Quick Settings editor.

## Controls

The permanent floating stack is exactly:

**Move → Super Up → Super Down → Prompt**

- **Move**: drag the small handle. Long-press it for target and visibility settings.
- **Super Up**: tap once to keep scrolling toward the top. Tap again to stop.
- **Super Down**: tap once to keep scrolling toward the bottom. Tap again to stop.
- **P**: opens the compact prompt list. Long-press to edit prompts.
- The dashboard switch turns floating controls on or off directly in the app.

## Reliable navigation

ScrollDock correlates scroll events with the selected window and structural target instead of treating every screen scroll as command success. It distinguishes confirmed movement, confirmed edges, unavailable targets and safety timeouts.

Each app can use one of three methods:

- **Automatic target**: chooses the strongest visible vertical scroll container.
- **Locked target**: remembers a user-selected structural path and identifiers.
- **Gesture only**: skips semantic accessibility scrolling and performs a configured swipe.

## Privacy

- No `INTERNET` permission.
- No analytics, ads, accounts, screen capture, broad storage or content transmission.
- Structural scrolling and diagnostics do not read screen text.
- When the user taps a Quick prompt, ScrollDock temporarily reads only the focused editable field so it can insert the prompt without deleting existing text. The field content is not logged, persisted or transmitted.
- Quick prompts, selected package identifiers and the latest structural diagnostic snapshot are stored locally in the same clearable settings store.
- Android cloud backup is disabled.

See [PRIVACY.md](PRIVACY.md) for the complete privacy statement.

## Install and enable from an APK

Android can block sensitive settings for apps installed from an APK.

1. Install the APK and open ScrollDock.
2. Accept the accessibility disclosure.
3. If Android displays **Restricted setting**, tap **Close**.
4. Open **Settings > Apps > ScrollDock** or hold the app icon and tap **App info**.
5. Tap the three-dot menu.
6. Select **Allow restricted settings** and confirm.
7. Open **Settings > Accessibility > Installed apps > ScrollDock**.
8. Turn the service on and tap **Allow**.

Only allow restricted settings when you trust the APK source. ScrollDock cannot enable or bypass this Android protection itself.

## APK updates and signing

Android accepts an update only when the package name is unchanged, the version is compatible, and both APKs use the same signing certificate. A fresh debug key can require uninstalling the old app first.

The workflow supports a persistent release key through GitHub Actions secrets. Without those secrets, CI produces a debug APK and in-place updates are not guaranteed. See [docs/SIGNING.md](docs/SIGNING.md).

## Build

Requirements:

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools 36.0.0
- Gradle 9.5.0

```bash
gradle testDebugUnitTest assembleDebug
```

The local debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions publishes a version-matched APK and certificate report.
