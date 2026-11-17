# rishadblack/sylloo-app-native

Sylloo App's phone package: a NativePHP Mobile 4 plugin (Android). It holds the native faces of the shared
Sylloo screens, the small Kotlin jobs (Clock, Secret, Lock, Location, Camera, Qr, Push, Sync, Update, Window;
each behind its own PHP class in `src/`) and the cloud window element `<native:sylloo-window>`. Free NativePHP
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

## What the manifest adds to the Android build

- permissions: USE_BIOMETRIC, CAMERA, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, POST_NOTIFICATIONS,
  REQUEST_INSTALL_PACKAGES (no background location)
- Gradle: androidx.biometric 1.1.0, ZXing core 3.5.3 (Apache 2.0), WorkManager 2.10.0, Firebase Messaging 24.1.0
  (no Google gradle plugin: the build hook writes Firebase's values from `google-services.json`, see config `push`)
- activities PhotoActivity, QrScanActivity, PushOpenActivity and the service SyllooMessagingService (none exported)
- the element `sylloo_window` (renderer `WindowRenderer`)

## Tests

`vendor/bin/pest` (PHP, fake bridge). The pure Kotlin rules have JVM tests in `resources/android-tests/`, copied
into the native project by the build hook: `gradlew testDebugUnitTest` in the app's `nativephp/android`.

## License

MIT. See [license.md](license.md).
