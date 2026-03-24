# Shul Display Android

Android app for displaying shul-display content on Android TV and tablets.

## Features

- Setup screen for entering display slug
- Full-screen WebView loading from shul-display.vercel.app
- Auto-start on device boot
- Remote command polling (reload, update slug)
- Device registration with the server

## Building

```bash
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.
