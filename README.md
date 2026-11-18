# rishadblack/sylloo-app-native

Sylloo App's phone package: a NativePHP Mobile 4 plugin (Android). It holds the native faces of the shared
Sylloo screens, the small Kotlin jobs (Clock, Secret, Lock, Location, Camera, Qr, Push, Sync, Update, Window, and
the till's Print and Scan.Keys, off unless switched on; each behind its own PHP class in `src/`) and the cloud window element `<native:sylloo-window>`. Free NativePHP
plugins are used where one exists (Browser, Camera plugin for plain photos, core Device for the phone's name);
these jobs exist because NativePHP's own are paid or missing.

## Install

```bash
composer require rishadblack/sylloo-app-native
php artisan native:plugin:register rishadblack/sylloo-app-native
php artisan native:plugin:list
```

It needs `rishadblack/sylloo-app-base` (installed with it) and NativePHP for Mobile 4.8 with `nativephp/mobile-ui`.

## Clock

```php
use Rishadblack\SyllooAppNative\Facades\Clock;

$reading = Clock::elapsed(); // null off the phone
$reading?->elapsedMs;        // SystemClock.elapsedRealtime()
$reading?->bootCount;        // Settings.Global.BOOT_COUNT, null when unknown
```

## Till jobs (off unless the app turns them on)

Jobs the Android till needs and other apps do not. Each is off by default; turn it on in the published
`config/sylloo-app-native.php` (or `.env`) and build again:

```php
'features' => [
    'print' => true,      // Print.Receipt, Print.Printers, Print.Drawer   (env SYLLOO_NATIVE_PRINT)
    'scan_keys' => true,  // Scan.Keys                                     (env SYLLOO_NATIVE_SCAN_KEYS)
    'scan_camera' => true, // Scan.Camera, Scan.Answer                     (env SYLLOO_NATIVE_SCAN_CAMERA)
    'sms_send' => true,   // Sms.*, Messages.* (phone SMS)                 (env SYLLOO_NATIVE_SMS_SEND)
    'notice_schedule' => true, // Notice.* (timed notices)                 (env SYLLOO_NATIVE_NOTICE_SCHEDULE)
    'calls_recent' => true, // Calls.Recent, Calls.Permission, Calls.Status (env SYLLOO_NATIVE_CALLS_RECENT)
    'calls_ended' => true,  // Calls.Ended (wake after a call)             (env SYLLOO_NATIVE_CALLS_ENDED)
    'calls_recording' => true, // Calls.Folder, Calls.Recording, ...       (env SYLLOO_NATIVE_CALLS_RECORDING)
],
```

While a job is off its PHP class answers `not_set_up` without asking the phone, the Kotlin job refuses too, and the
build has none of its permissions. The build hook adds a switched-on job's permissions to the generated
`AndroidManifest.xml` in a marked block (print: `BLUETOOTH_CONNECT`, and `BLUETOOTH` up to Android 11, plus optional
Bluetooth / USB host features; the typing scanner needs none; the camera scanner only optional camera features, as
CAMERA is already declared for `Qr.Scan`; phone SMS: `SEND_SMS`, `READ_PHONE_STATE`, the exact alarm, the
foreground notice and, inside `<application>`, its receivers) and takes the block out when the job is off again. They are not
in `nativephp.json`, because NativePHP merges a plugin's permissions into every build with no switch.

### Receipt printing

```php
use Rishadblack\SyllooAppNative\Data\PrinterTarget;
use Rishadblack\SyllooAppNative\Events\PrintAnswered;
use Rishadblack\SyllooAppNative\Printer;

$printer = app(Printer::class);

$printer->printers();           // ['status' => 'listed', 'printers' => [['kind' => 'bluetooth', 'name' => …, 'target' => 'bt:…'], …]]
$printer->printers(ask: true);  // no Bluetooth permission yet: Android asks; the answer is PrintAnswered (job "permission")

$outcome = $printer->receipt($html, 80, 'bt:66:22:11:33:44:55', id: 'sale-7', copies: 1, cut: true, openDrawer: false);
$outcome = $printer->picture($pngBytes, 58, PrinterTarget::network('192.168.1.50'));
$outcome = $printer->drawer('usb:0416:5011');

$outcome->status; // started (the final outcome follows as PrintAnswered) | not_set_up | no_printer | failed

#[On(PrintAnswered::class)]
public function printed(string $status, ?string $id = null, string $job = 'receipt', ?string $reason = null): void
{
    // status printed | opened | no_printer | not_permitted | failed (reason: not_paired, not_reachable, usb, render, …)
}
```

- The receipt is the same self-contained HTML as on the PC (pictures as `data:` addresses). The phone lays it out off
  the screen in a web view (JavaScript off, no network, no files) at the paper's width (58 mm = 384 dots, 80 mm = 576
  dots; laid out at 182 / 272 CSS px, as the PC prints), makes it a 1-bit picture with dithering and sends it as
  ESC/POS raster (`GS v 0`), then feeds and cuts. Bangla, logos and barcodes print as drawn.
- Printers: `bt:AA:BB:CC:DD:EE:FF` (paired, classic SPP), `usb:vvvv:pppp` (a printer-class USB device; Android asks
  the person once), `net:host[:port]` (port 9100). Keep the text as the printer setting; `PrinterTarget::parse()`.
- The drawer: `ESC p 0 25 250` (pin 2, 50 ms) through the same connection, or `openDrawer: true` after a receipt.
- Nothing throws: every answer is a `Data\PrintOutcome` (`status`, `reason`, `id`, `job`).

### A scanner that types

```php
use Rishadblack\SyllooAppNative\Events\ScanReceived;
use Rishadblack\SyllooAppNative\ScanKeys;

app(ScanKeys::class)->on();   // 'on' | 'not_set_up' | 'failed'
app(ScanKeys::class)->off();

#[On(ScanReceived::class)]
public function scanned(string $code, string $source = 'keys'): void { /* … */ }
```

The phone watches the key strokes of its screen while no text field has the cursor, groups characters that come at
most `gap_ms` apart (config `sylloo-app-native.scan_keys`, 60 ms) and end in Enter (or `end_ms` of quiet, 150 ms), at
least `min_length` (4), and sends each group as `ScanReceived`. The scanner's Enter is kept from the screen; a person's
typing and typing into a focused field are left alone.

```php
use Rishadblack\SyllooAppNative\Events\ScanCameraClosed;
use Rishadblack\SyllooAppNative\Scanner;

app(Scanner::class)->camera('sell');                      // many in a row: 'started' | 'not_set_up' | 'failed'
app(Scanner::class)->camera('find', continuous: false);   // closes after the first read (first time; see the switch)
app(Scanner::class)->camera('find', continuous: false, switchable: false);   // always one code, no switch

#[On(ScanReceived::class)]   // code, source 'camera', format (ean_13, ean_8, upc_a, upc_e, code_128, code_39, qr_code), id, continuous
#[On(ScanCameraClosed::class)]   // status done | cancelled | denied | failed, id, count, continuous
```

The camera screen (CameraX + ZXing core, like `Qr.Scan`) asks for the camera permission the first time, sends each
code at once, and closes with Done or Back. A "Continuous scan" switch sits next to the torch, low on the screen: on,
the screen stays open for many items; off, it closes after the first code and sends only that one. The phone keeps the
person's choice (the app's SharedPreferences) and it wins next time, so `continuous` is only the first-time default;
`switchable: false` hides the switch and lets the app decide. The switch's text is `pieces.scan_continuous` (en, bn,
fi), sent with the call. `ScanReceived` and `ScanCameraClosed` carry `continuous` (null from older builds and from a
scanner that types), so existing listeners need no change. It reads only the aim box, the way the person sees it (a quick try per
frame, plus one changing extra try: the code held across, light on dark, or the whole picture), so a frame takes a few
milliseconds instead of a hundred. A moving line shows that it looks; each read gives a beep, a buzz and a green flash.
An item held under the camera counts once; the same code counts again after it was out of sight for 0.8 s (take the
item away and show it again). A tap focuses there; a torch button shows when the phone has a light; it opens at 1.5×
zoom so small codes read from a distance the camera can focus at. The times go to the log (`SyllooScan`) on close.

