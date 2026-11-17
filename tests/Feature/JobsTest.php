<?php

use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\Cache;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppBase\Contracts\SecretStore;
use Rishadblack\SyllooAppNative\Camera;
use Rishadblack\SyllooAppNative\Contracts\QrScanner;
use Rishadblack\SyllooAppNative\Contracts\WaitingSender;
use Rishadblack\SyllooAppNative\Data\LocationReading;
use Rishadblack\SyllooAppNative\Events\LocationAnswered;
use Rishadblack\SyllooAppNative\Location;
use Rishadblack\SyllooAppNative\Lock;
use Rishadblack\SyllooAppNative\Phone;
use Rishadblack\SyllooAppNative\Push;
use Rishadblack\SyllooAppNative\Qr;
use Rishadblack\SyllooAppNative\Secret;
use Rishadblack\SyllooAppNative\Support\Bridge;
use Rishadblack\SyllooAppNative\Support\FirebaseValues;
use Rishadblack\SyllooAppNative\Sync;
use Rishadblack\SyllooAppNative\Update;
use Rishadblack\SyllooAppNative\Window;

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
});

describe('Bridge', function () {
    it('reads a bare answer and a wrapped one alike, and keeps a refusal', function () {
        $this->bridge->respondTo('A.Bare', ['x' => 1])
            ->respondTo('A.Wrapped', ['status' => 'success', 'data' => ['x' => 2]])
            ->respondTo('A.Refused', ['status' => 'error', 'code' => 'KEYSTORE', 'message' => 'locked']);

        expect(Bridge::call('A.Bare'))->toBe(['x' => 1])
            ->and(Bridge::call('A.Wrapped'))->toBe(['x' => 2])
            ->and(Bridge::call('A.Refused'))->toBeNull()
            ->and(Bridge::result('A.Refused'))->toMatchArray(['ok' => false, 'code' => 'KEYSTORE', 'message' => 'locked'])
            ->and(Bridge::call('A.Unanswered'))->toBeNull();
    });
});

describe('Secret', function () {
    it('is the phone\'s SecretStore and keeps, reads and deletes through the Keystore job', function () {
        $this->bridge->respondTo('Secret.Get', fn ($params) => $params['name'] === 'phone.data-key' ? ['found' => true, 'value' => 's3cret'] : ['found' => false, 'value' => null])
            ->respondTo('Secret.Set', ['stored' => true])
            ->respondTo('Secret.Delete', ['deleted' => true]);

        $secret = app(Secret::class);

        expect($secret)->toBeInstanceOf(SecretStore::class)
            ->and($secret->get('phone.data-key'))->toBe('s3cret')
            ->and($secret->get('other'))->toBeNull()
            ->and($secret->set('phone.data-key', 'new'))->toBeTrue()
            ->and($secret->delete('phone.data-key'))->toBeTrue();

        $secret->put('a', 'b');
        $secret->put('a', null);

        $this->bridge->assertCalled('Secret.Set', fn ($p) => $p === ['name' => 'phone.data-key', 'value' => 'new'])
            ->assertCalled('Secret.Set', fn ($p) => $p === ['name' => 'a', 'value' => 'b'])
            ->assertCalled('Secret.Delete', fn ($p) => $p === ['name' => 'a']);
    });

    it('throws when the Keystore refuses a read, so a refusal is never taken for "no secret"', function () {
        $this->bridge->respondTo('Secret.Get', ['status' => 'error', 'code' => 'KEYSTORE', 'message' => 'Key invalidated']);

        app(Secret::class)->get('phone.data-key');
    })->throws(RuntimeException::class, 'Key invalidated');

    it('refuses bad names and secrets before asking the phone', function (string $name, string $value) {
        app(Secret::class)->set($name, $value);
    })->with([
        'capitals' => ['Phone', 'x'],
        'a slash' => ['a/b', 'x'],
        'too long a name' => [str_repeat('a', 65), 'x'],
        'empty secret' => ['a', ''],
        'too long a secret' => ['a', str_repeat('x', 4097)],
    ])->throws(RuntimeException::class);
});

describe('Lock', function () {
    it('reads the status and opens the prompt', function () {
        $this->bridge->respondTo('Lock.Status', ['status' => 'available'])->respondTo('Lock.Prompt', ['started' => true]);

        expect(app(Lock::class)->isAvailable())->toBeTrue()
            ->and(app(Lock::class)->prompt('Unlock Sylloo App', cancel: 'Use PIN', id: 'unlock'))->toBeTrue();

        $this->bridge->assertCalled('Lock.Prompt', fn ($p) => $p === ['title' => 'Unlock Sylloo App', 'cancel' => 'Use PIN', 'id' => 'unlock']);
    });

    it('is not available off the phone or when nothing is enrolled', function () {
        $this->bridge->respondTo('Lock.Status', ['status' => 'none_enrolled']);

        expect(app(Lock::class)->isAvailable())->toBeFalse()->and(app(Lock::class)->status())->toBe('none_enrolled');
    });
});

