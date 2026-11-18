<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppBase\Contracts\MessagingPhone;
use Rishadblack\SyllooAppBase\Testing\TestDevice;
use Rishadblack\SyllooAppNative\Events\SmsAnswered;
use Rishadblack\SyllooAppNative\Events\SmsPermissionAnswered;
use Rishadblack\SyllooAppNative\Faces;
use Rishadblack\SyllooAppNative\Faces\NativeRoutes;
use Rishadblack\SyllooAppNative\Messages;
use Rishadblack\SyllooAppNative\Sms;
use Rishadblack\SyllooAppNative\Support\BackupManifest;
use Rishadblack\SyllooAppNative\Support\FeatureManifest;

/*
 * Phone SMS (phone-messaging-plan.md SM2): Sms.* and the background job Messages.*, off unless the app turns on
 * features.sms_send, with SEND_SMS and READ_PHONE_STATE in the build only then. The loop itself is tested in Kotlin
 * (MessagesLoopTest, MessagesRulesTest).
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
});

describe('Switched off (the default)', function () {
    it('answers not_set_up or nothing without asking the phone', function () {
        $messages = app(Messages::class);

        expect(app(Sms::class)->enabled())->toBeFalse()
            ->and(app(Sms::class)->send('+8801712345678', 'Hello'))->toBe('not_set_up')
            ->and(app(Sms::class)->sims())->toBe(['status' => 'not_set_up', 'sims' => [], 'can_send' => false, 'default_slot' => null])
            ->and(app(Sms::class)->askPermission())->toBe('not_set_up')
            ->and($messages->available())->toBeFalse()
            ->and($messages->hasPermission())->toBeFalse()
            ->and($messages->start('https://erp.sylloo.test', 'device-key-123456789', '1.0.0'))->toBeFalse()
            ->and($messages->wake())->toBeFalse()
            ->and($messages->status())->toBe([]);

        $this->bridge->assertNothingCalled();
    });

    it('draws no native route for the SMS screens', function () {
        expect(NativeRoutes::path('messages'))->toBeNull()
            ->and(NativeRoutes::path('messages_accept'))->toBeNull();
    });

    it('is the base\'s messaging phone, which then says it can not send', function () {
        expect(app(MessagingPhone::class))->toBeInstanceOf(Messages::class)
            ->and(app(MessagingPhone::class)->available())->toBeFalse();
    });
});

describe('Sms', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.sms_send', true);
    });

    it('sends one SMS from the chosen SIM; the outcome comes as an event', function () {
        $this->bridge->respondTo('Sms.Send', fn () => ['status' => 'started', 'slot' => 1]);

        expect(app(Sms::class)->send('+8801712345678', 'আপনার অর্ডার তৈরি', slot: 1, id: 'order-7'))->toBe('started');

        $this->bridge->assertCalled('Sms.Send', fn ($p) => $p === ['to' => '+8801712345678', 'text' => 'আপনার অর্ডার তৈরি', 'slot' => 1, 'id' => 'order-7']);

        $event = new SmsAnswered('failed', 'order-7', 'no_signal');
        expect($event->reason)->toBe('no_signal');
    });

    it('refuses a number that is not E.164 or an empty text without asking the phone', function () {
        expect(app(Sms::class)->send('01712345678', 'Hello'))->toBe('failed')
            ->and(app(Sms::class)->send('+8801712345678', ''))->toBe('failed')
            ->and(app(Sms::class)->send('+8801712345678', str_repeat('a', 2001)))->toBe('failed');

        $this->bridge->assertNothingCalled();
    });

    it('lists the SIMs by slot and operator, and tells the permission', function () {
        $this->bridge->respondTo('Sms.Sims', fn () => ['status' => 'listed', 'can_send' => true, 'default_slot' => 0, 'sims' => [
            ['slot' => 0, 'label' => 'SIM 1 · Grameenphone', 'operator' => 'Grameenphone'],
            ['slot' => 1, 'label' => 'SIM 2 · Robi', 'operator' => 'Robi'],
            ['slot' => 'x'],
        ]]);

        expect(app(Sms::class)->sims())->toBe(['status' => 'listed', 'sims' => [
            ['slot' => 0, 'label' => 'SIM 1 · Grameenphone', 'operator' => 'Grameenphone'],
            ['slot' => 1, 'label' => 'SIM 2 · Robi', 'operator' => 'Robi'],
        ], 'can_send' => true, 'default_slot' => 0])
            ->and(app(Sms::class)->hasPermission())->toBeTrue()
            ->and(app(Messages::class)->sims())->toBe([['slot' => 0, 'label' => 'SIM 1 · Grameenphone'], ['slot' => 1, 'label' => 'SIM 2 · Robi']]);
    });

    it('has no SIM list before Android allowed it, and asks Android', function () {
        $this->bridge->respondTo('Sms.Sims', fn () => ['status' => 'permission', 'sims' => []])
            ->respondTo('Sms.Permission', fn () => ['status' => 'asking']);

        expect(app(Messages::class)->sims())->toBeNull()
            ->and(app(Messages::class)->hasPermission())->toBeFalse()
            ->and(app(Messages::class)->askPermission())->toBeTrue()
            ->and((new SmsPermissionAnswered('granted'))->status)->toBe('granted');

        $this->bridge->assertCalled('Sms.Permission');
    });
});

describe('Messages (the background job)', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.sms_send', true);
    });

    it('hands the key, the office address and the notice in the app language to the phone', function () {
        $this->bridge->respondTo('Messages.Start', fn () => ['status' => 'started']);
        TestDevice::bind();

        expect(app(Messages::class)->start('https://erp.sylloo.test', 'device-key-123456789', '1.4.0'))->toBeTrue();

        $this->bridge->assertCalled('Messages.Start', fn ($p) => $p['base_url'] === 'https://erp.sylloo.test'
            && $p['device_key'] === 'device-key-123456789'
            && $p['app_version'] === '1.4.0'
            && $p['notice_title'] === "Sending the shop's messages"
            && $p['notice_text'] === 'SMS of XYZ Trading go out from this phone.');
    });

    it('wakes, stops and reads the phone\'s state', function () {
        $this->bridge->respondTo('Messages.Work', fn () => ['status' => 'started'])
            ->respondTo('Messages.Stop', fn () => ['status' => 'stopped'])
            ->respondTo('Messages.Status', fn () => ['status' => 'ok', 'online' => true, 'can_send' => true, 'service' => 'no_signal', 'sent_today' => 3, 'failed_today' => 1]);

        expect(app(Messages::class)->wake())->toBeTrue()
            ->and(app(Messages::class)->stop())->toBeTrue()
            ->and(app(Messages::class)->status(1))->toBe(['online' => true, 'can_send' => true, 'service' => 'no_signal', 'sent_today' => 3, 'failed_today' => 1]);

        $this->bridge->assertCalled('Messages.Status', fn ($p) => $p === ['slot' => 1]);
    });

    it('draws the native faces of the two SMS screens', function () {
        expect(NativeRoutes::path('messages'))->toBe('/messages')
            ->and(NativeRoutes::path('messages_accept'))->toBe('/messages/accept')
            ->and(NativeRoutes::FACES['messages'])->toBe(Faces\Messages::class)
            ->and(NativeRoutes::FACES['messages_accept'])->toBe(Faces\MessagesAccept::class);

        TestDevice::bind();
        NativeRoutes::register();

        Native::visit('/messages')->assertScreen(Faces\Messages::class);
        Native::visit('/messages/accept')->assertScreen(Faces\MessagesAccept::class);
    });
});

describe('Declared and built', function () {
    it('declares the bridge functions and events', function () {
        $plugin = json_decode((string) file_get_contents(dirname(__DIR__, 2).'/nativephp.json'), true);
        $functions = array_column($plugin['bridge_functions'], 'android', 'name');

        foreach (['Sms.Send' => 'SmsFunctions.Send', 'Sms.Sims' => 'SmsFunctions.Sims', 'Sms.Permission' => 'SmsFunctions.Permission', 'Messages.Start' => 'MessagesFunctions.Start', 'Messages.Work' => 'MessagesFunctions.Work', 'Messages.Stop' => 'MessagesFunctions.Stop', 'Messages.Status' => 'MessagesFunctions.Status'] as $name => $class) {
            expect($functions[$name] ?? null)->toBe('com.rishadblack.plugins.sylloo_app_native.'.$class);
        }

        expect($plugin['events'])->toContain(SmsAnswered::class)->toContain(SmsPermissionAnswered::class)
            // No SMS permission in nativephp.json: NativePHP would merge it into every build.
            ->and($plugin['android']['permissions'])->not->toContain('android.permission.SEND_SMS')
            ->not->toContain('android.permission.READ_PHONE_STATE');
    });

    it('wakes on the push {wake: messages} and keeps no number or text after sending', function () {
        $root = dirname(__DIR__, 2).'/resources/android';
        $push = (string) file_get_contents($root.'/PushFunctions.kt');
        $kotlin = (string) file_get_contents($root.'/MessagesFunctions.kt').file_get_contents($root.'/SmsFunctions.kt');

        expect($push)->toContain('if (MessagesRules.isWake(data)) {')->toContain('MessagesWork.wake(applicationContext)')
            ->and($kotlin)->toContain('class SmsResultReceiver')->toContain('class MessagesAlarmReceiver')
            ->not->toMatch('/Log\.[a-z]\([^)]*\b(to|text)\b/')
            ->not->toContain('putString("to"')
            ->not->toContain('putString("text"');
    });

    describe('the build hook', function () {
        beforeEach(function () {
            $this->build = sys_get_temp_dir().'/sylloo-native-sms-'.bin2hex(random_bytes(4));
            mkdir($this->build.'/app/src/main', 0755, true);
            $made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
            file_put_contents($this->build.'/app/src/main/AndroidManifest.xml', $made);
            $this->original = BackupManifest::apply($made);
            config()->set('sylloo-app-native.push.google_services', '');

            $this->runHook = function (bool $sms): string {
                config()->set('sylloo-app-native.features', ['print' => false, 'scan_keys' => false, 'scan_camera' => false, 'sms_send' => $sms]);

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

        it('has no SEND_SMS and no READ_PHONE_STATE when the switch is off', function () {
            $manifest = ($this->runHook)(false);

            expect($manifest)->toBe($this->original)
                ->not->toContain('SEND_SMS')
                ->not->toContain('READ_PHONE_STATE')
                ->not->toContain('SmsResultReceiver')
                ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
                ->toContain('<bool name="sylloo_feature_sms_send">false</bool>');
        });

        it('adds them once when on, the receivers inside the application, and takes everything out again', function () {
            $manifest = ($this->runHook)(true);
            ($this->runHook)(true);
            $twice = (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');
            $application = strpos($manifest, '<application');
            $end = strpos($manifest, '</application>');

            expect($twice)->toBe($manifest)
                ->and(substr_count($manifest, 'android.permission.SEND_SMS'))->toBe(1)
                ->and(substr_count($manifest, 'android.permission.READ_PHONE_STATE'))->toBe(1)
                ->and($manifest)->toContain('<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />')
                ->toContain('<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />')
                ->toContain('<uses-feature android:name="android.hardware.telephony" android:required="false" />')
                ->and(strpos($manifest, 'SEND_SMS'))->toBeLessThan($application)
                ->and(strpos($manifest, 'SmsResultReceiver'))->toBeGreaterThan($application)->toBeLessThan($end)
                ->and(strpos($manifest, 'android:foregroundServiceType="dataSync" tools:node="merge"'))->toBeGreaterThan($application)->toBeLessThan($end)
                ->and(simplexml_load_string($manifest))->not->toBeFalse()
                ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
                ->toContain('<bool name="sylloo_feature_sms_send">true</bool>')
                ->and(($this->runHook)(false))->toBe($this->original);
        });

        it('never touches READ_PHONE_STATE declared by someone else', function () {
            $foreign = str_replace('<application', "<uses-permission android:name=\"android.permission.READ_PHONE_STATE\" />\n\n    <application", $this->original);

            expect(FeatureManifest::apply($foreign, ['sms_send' => false]))->toBe($foreign)
                ->and(FeatureManifest::apply(FeatureManifest::apply($foreign, ['sms_send' => true]), ['sms_send' => false]))->toBe($foreign);
        });
    });
});
