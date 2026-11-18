# Changelog

All notable changes to `rishadblack/sylloo-app-native` are documented in this file.

## 0.3.0 - 2026-11-18

### Fixed

- The cloud window no longer shows the business's sign-in page after the page's own Sign out (as the desktop app):
  `/member/login` and `/login` end the session (`ENDED`, a new ticket) even though `/member/login` lies under
  `/member`; `/member/theme/logout`, `/member/logout` and `/logout` are the new decision `SIGN_OUT` (the `ended`
  callback gets the text `signed_out`, `WindowSession::SIGNED_OUT`), so the app signs the person out instead of quietly
  signing them in again. Each page the window starts to load is checked again in `onPageStarted`, which also sees a
  form sent with POST and the page its answer leads to (`shouldOverrideUrlLoading` never sees a POST).
  `WindowSession::decide()` answers `sign_out` / `ended` the same way; new cases in `tests/Fixtures/window-cases.json`.
- The page's own Sign out on the phone really signs out: Android shows `onPageStarted` the Sign out POST only after the
  business answered it, and its redirect to the sign-in page (on Practice the Live sign-in address) came through as
  `ENDED` with that address, so the app signed in again quietly. `shouldInterceptRequest` now sees the Sign out before
  it is sent, and for 15 s its answer counts as `SIGN_OUT` (`WindowFilter.afterSignOut`, JVM test).

### Added

- Call recordings (crm-call-recording-plan.md CR3), off unless the app turns on `features.calls_recording` (env
  `SYLLOO_NATIVE_CALLS_RECORDING`); it adds no Android permission. `Calls.Folder` (Android's folder chooser for the
  phone's own call recorder's folder, read grant of that folder only, kept; or `forget`; event `CallsFolderAnswered`),
  `Calls.FolderStatus`, `Calls.Recording` (one saved call's file found by `RecordingRules`, copied app-private, AAC
  re-wrapped as `.mp4` with MediaMuxer, with size and SHA-256) and `Calls.RecordingDrop`. PHP `CallRecordings`,
  `Data\CallRecordingFile`. JVM tests `RecordingRulesTest` (8); Pest `CallRecordingTest`.

- Work calls (crm-call-recording-plan.md CR2), each off unless the app turns it on: `Calls.Recent` (`features.calls_recent`,
  env `SYLLOO_NATIVE_CALLS_RECENT`) reads the call list after a row id and hands back only the calls whose number ends
  in one of the app's compare keys (the last 9 digits of the numbers of the employee's own records): a private call
  never reaches PHP; it reads only id, number, type, date and duration, never names. `Calls.Ended`
  (`features.calls_ended`, env `SYLLOO_NATIVE_CALLS_ENDED`) wakes the app's send command a few seconds after a call ends
  (a PHONE_STATE receiver, and a WorkManager watch of the call list as the second way). `Calls.Permission` (event
  `CallsPermissionAnswered`), `Calls.Status`. PHP `Calls` (also `Calls::key()`, the same compare key as Kotlin),
  `Data\PhoneCall`, `Data\CallsRead`. The build hook adds READ_CALL_LOG (calls_recent) and READ_PHONE_STATE plus the
  receiver (calls_ended) only when on; READ_PHONE_STATE shared with phone SMS is written once. JVM tests
  `CallRulesTest` (8); Pest `CallsTest`. Recording calls is not here (CR3).
- Push: a data message's `open`, `open_id` and `actions` (at most two buttons, labelled only from the app's own map
  `Push.Labels` / `Push::labels(['crm.call' => 'Call'])`; an unknown label shows no button). A tap keeps
  `{url, open, open_id, action}` for `Push.Opened` / `Push::tapped()` and sends `NoticeOpened` as well as `PushOpened`.
  JVM tests `PushRulesTest` (4).
- The CameraX libraries the photo, QR and barcode screens use (camera-core, camera-camera2, camera-lifecycle,
  camera-view 1.4.1) are declared in `nativephp.json`; before they only came from NativePHP's app template.

