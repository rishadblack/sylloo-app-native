# Changelog

All notable changes to `rishadblack/sylloo-app-native` are documented in this file.

## 0.1.0 - 2026-11-17

First release: the phone package of Sylloo App (NativePHP for Mobile 4, Android).

### Added

- Native faces (`nativephp/mobile-ui`) of the shared screens of `rishadblack/sylloo-app-base`: Activate (with Scan QR
  code), Sign in, Create PIN, PIN pad, Lock, the state screens, Businesses, PIN and lock settings, About, Language. Each
  face only drives the base's screen logic; `ReadyDevice` / `NeedsReadyDevice` ask the base's `FirstScreen`.
- Kotlin jobs, each behind its own PHP class: `Clock.Elapsed`, `Secret.Set/Get/Delete` (Android Keystore),
  `Lock.Prompt` (biometric), `Location.Fix`, `Camera.Take` (selfie with outline or back photo, no gallery, made small),
  `Qr.Scan` (CameraX + ZXing, offline), `Push.*` (FCM without the Google gradle plugin), `Sync.Schedule` (WorkManager),
  `Update.Install` (signed APK, SHA-256), the phone's name.
- The cloud window element `<native:sylloo-window>`: the business pages between native bars, without the app's bridge;
  filter from the base's window rules, uploads, downloads, print, back, keep-alive.
- Texts in English, Bangla and Finnish; Boost guideline.
