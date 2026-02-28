# The Vault

Secure, offline-first vault for storing passwords, notes, contacts, and files with strong local encryption and manual backups.

## Version
Current app version: **1.0.0**

## Features
- Multiple vaults with individual PINs
- Decoy PIN support
- Fingerprint unlock (configurable per vault or all vaults)
- Inactivity lock and auto-wipe (failed attempts)
- Intruder capture on failed attempts
- File vault (images, documents, media)
- Manual backups:
  - Normal backup (single vault)
  - Extreme backup (single vault with device‑locked protection)
  - Full backup (entire app data)
  - Full Extreme backup (entire app data + encrypted zip)
- Restore with validation and progress tracking
- Backup history and vault health info
- Clipboard auto‑clear for sensitive data

## Requirements
- Android Studio Hedgehog+ or Gradle CLI
- Android SDK (via Android Studio)
- JDK 17

## Project Structure (high level)
```
TV/
  app/                # Android app module
  gradle/             # Gradle wrapper files
  build.gradle.kts    # Top-level Gradle config
  settings.gradle.kts
  gradle.properties
```

## Build
Debug APK:
```bash
./gradlew assembleDebug
```

Release APK (signed):
1. Create a keystore (once):
   ```bash
   keytool -genkeypair -v -keystore release-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias vault_release
   ```
2. Copy `keystore.properties.example` to `keystore.properties` and fill in your values.
3. Build:
   ```bash
   ./gradlew assembleRelease
   ```

Outputs:
- Debug: `app/build/outputs/apk/debug/`
- Release: `app/build/outputs/apk/release/`

## Configuration
- `keystore.properties` and `*.jks` are intentionally ignored by git.
- Update the GitHub button URL in-app when the repo URL is final.

## GitHub Setup
If this is a new repo:
```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/S0L0-R00T-DEV/The-Vault.git
git push -u origin main
```

## Security Notes
- The app stores data locally and encrypts vault content.
- Backups are designed to be portable but protected by keys/credentials.
- Losing keys or device‑locked backups is irreversible by design.

## License
Add a LICENSE file before publishing (recommended).