describe('Location', function () {
    it('asks for one reading within the time limit, kept between 2 and 60 seconds', function () {
        $this->bridge->respondTo('Location.Fix', ['started' => true]);

        expect(app(Location::class)->fix(30, 'punch-1'))->toBeTrue();
        app(Location::class)->fix(600);
        app(Location::class)->fix(0);

        $this->bridge->assertCalled('Location.Fix', fn ($p) => $p === ['timeout_ms' => 30000, 'id' => 'punch-1'])
            ->assertCalled('Location.Fix', fn ($p) => $p === ['timeout_ms' => 60000])
            ->assertCalled('Location.Fix', fn ($p) => $p === ['timeout_ms' => 2000]);
    });

    it('reads an ok answer into a reading with the satellite time', function () {
        $reading = LocationReading::fromEvent(new LocationAnswered(
            status: 'ok', id: 'punch-1', latitude: 23.81, longitude: 90.41, accuracy: 8.5, fake: false,
            gpsTimeMs: 1_763_380_800_123, fixElapsedMs: 5000, elapsedMs: 5100, bootCount: 12, provider: 'gps',
        ));

        expect($reading->latitude)->toBe(23.81)
            ->and($reading->isGps())->toBeTrue()
            ->and($reading->gpsTime())->toBe('2025-11-17T12:00:00.123Z')
            ->and($reading->bootCount)->toBe(12);
    });

    it('has no reading for a timeout, a refusal or location off, and no restart count the phone does not know', function () {
        expect(LocationReading::fromEvent(new LocationAnswered('timeout')))->toBeNull()
            ->and(LocationReading::fromEvent(new LocationAnswered('denied')))->toBeNull()
            ->and(LocationReading::fromEvent(new LocationAnswered('ok', latitude: 1.0, longitude: 2.0, bootCount: -1, provider: 'network'))->bootCount)->toBeNull();
    });
});

describe('Camera', function () {
    it('takes a front selfie with the outline or a back photo, the size kept in bounds', function () {
        $this->bridge->respondTo('Camera.Take', ['started' => true]);

        expect(app(Camera::class)->selfie('punch-1'))->toBeTrue();
        app(Camera::class)->photo('visit-1');
        app(Camera::class)->take('side', true, 99999, null, 200);

        $this->bridge->assertCalled('Camera.Take', fn ($p) => $p === ['lens' => 'front', 'outline' => true, 'max_side' => 1024, 'quality' => 80, 'id' => 'punch-1'])
            ->assertCalled('Camera.Take', fn ($p) => $p === ['lens' => 'back', 'outline' => false, 'max_side' => 1600, 'quality' => 80, 'id' => 'visit-1'])
            ->assertCalled('Camera.Take', fn ($p) => $p === ['lens' => 'front', 'outline' => true, 'max_side' => 2048, 'quality' => 95]);
    });

    it('deletes only its own photos', function () {
        $folder = sys_get_temp_dir().'/sylloo-photos';
        @mkdir($folder);
        file_put_contents($mine = $folder.'/p.jpg', 'x');
        file_put_contents($other = sys_get_temp_dir().'/other.jpg', 'x');

        expect(app(Camera::class)->forget($mine))->toBeTrue()
            ->and(app(Camera::class)->forget($other))->toBeFalse()
            ->and(file_exists($other))->toBeTrue();

        @unlink($other);
    });
});

describe('Qr', function () {
    it('is a QrScanner that opens the scanner with an id', function () {
        $this->bridge->respondTo('Qr.Scan', ['started' => true]);

        expect(app(Qr::class))->toBeInstanceOf(QrScanner::class)
            ->and(app(Qr::class)->start('activate-code'))->toBeTrue();

        $this->bridge->assertCalled('Qr.Scan', fn ($p) => $p === ['id' => 'activate-code']);
    });
});

describe('Push', function () {
    it('asks for permission and the token, and reads a tapped notice\'s https address once', function () {
        $this->bridge->respondTo('Push.Permission', ['started' => true])
            ->respondTo('Push.Token', ['started' => true])
            ->respondTo('Push.Opened', ['url' => 'https://demo.sylloo.app/member/leave/5']);

        expect(app(Push::class)->askPermission())->toBeTrue()
            ->and(app(Push::class)->token('t'))->toBeTrue()
            ->and(app(Push::class)->opened())->toBe('https://demo.sylloo.app/member/leave/5');

        $this->bridge->assertCalled('Push.Token', fn ($p) => $p === ['id' => 't']);
    });

    it('drops a tapped address that is not https', function () {
        $this->bridge->respondTo('Push.Opened', ['url' => 'javascript:alert(1)']);

        expect(app(Push::class)->opened())->toBeNull();
    });
});

