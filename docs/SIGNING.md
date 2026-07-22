# Persistent APK signing

Android only installs an APK over an existing app when both APKs use the same package name and compatible signing certificate. A higher `versionCode` alone is not enough.

## Root cause of repeated uninstall requirements

GitHub Actions runners are temporary. A normal debug build uses `~/.android/debug.keystore`; when that file does not exist, Android build tools generate it. A fresh runner therefore produces a different debug certificate. Android rejects the next APK as an update from a different signer.

This behavior is part of Android's app-update security model. It is not specific to Android 16.

## Configure a persistent release key

Generate the key once on a trusted offline machine:

```bash
keytool -genkeypair \
  -keystore scrolldock-release.jks \
  -alias scrolldock \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Back up the keystore and passwords securely. Losing the key prevents future APKs from updating existing installations.

Convert the keystore to one base64 line.

Linux or macOS:

```bash
base64 < scrolldock-release.jks | tr -d '\n'
```

PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("scrolldock-release.jks"))
```

In the GitHub repository, open **Settings > Secrets and variables > Actions** and create these repository secrets:

| Secret | Value |
| --- | --- |
| `SCROLLDOCK_KEYSTORE_BASE64` | Base64-encoded keystore |
| `SCROLLDOCK_KEYSTORE_PASSWORD` | Keystore password |
| `SCROLLDOCK_KEY_ALIAS` | Key alias, such as `scrolldock` |
| `SCROLLDOCK_KEY_PASSWORD` | Private-key password |

When all four secrets exist, GitHub Actions builds the release APK with that key. Otherwise it builds a temporary debug APK and records a warning.

## One-time migration

The first APK signed by the persistent key cannot update an older APK signed by a temporary debug key.

1. Note or export any settings that must be preserved.
2. Uninstall the old ScrollDock build once.
3. Install the first persistently signed APK.
4. Allow restricted settings and re-enable the accessibility service.
5. Install later versions directly over the existing app.

## Verification

Every workflow run publishes a `ScrollDock-signing-v<version>` artifact containing:

- signing mode
- signer certificate distinguished name
- SHA-256 certificate digest

The SHA-256 certificate digest must remain identical between updateable releases. Do not publish the private keystore or passwords in source control, workflow files, logs, releases, or issue attachments.
