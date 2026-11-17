## rishadblack/sylloo-app-native

Sylloo App's phone package (NativePHP Mobile 4 plugin, Android): the native faces of the shared Sylloo screens and the small Kotlin jobs, each behind its own PHP class (plan: `../sylloo-erp-docs/app/nativephp-plan.md` §4.6). It must be registered in `app/Providers/NativeServiceProvider.php` (`php artisan native:plugin:register rishadblack/sylloo-app-native`).

### The shared screens' native faces

- `Faces\NativeRoutes::register()` in the app's `routes/mobile.php` adds every face (paths in config `sylloo-app-native.screens.paths`; `false` leaves one out). Each face (`Faces\Activate`, `SignIn`, `Pin`, …) only draws the base's screen logic; never copy logic or texts into the app.
- The app's own screens use the `Faces\NeedsReadyDevice` trait (`if (! $this->deviceReady()) { return; }` in `mount()`): the native twin of the base's `sylloo.device` middleware.
- App pieces at hook places: `app(Faces\NativePieces::class)->add('activate.below_code', 'view')->action('name', fn (ScreenFace $face) => …)`; "Scan QR code": `Faces\ScanQr::addTo(...)` (bind `Contracts\QrScanner` to `Qr`; the answer comes back as `QrAnswered` with id `ScanQr::ID`).
- Never put a native list inside the scroll view (Compose crash): lines and tiles are rows.
- Test with `Native::test(Faces\Activate::class)` / `Native::visit('/')->follow()` and the base's `PretendOffice`, `RecordingBrowser`, `TestDevice`.

### Clock

@verbatim
<code-snippet name="Reading the phone's running timer" lang="php">
use Rishadblack\SyllooAppNative\Facades\Clock;

$reading = Clock::elapsed(); // Rishadblack\SyllooAppNative\Data\ClockReading, or null off the phone
$reading?->elapsedMs;        // SystemClock.elapsedRealtime(): ms since the phone started, sleep included
$reading?->bootCount;        // Settings.Global.BOOT_COUNT, null when the phone does not report it
</code-snippet>
@endverbatim

In tests fake the bridge: `Native::fakeBridge()->respondTo('Clock.Elapsed', ['status' => 'success', 'data' => ['elapsed_ms' => 1, 'boot_count' => 1]])`.

### The other jobs (resolve the class from the container; off the phone each answers null / false)

- `Secret` (Keystore, the base's `SecretStore`): `get` throws on a Keystore error, never reads it as "no secret".
- `Lock` (`status()`, `prompt()` → event `Events\LockAnswered`), `Location` (`fix($seconds, $id)` → `LocationAnswered`, read with `Data\LocationReading::fromEvent()`), `Camera` (`selfie()` / `photo()` → `CameraAnswered`; no gallery; made small in Kotlin; `forget($path)` after a good send), `Qr` (the `Contracts\QrScanner`: `start($id)` → `QrAnswered`).
- `Push` (`askPermission()`, `token()` → `PushTokenAnswered`, `opened()` / event `PushOpened`): Firebase values come from `google-services.json` at build time (config `sylloo-app-native.push.google_services`, env `SYLLOO_GOOGLE_SERVICES_JSON`; never in git).
- `Sync` (`schedule($periodMinutes)`: WorkManager runs `sylloo-app-native:send-waiting`, which calls the app's `Contracts\WaitingSender`; print RETRY by returning false), `Update` (`install($url, $sha256)`: GitHub release hosts only → `UpdateAnswered`), `Window` (`clear()`), `Phone` (`name()` from core `Device.GetInfo`).
- Listen with `#[On(Event::class, when: ['id' => '…'])]`; test with `Native::fakeBridge()->respondTo('Job.Call', [...])` and `->emitNative(Event::class, [...])`.

### The cloud window

`<native:sylloo-window :src :hosts :paths :allow-http :keep-alive-path :keep-alive-seconds @leave @ended @navigated @failed />` (props from `Window\WindowSession::fromTicket($answer, $device->hosts(), $employee)->props()`). The package's own web view: no app bridge, only addresses `WindowRules::mayShow()` allows (Kotlin twin `WindowFilter`, both tested against `tests/Fixtures/window-cases.json`), uploads, downloads, print, back. Change the PHP and Kotlin rules together.

### Adding a job

One Kotlin `object` per job in `resources/android/` (`android_params: ["context"]`), one bridge function per call in `nativephp.json` (`Job.Call` → `com.rishadblack.plugins.sylloo_app_native.<Object>.<Class>`), one PHP class in `src/`, an event class in `src/Events` (listed in the manifest's `events`) for an answer that comes later, permissions and Gradle dependencies in the manifest's `android` block. Pure Kotlin rules get JVM tests in `resources/android-tests/` (copied by the build hook; run `gradlew testDebugUnitTest` in `nativephp/android`). No business rules here: they live in the shared base.

### Security

`unserialize()` runs the code of whatever classes its bytes name. If this plugin caches or
persists a serialized PHP value that a later request could read back, sign or encrypt it with
the app key (`app('encrypter')->getKey()`) on write and verify or decrypt it before you
unserialize — then drop anything whose signature or shape does not match. Prefer JSON for
data that does not need PHP objects.