After each `ScanReceived` the app may put one line under the aim box for about 2 s, in green, amber or red (also
for a typing scanner's code, while the camera screen is open):

```php
app(Scanner::class)->answer($event->id, 'Added: Rice 5 kg × 2');                     // 'shown' | 'not_open' | 'not_set_up' | 'failed'
app(Scanner::class)->answer($event->id, 'No product with code 890123', Scanner::ERROR); // ok | warn | error, at most 80 characters
```

The switch draws its own track (teal on, grey off, white thumb; TalkBack reads it as a switch with On / Off) and the
close button reads Done; their words are `pieces.scan_on`, `scan_off`, `scan_done` (en, bn, fi), sent with the call.

### Phone SMS (the business's SMS from this phone's SIM)

```php
use Rishadblack\SyllooAppNative\Messages;
use Rishadblack\SyllooAppNative\Sms;

app(Sms::class)->sims();                          // ['status' => 'listed', 'sims' => [['slot' => 0, 'label' => 'SIM 1 · Robi', 'operator' => 'Robi']], …]
app(Sms::class)->send('+8801712345678', $text, slot: 0, id: 'x');   // 'started' → SmsAnswered (sent | delivered | failed + reason | no_answer)
app(Messages::class)->start($baseUrl, $deviceKey, $appVersion);     // the background job (the base's PhoneMessaging calls it)
```

The background job runs wholly in Kotlin, also with the app closed: it asks the office (`GET device/api/v1/messages`
with the device key), claims one message, sends it from the chosen SIM (in parts when long or Bangla), reports
`sent` / `failed` (and `delivered` later), waits the office's pace and goes on; it stops at the daily limit, honours a
pause, never claims while it can not send (no signal, flight mode), and stops after five failures in a row. A notice
"Sending the shop's messages" shows while it works. An exact alarm, the push `{wake: messages}` and every return to the
app wake it. PHP does not run in the background, so `Messages::start()` hands over the device key (kept in the
Keystore vault, like `Secret.*`, never in a backup), the office address and the app version; `stop()` forgets them.
Nothing of a message is kept after its report (no number or text in logs or storage). The package binds the base's
`Contracts\MessagingPhone` to `Messages`; the screens `messages_accept` and `messages` get native faces at
`/messages/accept` and `/messages` (only with the switch on).

### Timed notices (reminders shown by the phone itself)

```php
use Rishadblack\SyllooAppNative\Notices;

app(Notices::class)->schedule([
    'id' => 'crm-f-12', 'group' => 'crm', 'channel' => 'crm_reminders', 'channel_name' => 'Reminders',
    'at_ms' => $at->getTimestampMs(), 'title' => 'Call Rahim Store', 'body' => 'Show samples · 15:00',
    'open' => 'lead', 'open_id' => 'l-1',
    'call' => ['label' => 'Call'],
    'later' => ['label' => 'Later', 'options' => [['key' => '15m', 'label' => 'In 15 min', 'minutes' => 15], ['key' => 'tomorrow', 'label' => 'Tomorrow', 'at_ms' => $tomorrowMs]]],
]);                                              // 'scheduled' | 'not_set_up' | 'invalid' | 'failed'
app(Notices::class)->cancel('crm-f-12');          // or cancelGroup('crm') at sign-out
app(Notices::class)->opened();                    // ['open' => 'lead', 'open_id' => 'l-1', 'action' => 'open'|'call'] once, or null
```

Off unless `features.notice_schedule` is on. The phone keeps each notice (no phone number) and shows it at its minute
with an exact alarm (inexact when Android does not allow exact ones), also with the app closed; a restart or an app
update arms them again (one missed by at most 12 h shows at once). A tap or Call opens the app and keeps what to open
for `opened()` (event `NoticeOpened` while it runs); Later offers the app's choices and shows it again. Quiet hours and
working time are the app's rules: the phone shows each notice at the time it was given.

### Work calls (only the calls with the app's own numbers)

```php
use Rishadblack\SyllooAppNative\Calls;

$calls = app(Calls::class);
$calls->askPermission('crm-calls');              // event CallsPermissionAnswered (status, id, call_log, phone_state)
$read = $calls->recent(['711000111'], digits: 9, afterId: $cursor, sinceMs: $yesAtMs);
foreach ($read->calls as $call) { /* callId, number, key, direction in|out, startedAt(), durationSeconds */ }
$cursor = $read->lastId ?? $cursor;              // $read->more: read again
$calls->watch('my-app:send-waiting');            // run the command a few seconds after each call ends
$calls->stopWatching();
Calls::key('+880 1711-000111');                  // '711000111', as the phone makes it
```

`Calls.Recent` (`features.calls_recent`) compares each number on the phone and hands back only the calls whose last
digits are one of the keys: any other call is never read into PHP. It reads only id, number, type, date and duration
(no names). `Calls.Ended` (`features.calls_ended`) adds READ_PHONE_STATE and a receiver; without it the app reads the
calls when it opens.

### Call recordings (the phone's own recorder's file of a work call)

```php
use Rishadblack\SyllooAppNative\CallRecordings;

$recordings = app(CallRecordings::class);
$recordings->chooseFolder('crm-folder');         // Android's folder chooser; event CallsFolderAnswered (picked|cancelled|failed, name)
$recordings->status();                           // ['on' => true, 'folder' => true, 'name' => 'Recordings/Call']
$file = $recordings->recording($callId, $startedAtMs, $seconds, key: '711000111', maxMinutes: 60);
if ($file->found()) { /* path, extension mp4|mp3|wav|ogg, size, sha256: send it, then */ $recordings->drop($callId); }
$file->final();                                  // too_long, no_call, unsupported, ambiguous: do not ask again
$recordings->forgetFolder();                     // let the folder go and remove the copies not sent
```

The package never records a call. `features.calls_recording` needs no Android permission: the person picks the folder
their phone's call recorder writes to (Android's folder chooser gives read access to that folder only, kept across
restarts). `Calls.Recording` finds the call's file by the rules of `RecordingRules` (JVM-tested): the time in the file
name within 90 seconds of the call's start (the name is local time), else a file closed at the call's end; a name with
another long number, or two files that could be it, is never taken. The copy is app-private: AAC (m4a, mp4, aac, 3gp)
is re-wrapped as `.mp4` with MediaMuxer (the sound is not re-encoded), mp3 / wav / ogg are copied, AMR is
`unsupported`. The person's own files are never changed.