describe('Sync', function () {
    it('schedules the send-waiting command and cancels it', function () {
        $this->bridge->respondTo('Sync.Schedule', ['scheduled' => true])->respondTo('Sync.Cancel', ['cancelled' => true]);

        expect(app(Sync::class)->schedule(15))->toBeTrue()->and(app(Sync::class)->cancel())->toBeTrue();

        $this->bridge->assertCalled('Sync.Schedule', fn ($p) => $p === ['command' => 'sylloo-app-native:send-waiting', 'period_minutes' => 15]);
    });

    it('runs the app\'s sender in the background command and says when to try again', function () {
        $sender = new class implements WaitingSender
        {
            public bool $done = false;

            public int $runs = 0;

            public function sendWaiting(): bool
            {
                $this->runs++;

                return $this->done;
            }
        };
        app()->instance(WaitingSender::class, $sender);

        Artisan::call('sylloo-app-native:send-waiting');
        expect(Artisan::output())->toContain('RETRY');

        $sender->done = true;
        Artisan::call('sylloo-app-native:send-waiting');
        expect(Artisan::output())->toContain('DONE')->and($sender->runs)->toBe(2);
    });

    it('does nothing in the background without a sender', function () {
        Artisan::call('sylloo-app-native:send-waiting');

        expect(Artisan::output())->toContain('NOTHING');
    });
});

describe('Update', function () {
    it('installs from the release hosts with the SHA-256', function () {
        $this->bridge->respondTo('Update.Install', ['started' => true]);
        $sha = str_repeat('ab', 32);

        expect(app(Update::class)->install('https://github.com/rishadblack/x/releases/download/v1/app.apk', strtoupper($sha), 'u1'))->toBeTrue();

        $this->bridge->assertCalled('Update.Install', fn ($p) => $p['sha256'] === $sha && $p['id'] === 'u1');
    });

    it('refuses another host, http, a user in the address, or a missing SHA-256', function (string $url, string $sha) {
        app(Update::class)->install($url, $sha);
    })->with([
        'another host' => ['https://example.com/app.apk', str_repeat('a', 64)],
        'http' => ['http://github.com/app.apk', str_repeat('a', 64)],
        'a user' => ['https://x@github.com/app.apk', str_repeat('a', 64)],
        'no sha' => ['https://github.com/app.apk', 'abc'],
    ])->throws(InvalidArgumentException::class);
});

describe('Window', function () {
    it('clears what the window keeps', function () {
        $this->bridge->respondTo('Window.Clear', ['cleared' => true]);

        expect(app(Window::class)->clear())->toBeTrue();
    });
});

describe('Phone', function () {
    it('names the phone with the name its owner gave it, else maker and model, and keeps it', function () {
        Cache::forget(Phone::CACHE_KEY);
        $this->bridge->respondTo('Device.GetInfo', ['info' => json_encode(['name' => '', 'manufacturer' => 'Xiaomi', 'model' => 'Redmi Note 12'])]);

        expect(app(Phone::class)->name())->toBe('Xiaomi Redmi Note 12');

        $this->bridge->respondTo('Device.GetInfo', ['info' => json_encode(['name' => 'Rahim\'s phone'])]);
        expect(app(Phone::class)->name())->toBe('Xiaomi Redmi Note 12');

        Cache::forget(Phone::CACHE_KEY);
        expect(app(Phone::class)->name())->toBe('Rahim\'s phone');
    });

    it('has no name off the phone', function () {
        Cache::forget(Phone::CACHE_KEY);

        expect(app(Phone::class)->name())->toBeNull();
    });
});

describe('Firebase values', function () {
    it('turns google-services.json into the strings Firebase starts with, for the app id only', function () {
        $json = [
            'project_info' => ['project_number' => '123456', 'project_id' => 'sylloo-app', 'storage_bucket' => 'sylloo-app.appspot.com'],
            'client' => [
                ['client_info' => ['mobilesdk_app_id' => '1:123:android:other', 'android_client_info' => ['package_name' => 'com.other']], 'api_key' => [['current_key' => 'K0']]],
                ['client_info' => ['mobilesdk_app_id' => '1:123:android:abc', 'android_client_info' => ['package_name' => 'com.sylloo.app']], 'api_key' => [['current_key' => 'K1']]],
            ],
        ];

        $values = FirebaseValues::fromJson($json, 'com.sylloo.app');

        expect($values)->toMatchArray(['google_app_id' => '1:123:android:abc', 'gcm_defaultSenderId' => '123456', 'google_api_key' => 'K1', 'project_id' => 'sylloo-app'])
            ->and(FirebaseValues::fromJson($json, 'com.missing'))->toBeNull()
            ->and(FirebaseValues::xml($values))->toContain('<string name="google_app_id" translatable="false">1:123:android:abc</string>');
    });
});
