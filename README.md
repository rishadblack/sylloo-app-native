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
    'scan_camera' => true, // Scan.Camera                                  (env SYLLOO_NATIVE_SCAN_CAMERA)
],
```

While a job is off its PHP class answers `not_set_up` without asking the phone, the Kotlin job refuses too, and the
build has none of its permissions. The build hook adds a switched-on job's permissions to the generated
`AndroidManifest.xml` in a marked block (print: `BLUETOOTH_CONNECT`, and `BLUETOOTH` up to Android 11, plus optional
Bluetooth / USB host features; the typing scanner needs none; the camera scanner only optional camera features, as
CAMERA is already declared for `Qr.Scan`) and takes the block out when the job is off again. They are not
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
app(Scanner::class)->camera('find', continuous: false);   // closes after the first read

#[On(ScanReceived::class)]   // code, source 'camera', format (ean_13, ean_8, upc_a, upc_e, code_128, code_39, qr_code), id
#[On(ScanCameraClosed::class)]   // status done | cancelled | denied | failed, id, count
```

The camera screen (CameraX + ZXing core, like `Qr.Scan`) asks for the camera permission the first time, sends each
code at once, beeps and buzzes per read, ignores the same code again within 1.5 s, and closes with Done or Back.

## What the manifest adds to the Android build

- permissions: USE_BIOMETRIC, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS,
  REQUEST_INSTALL_PACKAGES (no background location)
- Gradle: androidx.biometric 1.1.0, ZXing core 3.5.3 (Apache 2.0), WorkManager 2.10.0, Firebase Messaging 24.1.0
  (no Google gradle plugin: the build hook writes Firebase's values from `google-services.json`, see config `push`)
- activities PhotoActivity, QrScanActivity, BarcodeScanActivity, PushOpenActivity and the service SyllooMessagingService (none exported)
- the element `sylloo_window` (renderer `WindowRenderer`)
- nothing for the till jobs: their permissions come from the build hook, only when switched on (see above)

## Tests

`vendor/bin/pest` (PHP, fake bridge). The pure Kotlin rules have JVM tests in `resources/android-tests/`, copied
into the native project by the build hook: `gradlew testDebugUnitTest` in the app's `nativephp/android`.

## License

MIT. See [license.md](license.md).