### Push buttons

`Push::labels(['crm.call' => 'Call'])` gives the texts of the buttons a push may ask for (`actions[].label` is a key of
this map; any other is left out). `Push::tapped()` answers `{url, open, open_id, action}` of a tapped push once; the
event `NoticeOpened` comes too while the app runs.

## What the manifest adds to the Android build

- permissions: USE_BIOMETRIC, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS,
  REQUEST_INSTALL_PACKAGES (no background location)
- Gradle: androidx.biometric 1.1.0, ZXing core 3.5.3 (Apache 2.0), WorkManager 2.10.0, Firebase Messaging 24.1.0,
  CameraX 1.4.1 (core, camera2, lifecycle, view)
  (no Google gradle plugin: the build hook writes Firebase's values from `google-services.json`, see config `push`)
- activities PhotoActivity, QrScanActivity, BarcodeScanActivity, PushOpenActivity and the service SyllooMessagingService (none exported)
- the element `sylloo_window` (renderer `WindowRenderer`)
- nothing for the till jobs: their permissions come from the build hook, only when switched on (see above)

## Tests

`vendor/bin/pest` (PHP, fake bridge). The pure Kotlin rules have JVM tests in `resources/android-tests/`, copied
into the native project by the build hook: `gradlew testDebugUnitTest` in the app's `nativephp/android`.

## License

MIT. See [license.md](license.md).
