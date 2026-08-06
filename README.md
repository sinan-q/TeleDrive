# TeleDrive

An Android app that uses Telegram as encrypted cloud storage, powered by TDLib.

## Features

- **Telegram Cloud Storage**: Store files using your Saved Messages or custom Telegram channels/chats.
- **Client-Side Encryption**: Encrypt files locally with AES using a Master Password before uploading.
- **Biometric Lock**: Optional fingerprint/face unlock using Android Biometrics.
- **Folder Navigation**: Organize files into folders with a local metadata index.
- **Background Backup & Sync**: Multi-file queued uploads/downloads and replacement handling.
- **Material 3 UI**: Jetpack Compose interface with dynamic color and edge-to-edge support.

## Tech Stack

- **Language**: Kotlin (JDK 21)
- **UI**: Jetpack Compose & Material 3
- **Telegram Backend**: TDLib (`org.drinkless.tdlib`)
- **DI**: Hilt + KSP
- **Database**: Room
- **Security**: EncryptedSharedPreferences (`androidx.security:security-crypto`) & Biometric API
- **Navigation**: Jetpack Navigation Compose with `kotlinx.serialization`

## Setup

1. Get your Telegram API credentials from [my.telegram.org](https://my.telegram.org).
2. Create or update `local.properties` in the project root:

```properties
TELEGRAM_API_ID=1234567
TELEGRAM_API_HASH=your_api_hash_here
```

> **Note**: Do not commit `local.properties` to version control.

## Building

Build the debug APK:

```bash
./gradlew assembleDebug
```

Build the release APK (with R8/ProGuard rules applied):

```bash
./gradlew assembleRelease
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```text
com.sinxn.teledrive/
├── data/
│   ├── backup/       # Worker and sync queue management
│   ├── crypto/       # AES encryption and key storage
│   ├── local/        # Room DB schema and EncryptedSharedPreferences
│   ├── model/        # Data classes and states
│   ├── provider/     # Telegram chat/channel target resolution
│   ├── repository/   # Data layer abstraction
│   └── telegram/     # TDLib client manager and auth flow
├── di/               # Hilt modules
├── navigation/       # Type-safe routes & NavHost
├── ui/
│   ├── screens/      # Compose screen layouts
│   └── theme/        # Material Design 3 theme
└── utils/            # Shared helper functions
```

## Security

Files encrypted with your Master Password are encrypted client-side prior to transmission. Telegram servers only store encrypted payload chunks when encryption is enabled.

## License

MIT
