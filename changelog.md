# Changelog

All notable changes to `rishadblack/sylloo-app-native` are documented in this file.

## 0.2.0 - 2026-11-18

### Added

- Switched jobs for the Android till, each off unless the app turns it on (config `sylloo-app-native.features.print`,
  `features.scan_keys`; env `SYLLOO_NATIVE_PRINT`, `SYLLOO_NATIVE_SCAN_KEYS`). Off: the PHP class answers
  `not_set_up`, the Kotlin job refuses, and the build has none of its permissions.
- `Print.Receipt`, `Print.Printers`, `Print.Drawer` behind `Printer` (`receipt()`, `picture()`, `drawer()`,
  `printers()`): the receipt page drawn off the screen (no network, no scripts) as a 1-bit dithered ESC/POS raster
  picture for 58 / 80 mm paper, over Bluetooth (paired), USB or the network; the cash drawer kick. Plain outcomes
  (`Data\PrintOutcome`), the final one as the event `PrintAnswered`. `Data\PrinterTarget` reads and writes the printer
  addresses (`bt:…`, `usb:vvvv:pppp`, `net:host[:port]`).
- `Scan.Keys` behind `ScanKeys` (`on()`, `off()`): a scanner that types while no field has the cursor; each code as
  the event `ScanReceived` (config `sylloo-app-native.scan_keys`).
- `Scan.Camera` behind `Scanner::camera(string $id, bool $continuous = true)`, off unless `features.scan_camera`
  (env `SYLLOO_NATIVE_SCAN_CAMERA`): the package's own barcode screen (CameraX + ZXing core, like `Qr.Scan`) reads
  EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39 and QR, also a frame lying on its side. Many in a row: stays open, a
  beep and a buzz per read, the same code again within 1.5 s ignored, a Done button. Each code as `ScanReceived`
  (`source` camera, new `format` and `id`); the close as the new event `ScanCameraClosed` (`status`, `id`, `count`).
  On, the build adds only the optional camera feature lines (the CAMERA permission is already declared for `Qr.Scan`
  and `Camera.Take`). JVM tests decode generated barcode pictures (`BarcodeDecoderTest`).
- The build hook writes `res/values/sylloo_features.xml` and adds the print job's permissions (`BLUETOOTH_CONNECT`;
  `BLUETOOTH` up to Android 11; optional Bluetooth / USB host features) to the generated manifest only when it is on.
- App backup off for every app (plan §7.10): the build hook sets `android:allowBackup="false"` on the generated
  manifest and names two rule files it writes (`res/xml/sylloo_backup_rules.xml`,
  `res/xml/sylloo_data_extraction_rules.xml`) that exclude every domain from the cloud backup and from the
  device-to-device transfer (Android 12+) (`Support\BackupManifest`).
- JVM tests for the raster encoder, the drawer bytes, the printer addresses and the scanner's grouping.
- The fingerprint shortcut (`Faces\Fingerprint::addTo()`, `Contracts\FingerprintChoices`): "Use fingerprint" on the
  lock screen (`lock.below_pad`) for a person who chose it, and the on / off choice in PIN settings
  (`pin_settings.bottom`), both through `Lock.Prompt`; the PIN stays the only way after a restart of the app.
- The cloud window opens calls, mail, messages, maps and WhatsApp links in the phone's own apps
  (`WindowFilter.opensPhoneApp`, `WindowExternal`), instead of handing them to PHP as a leave.

### Fixed

- The build hook no longer adds a switched job's manifest block a second time when NativePHP's next build left the old
  block glued to the end of a line (`FeatureManifest::remove()` also matches a block that does not start a line).
- `Scan.Keys`: a scanner's Enter that comes after the quiet gap already ended its code (a slow Bluetooth link) is
  still kept from the app, within 1 s (`ScanKeyGrouper::LATE_ENTER_MS`); before, the stray Enter reached the screen
  and the next code was lost. An Enter that comes before the quiet timer ran now ends the code instead of dropping it.

### Changed

- The employee cloud window keeps its session alive too: `WindowSession::EMPLOYEE_KEEP_ALIVE`
  (`/employee/app/keep-alive`; the ERP answers 401 `device_disconnected` when the device or the sign-in ended).

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
