<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppNative\Data\PrinterTarget;
use Rishadblack\SyllooAppNative\Data\PrintOutcome;
use Rishadblack\SyllooAppNative\Events\PrintAnswered;
use Rishadblack\SyllooAppNative\Events\ScanCameraClosed;
use Rishadblack\SyllooAppNative\Events\ScanReceived;
use Rishadblack\SyllooAppNative\Printer;
use Rishadblack\SyllooAppNative\ScanKeys;
use Rishadblack\SyllooAppNative\Scanner;
use Rishadblack\SyllooAppNative\Support\BackupManifest;
use Rishadblack\SyllooAppNative\Support\FeatureManifest;

/*
 * The Android till's jobs (the till plan §3.4, §7): receipt printing, the cash drawer, the typing scanner and the camera scanner. Each is
 * off unless the app turns it on, and its permissions are in the build only when it is on.
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
});

describe('Switched off (the default)', function () {
    it('answers not_set_up without asking the phone', function () {
        $printer = app(Printer::class);

        expect(config('sylloo-app-native.features'))->toBe(['print' => false, 'scan_keys' => false, 'scan_camera' => false])
            ->and($printer->enabled())->toBeFalse()
            ->and($printer->receipt('<p>x</p>', 58, 'bt:AA:BB:CC:DD:EE:FF')->status)->toBe('not_set_up')
            ->and($printer->picture("\x89PNG\r\n\x1a\nx", 80, 'net:10.0.0.5')->status)->toBe('not_set_up')
            ->and($printer->drawer('net:10.0.0.5')->reason)->toBe('feature_off')
            ->and($printer->printers())->toBe(['status' => 'not_set_up', 'printers' => []])
            ->and(app(ScanKeys::class)->on())->toBe('not_set_up')
            ->and(app(ScanKeys::class)->off())->toBe('not_set_up')
            ->and(app(Scanner::class)->cameraEnabled())->toBeFalse()
            ->and(app(Scanner::class)->camera('sell'))->toBe('not_set_up');

        $this->bridge->assertNothingCalled();
    });
});

describe('Printer', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.print', true);
    });

    it('sends a receipt page with the paper, the printer and the options, and says it started', function () {
        $this->bridge->respondTo('Print.Receipt', ['status' => 'started']);

        $outcome = app(Printer::class)->receipt('<p>বিক্রয় রসিদ</p>', 80, 'bt:aa:bb:cc:dd:ee:ff', 'sale-7', copies: 9, openDrawer: true);

        expect($outcome->status)->toBe(PrintOutcome::STARTED)
            ->and($outcome->started())->toBeTrue()
            ->and($outcome->id)->toBe('sale-7');

        $this->bridge->assertCalled('Print.Receipt', fn ($p) => $p === [
            'html' => '<p>বিক্রয় রসিদ</p>',
            'paper_mm' => 80,
            'target' => 'bt:AA:BB:CC:DD:EE:FF',
            'copies' => 5,
            'cut' => true,
            'open_drawer' => true,
            'id' => 'sale-7',
        ]);
    });

    it('sends a ready PNG as base64', function () {
        $png = "\x89PNG\r\n\x1a\n".random_bytes(16);
        $this->bridge->respondTo('Print.Receipt', ['status' => 'started']);

        app(Printer::class)->picture($png, 58, PrinterTarget::usb(0x0416, 0x5011), cut: false);

        $this->bridge->assertCalled('Print.Receipt', fn ($p) => $p['png'] === base64_encode($png)
            && $p['target'] === 'usb:0416:5011' && $p['paper_mm'] === 58 && $p['cut'] === false && ! isset($p['html']));
    });

    it('refuses plainly before asking the phone', function (Closure $call, string $status, string $reason) {
        $outcome = $call(app(Printer::class));

        expect($outcome)->toBeInstanceOf(PrintOutcome::class)
            ->and($outcome->status)->toBe($status)
            ->and($outcome->reason)->toBe($reason);

        $this->bridge->assertNothingCalled();
    })->with([
        'no printer' => [fn (Printer $p) => $p->receipt('<p>x</p>', 58, ''), 'no_printer', 'target'],
        'a printer address that is no address' => [fn (Printer $p) => $p->receipt('<p>x</p>', 58, 'lpt:1'), 'no_printer', 'target'],
        'a paper width the printers do not have' => [fn (Printer $p) => $p->receipt('<p>x</p>', 60, 'net:10.0.0.5'), 'failed', 'paper'],
        'an empty page' => [fn (Printer $p) => $p->receipt('  ', 58, 'net:10.0.0.5'), 'failed', 'empty'],
        'a page too big' => [fn (Printer $p) => $p->receipt(str_repeat('x', Printer::MAX_HTML_BYTES + 1), 58, 'net:10.0.0.5'), 'failed', 'too_big'],
        'not a PNG' => [fn (Printer $p) => $p->picture('GIF89a', 58, 'net:10.0.0.5'), 'failed', 'empty'],
        'a drawer with no printer' => [fn (Printer $p) => $p->drawer('bt:nope'), 'no_printer', 'target'],
    ]);

    it('passes the phone\'s own refusal on as a plain outcome', function () {
        $this->bridge->respondTo('Print.Receipt', ['status' => 'not_set_up', 'reason' => 'feature_off'])
            ->respondTo('Print.Drawer', ['status' => 'error', 'code' => 'UNKNOWN_ERROR', 'message' => 'boom']);

        $printer = app(Printer::class);

        expect($printer->receipt('<p>x</p>', 58, 'net:10.0.0.5')->status)->toBe('not_set_up')
            ->and($printer->drawer('net:10.0.0.5'))->toEqual(new PrintOutcome('failed', 'unknown_error', null, 'drawer'));
    });

    it('opens the drawer through the printer', function () {
        $this->bridge->respondTo('Print.Drawer', ['status' => 'started']);

        expect(app(Printer::class)->drawer('net:192.168.1.50:9100', 'drawer-1')->started())->toBeTrue();

        $this->bridge->assertCalled('Print.Drawer', fn ($p) => $p === ['target' => 'net:192.168.1.50:9100', 'id' => 'drawer-1']);
    });

    it('lists the printers the phone can reach and drops anything that is no printer address', function () {
        $this->bridge->respondTo('Print.Printers', ['status' => 'listed', 'printers' => [
            ['kind' => 'bluetooth', 'name' => 'RPP02N', 'target' => 'bt:66:22:11:33:44:55'],
            ['kind' => 'usb', 'name' => '', 'target' => 'usb:0416:5011'],
            ['kind' => 'bluetooth', 'name' => 'Odd', 'target' => 'bt:nope'],
            'junk',
        ]]);

        expect(app(Printer::class)->printers())->toBe(['status' => 'listed', 'printers' => [
            ['kind' => 'bluetooth', 'name' => 'RPP02N', 'target' => 'bt:66:22:11:33:44:55'],
            ['kind' => 'usb', 'name' => '0416:5011', 'target' => 'usb:0416:5011'],
        ]]);
    });

    it('asks for the Bluetooth permission only when told to', function () {
        $this->bridge->respondTo('Print.Printers', ['status' => 'asking', 'printers' => []]);

        expect(app(Printer::class)->printers(ask: true, id: 'setup')['status'])->toBe('asking');

        $this->bridge->assertCalled('Print.Printers', fn ($p) => $p === ['ask' => true, 'id' => 'setup']);
    });

    it('reads the final answer from the event', function () {
        $outcome = PrintOutcome::fromEvent(new PrintAnswered('no_printer', 'sale-7', 'receipt', 'not_paired'));

        expect($outcome->status)->toBe('no_printer')
            ->and($outcome->reason)->toBe('not_paired')
            ->and($outcome->done())->toBeFalse()
            ->and(PrintOutcome::fromEvent(new PrintAnswered('opened', job: 'drawer'))->done())->toBeTrue();
    });

    it('answers failed when the phone gives no answer', function () {
        expect(app(Printer::class)->drawer('net:10.0.0.5'))->toEqual(new PrintOutcome('failed', 'no_answer', null, 'drawer'));
    });
});

describe('PrinterTarget', function () {
    it('reads and writes the same texts as the Kotlin twin', function (string $text, ?string $kind, ?string $normal) {
        $target = PrinterTarget::parse($text);

        expect($target?->kind)->toBe($kind)
            ->and($target?->toString())->toBe($normal);
    })->with([
        ['bt:aa:bb:cc:dd:ee:0f', 'bluetooth', 'bt:AA:BB:CC:DD:EE:0F'],
        ['usb:0416:5011', 'usb', 'usb:0416:5011'],
        ['net:192.168.1.50', 'network', 'net:192.168.1.50:9100'],
        [' NET:Printer.local:9101 ', 'network', 'net:printer.local:9101'],
        ['', null, null],
        ['bt:', null, null],
        ['bt:AA:BB', null, null],
        ['usb:416:5011', null, null],
        ['net:', null, null],
        ['net:a b', null, null],
        ['net:host:0', null, null],
        ['net:host:70000', null, null],
        ['net:http://x', null, null],
        ['lpt:1', null, null],
        ['AA:BB:CC:DD:EE:FF', null, null],
    ]);

    it('has its own makers', function () {
        expect((string) PrinterTarget::bluetooth('66:22:11:33:44:55'))->toBe('bt:66:22:11:33:44:55')
            ->and(PrinterTarget::usb(0x1FFFF, 1))->toBeNull()
            ->and(PrinterTarget::network('10.0.0.5', 9100)?->port)->toBe(9100)
            ->and(PrinterTarget::network('10.0.0.5', 0))->toBeNull();
    });
});

describe('ScanKeys', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.scan_keys', true);
    });

    it('turns the typing scanner on with the configured settings, and off', function () {
        config()->set('sylloo-app-native.scan_keys.gap_ms', 40);
        $this->bridge->respondTo('Scan.Keys', fn ($p) => ['status' => $p['on'] ? 'on' : 'off']);

        $scan = app(ScanKeys::class);

        expect($scan->on())->toBe('on')
            ->and($scan->off())->toBe('off');

        $this->bridge->assertCalled('Scan.Keys', fn ($p) => $p === ['on' => true, 'gap_ms' => 40, 'end_ms' => 150, 'min_length' => 4, 'end_on_gap' => true])
            ->assertCalled('Scan.Keys', fn ($p) => $p === ['on' => false]);
    });

    it('answers failed when the phone does not answer', function () {
        expect(app(ScanKeys::class)->on())->toBe('failed');
    });

    it('hands each code to PHP as ScanReceived', function () {
        $event = new ScanReceived('8901234567890');

        expect($event->code)->toBe('8901234567890')
            ->and($event->source)->toBe('keys');
    });
});

describe('Scanner (camera)', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.scan_camera', true);
    });

    it('opens the camera scanner for many in a row by default, or for one', function () {
        $this->bridge->respondTo('Scan.Camera', fn () => ['status' => 'started']);

        $scanner = app(Scanner::class);

        expect($scanner->camera('sell'))->toBe('started')
            ->and($scanner->camera('find', continuous: false))->toBe('started');

        $this->bridge->assertCalled('Scan.Camera', fn ($p) => $p === ['id' => 'sell', 'continuous' => true])
            ->assertCalled('Scan.Camera', fn ($p) => $p === ['id' => 'find', 'continuous' => false]);
    });

    it('passes on not_set_up from a build without the feature, and failed for anything else', function () {
        $this->bridge->respondTo('Scan.Camera', fn () => ['status' => 'not_set_up']);
        expect(app(Scanner::class)->camera('sell'))->toBe('not_set_up');

        $this->bridge->respondTo('Scan.Camera', fn () => ['status' => 'odd']);
        expect(app(Scanner::class)->camera('sell'))->toBe('failed');
    });

    it('answers failed when the phone does not answer', function () {
        expect(app(Scanner::class)->camera('sell'))->toBe('failed');
    });

    it('hands each camera code to PHP as ScanReceived with its format, and the close as ScanCameraClosed', function () {
        $read = new ScanReceived(code: '8901234567890', source: 'camera', format: 'ean_13', id: 'sell');
        $closed = new ScanCameraClosed(status: 'done', id: 'sell', count: 3);

        expect([$read->code, $read->source, $read->format, $read->id])->toBe(['8901234567890', 'camera', 'ean_13', 'sell'])
            ->and([$closed->status, $closed->id, $closed->count])->toBe(['done', 'sell', 3]);
    });

    it('is a declared bridge function, activity and event of the plugin', function () {
        $plugin = json_decode((string) file_get_contents(dirname(__DIR__, 2).'/nativephp.json'), true);

        expect(collect($plugin['bridge_functions'])->firstWhere('name', 'Scan.Camera')['android'] ?? null)
            ->toBe('com.rishadblack.plugins.sylloo_app_native.BarcodeFunctions.Camera')
            ->and(collect($plugin['android']['activities'])->pluck('name'))->toContain('.BarcodeScanActivity')
            ->and($plugin['events'])->toContain(ScanCameraClosed::class)->toContain(ScanReceived::class);
    });
});

describe('The build hook', function () {
    beforeEach(function () {
        $this->build = sys_get_temp_dir().'/sylloo-native-build-'.bin2hex(random_bytes(4));
        mkdir($this->build.'/app/src/main', 0755, true);
        $made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
        file_put_contents($this->build.'/app/src/main/AndroidManifest.xml', $made);
        // What every build ends with when no job is on: NativePHP's manifest with app backup off (BackupTest).
        $this->original = BackupManifest::apply($made);
        config()->set('sylloo-app-native.push.google_services', '');

        $this->runHook = function (bool $print, bool $scanKeys, bool $scanCamera = false): string {
            config()->set('sylloo-app-native.features', ['print' => $print, 'scan_keys' => $scanKeys, 'scan_camera' => $scanCamera]);

            expect(Artisan::call('nativephp:sylloo-app-native:copy-assets', [
                '--platform' => 'android',
                '--build-path' => $this->build,
                '--plugin-path' => dirname(__DIR__, 2),
                '--app-id' => 'pos.sylloo.app',
            ]))->toBe(0);

            return (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');
        };
    });

    afterEach(function () {
        (new Filesystem)->deleteDirectory($this->build);
    });

    it('leaves the manifest without any Bluetooth permission when the jobs are off', function () {
        $manifest = ($this->runHook)(false, false);

        expect($manifest)->toBe($this->original)
            ->not->toContain('BLUETOOTH')
            ->not->toContain('sylloo-app-native:print')
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_print">false</bool>')
            ->toContain('<bool name="sylloo_feature_scan_keys">false</bool>');
    });

    it('adds the printer\'s permissions only with print on, once, inside the manifest, before the application', function () {
        $manifest = ($this->runHook)(true, false);
        ($this->runHook)(true, false);
        $twice = (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');

        expect($twice)->toBe($manifest)
            ->and(substr_count($manifest, 'android.permission.BLUETOOTH_CONNECT'))->toBe(1)
            ->and($manifest)->toContain('<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />')
            ->toContain('<uses-feature android:name="android.hardware.usb.host" android:required="false" />')
            ->not->toContain('BLUETOOTH_SCAN')
            ->not->toContain('BLUETOOTH_ADMIN')
            ->and(strpos($manifest, 'BLUETOOTH_CONNECT'))->toBeLessThan(strpos($manifest, '<application'))
            ->and(simplexml_load_string($manifest))->not->toBeFalse()
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_print">true</bool>');
    });

    it('takes them out again when print is switched off, back to the manifest NativePHP made', function () {
        ($this->runHook)(true, true);

        expect(($this->runHook)(false, true))->toBe($this->original);
    });

    it('adds no permission for the typing scanner', function () {
        expect(($this->runHook)(false, true))->toBe($this->original)
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_scan_keys">true</bool>');
    });

    it('adds only the optional camera feature lines for the camera scanner, and takes them out again', function () {
        $manifest = ($this->runHook)(false, false, true);

        expect($manifest)->toContain('<uses-feature android:name="android.hardware.camera" android:required="false" />')
            ->toContain('<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />')
            ->not->toContain('BLUETOOTH')
            ->and(strpos($manifest, 'android.hardware.camera"'))->toBeLessThan(strpos($manifest, '<application'))
            ->and(simplexml_load_string($manifest))->not->toBeFalse()
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_scan_camera">true</bool>')
            ->and(($this->runHook)(false, false, false))->toBe($this->original);
    });

    it('takes out a block the next build left glued to the end of a line, so a rebuild keeps one copy', function () {
        $on = FeatureManifest::apply($this->original, ['scan_camera' => true]);
        // NativePHP's permission merge on the next build: its lines written right before our block, no line break.
        $merged = str_replace('
    <!-- sylloo-app-native:scan_camera -->', '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />    <!-- sylloo-app-native:scan_camera -->', $on);

        expect(substr_count(FeatureManifest::apply($merged, ['scan_camera' => true]), '<!-- sylloo-app-native:scan_camera -->'))->toBe(1)
            ->and(FeatureManifest::apply($merged, ['scan_camera' => false]))->not->toContain('android.hardware.camera"');
    });

    it('never touches the same permission declared by someone else', function () {
        $foreign = str_replace('<application', "<uses-permission android:name=\"android.permission.BLUETOOTH_CONNECT\" />\n\n    <application", $this->original);

        expect(FeatureManifest::apply($foreign, ['print' => false]))->toBe($foreign)
            ->and(FeatureManifest::apply(FeatureManifest::apply($foreign, ['print' => true]), ['print' => false]))->toBe($foreign);
    });
});
