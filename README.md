# Makarena Android Runtime (WhiteWeb)

Hardware-independent runtime instrumentation & stealth browser for Android.

## Features
- Hardware Volume Key Interceptor (2x Volume Down = Toggle AI Assistant, 2x Volume Up = Panic Clear)
- Stealth IPC bridge over WebView prompt dialogs without DOM footprint
- Focus preservation & visibility spoofing
- Real-time multimodal vision solver & sequence parser
- Zero external tracking or telemetry

## Architecture
- `app/src/main/java/com/makarena/browser/MainActivity.kt` — Core WebView host, stealth bridge and hardware key interceptor
- `app/src/main/java/com/makarena/browser/SplashActivity.kt` — Minimalist monochrome launch animation
- `app/src/main/java/com/makarena/browser/AIService.kt` — Multimodal vision and heuristic fallback solver
- `app/src/main/assets/ai_injector.js` — Target DOM mutation monitor and invisible hint projector
- `app/src/main/assets/testportal_bypass.js` — Event suppression and environment parity

## Building
Run with Gradle 8.7+ or open in Android Studio Hedgehog / Iguana / Jellyfish / Koala:
```bash
./gradlew assembleDebug
```
The compiled package will be located at `app/build/outputs/apk/debug/app-debug.apk`.