- Timed notices (crm-employee-app-plan.md §4.4), off unless the app turns on `features.notice_schedule` (env
  `SYLLOO_NATIVE_NOTICE_SCHEDULE`): `Notice.Schedule` (a notice shown by an exact alarm at its minute, also offline,
  with the app closed and after a restart or an app update; the app's texts; buttons Call (opens the app with the
  action `call`, no number kept on the phone) and Later (the app's choices: minutes or a fixed time)), `Notice.Cancel`
  (one id or a whole group), `Notice.Opened` (what the tapped notice opens, once; also the event `NoticeOpened`). PHP
  `Notices`. The build hook adds USE_EXACT_ALARM (SCHEDULE_EXACT_ALARM up to Android 12L), RECEIVE_BOOT_COMPLETED,
  the alarm and restart receivers and the two small screens only when on; a permission two switched-on jobs share is
  written once (all blocks are taken out first, then added). JVM tests `NoticeRulesTest` (6); Pest `NoticesTest`.

- Phone SMS (phone-messaging-plan.md SM2), off unless the app turns on `features.sms_send` (env
  `SYLLOO_NATIVE_SMS_SEND`): `Sms.Send` (one SMS from a SIM slot through that subscription's SmsManager, multipart for
  a long or Bangla text, sent and delivered answers, failure reasons no_signal, no_service, no_balance (a guess from
  the network's cause), wrong_number, radio_off, blocked, permission, other), `Sms.Sims` (slot and operator; no SIM
  number), `Sms.Permission`; the background job `Messages.Start`, `Messages.Work`, `Messages.Stop`,
  `Messages.Status` (the whole loop in Kotlin, with the app closed: get, claim, send, report, pace, daily limit, pause,
  five failures in a row, Android's 30 SMS in 30 minutes; a foreground notice while it works; woken by an exact alarm,
  the push `{wake: messages}` and every return to the app). PHP `Sms`, `Messages` (the base's
  `Contracts\MessagingPhone`, bound by the provider), events `SmsAnswered`, `SmsPermissionAnswered`, native faces
  `Faces\MessagesAccept`, `Faces\Messages` (routes only with the switch on), texts `pieces.messages_notice_*`. The
  build hook adds SEND_SMS, READ_PHONE_STATE (only this job adds it), the exact alarm, FOREGROUND_SERVICE(_DATA_SYNC),
  the receivers and WorkManager's dataSync service type only when on. JVM tests `MessagesRulesTest` (12),
  `MessagesLoopTest` (15); Pest `PhoneSmsTest`.
- `Scan.Answer` / `Scanner::answer($id, $text, $kind)`: one line (ok green, warn amber, error red) under the aim box of
  the open camera screen for about 2 s, read out by TalkBack (remote-scanner.md §5). Off-switch rules as `Scan.Camera`.
  JVM tests `ScanAnswerTest` (3).

### Fixed

- The QR screen's Cancel and the photo screen's buttons sat under the navigation bar on Android 15 and newer (the
  screen is drawn edge to edge, and they used fixed pixel margins): they now keep clear of the system bars and the
  display cutout, as the barcode screen does.
- `Faces\NeedsReadyDevice::deviceReady()` handed a screen over with `replace()` from `mount()`, before NativePHP set up
  the screen's callback list, so the last frame failed ("$nativeCallbacks must not be accessed before
  initialization", logged). It now makes the list first.
- The native text, code and PIN fields never showed a value set by PHP (a scanned code, the default device name, a
  PIN cleared after a wrong try): `native:model.debounce.300ms="fields.{{ $field->name }}"` gave a `:value` that read
  the key `fields.{{ $field->name }}` as written, so the field stayed empty while PHP held the value. `partials/field`
  now binds `:value="$fields[$field->name]"` itself (same debounced sync back).
- Activate's "Scan QR code" (owner, 2026-11-18: "qr code scan but not working on activation"): the answer is heard
  without a `when` filter (the id is checked in `ScreenFace::qrAnswered`, which writes each answer to a debug build's
  edge-nav log). The office's activation QR now fills the code and activates at once (the person chose to scan it);
  16 digits alone only fill the field; any other QR says it is not an activation code; a refused camera or a camera
  that did not start says so (`pieces.scan_qr_denied`, `scan_qr_failed`) instead of "Scanning comes in a later
  version".

- The camera screen's "Continuous scan" switch had no visible track or thumb (on and off looked the same): it is now
  drawn by the screen (teal track on, grey off, white thumb; TalkBack: a switch, On / Off), and the grey "Cancel"
  button is a dark pill reading "Done" in the app's language (`pieces.scan_on`, `scan_off`, `scan_done`). Two more
  `ScanModeTest` tests.

### Changed

- `Scan.Camera` reads faster and feels smoother (owner, 2026-11-18). The old screen read the whole 1280 × 720 frame
  with TRY_HARDER, as the sensor lies (sideways), then turned, each also inverted: up to four slow passes per frame,
  and a code held level on an upright phone always needed the second one (PC: 67–96 ms to read a code, 118 ms per
  empty frame). Now `BarcodeReader` cuts the aim box out of the visible part of the frame (CameraX view port), turned
  upright, and reads it quickly, plus one extra try that changes per frame (the box turned a quarter, light on dark,
  the whole visible picture): PC 0.4–0.6 ms to read a level code, about 8 ms per empty frame. The frame buffer is
  reused (no 1 MB allocation per frame).
- Many in a row: an item held under the camera now counts once, however long (before: again every 1.5 s). The same
  code counts again after it was out of sight for 0.8 s; a different code counts at once (`ScanRepeatGuard`).
- The camera screen: a line sweeps the aim box while it looks; a green flash with the beep and buzz per read; tap to
  focus; a torch button when the phone has a light; opens at 1.5× zoom with focus on the box; a quick fade instead of
  the slide on open and close; the camera service starts while the permission is asked. Its times (open → first
  frame, open → first read, read time per frame) go to the log under `SyllooScan` when it closes.
- Continuous scan on / off (owner, 2026-11-18): a switch "Continuous scan" / "একটানা স্ক্যান" / "Jatkuva skannaus"
  next to the torch on the camera screen. On: many in a row, as before; off: the screen closes after the first code
  and sends only that one (no second code can slip in before it closes). The phone keeps the person's choice per app
  (SharedPreferences `sylloo_app_native_scan`), and it wins next time: `Scanner::camera()`'s `continuous` is now the
  first-time default. New `switchable` argument (default true; false hides the switch and the app alone decides). The
  call also sends `switchable` and `continuous_label` (the switch's text in the app's language, new lang key
  `pieces.scan_continuous`). `ScanReceived` and `ScanCameraClosed` have a new optional `continuous` (null when not
  sent), so old listeners keep working. JVM tests `ScanModeTest` (5).
- The pure parts moved to `resources/android/BarcodeDecoder.kt` (`BarcodeDecoder`, `BarcodeReader`, `AimBox`,
  `ScanRepeatGuard`, `ScanStats`, `ScanMode`); JVM tests `BarcodeDecoderTest` (15) and the new `BarcodeReaderTest` (10).

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
