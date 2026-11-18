<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppNative\Calls;
use Rishadblack\SyllooAppNative\Push;
use Rishadblack\SyllooAppNative\Support\BackupManifest;

/*
 * Work calls (crm-call-recording-plan.md CR2): Calls.Recent, Calls.Ended, Calls.Permission, Calls.Status, each off
 * unless the app turns on features.calls_recent / features.calls_ended, with READ_CALL_LOG, READ_PHONE_STATE and the
 * receiver in the build only then. The rules of a work call are tested in Kotlin (CallRulesTest).
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
    $this->workCall = ['call_id' => '41-1795000060000', 'number' => '+880 1711-000111', 'key' => '711000111', 'direction' => 'out', 'started_at_ms' => 1_795_000_060_000, 'duration_seconds' => 240];
});

it('asks the phone nothing while both switches are off', function () {
    $calls = app(Calls::class);

    expect($calls->enabled())->toBeFalse()
        ->and($calls->endedEnabled())->toBeFalse()
        ->and($calls->recent(['711000111'])->status)->toBe('not_set_up')
        ->and($calls->askPermission('crm-calls'))->toBeFalse()
        ->and($calls->watch())->toBeFalse()
        ->and($calls->stopWatching())->toBeFalse()
        ->and($calls->status())->toBe(['recent' => false, 'ended' => false, 'call_log' => false, 'phone_state' => false, 'watching' => false]);

    $this->bridge->assertNothingCalled();
});

it('makes the compare key of a number as the phone does', function () {
    expect(Calls::key('+880 1711-000111'))->toBe('711000111')
        ->and(Calls::key('01711000111', 6))->toBe('000111')
        ->and(Calls::key('16216'))->toBeNull()
        ->and(Calls::key(null))->toBeNull();
});

describe('switched on', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.calls_recent', true);
        config()->set('sylloo-app-native.features.calls_ended', true);
    });

    it('hands the phone only good keys and passes on only work calls with a good shape', function () {
        $this->bridge->respondTo('Calls.Recent', ['status' => 'ok', 'last_id' => 44, 'more' => true, 'calls' => [
            $this->workCall,
            [...$this->workCall, 'call_id' => '42-1', 'number' => '01911999999', 'key' => '911999999'],
            [...$this->workCall, 'call_id' => 'bad id!'],
            [...$this->workCall, 'call_id' => '43-1', 'key' => '811000222'],
        ]]);

        $read = app(Calls::class)->recent(['711000111', '71100011', 'x11000111', '711000111'], 9, 40, 1_795_000_000_000, 500);

        expect($read->ok())->toBeTrue()
            ->and(array_map(fn ($call) => $call->callId, $read->calls))->toBe(['41-1795000060000'])
            ->and($read->calls[0]->direction)->toBe('out')
            ->and($read->calls[0]->durationSeconds)->toBe(240)
            ->and($read->calls[0]->startedAt()->toIso8601String())->toBe('2026-11-18T11:07:40+00:00')
            ->and($read->lastId)->toBe(44)
            ->and($read->more)->toBeTrue();

        $this->bridge->assertCalled('Calls.Recent', fn ($params) => $params['keys'] === ['711000111'] && $params['digits'] === 9
            && $params['after_id'] === 40 && $params['since_ms'] === 1_795_000_000_000 && $params['limit'] === 100);
    });

    it('passes on the phone\'s no-permission and failure without calls', function () {
        $this->bridge->respondTo('Calls.Recent', ['status' => 'no_permission']);
        expect(app(Calls::class)->recent(['711000111'])->status)->toBe('no_permission');

        $this->bridge->respondTo('Calls.Recent', ['status' => 'weird']);
        expect(app(Calls::class)->recent(['711000111'])->calls)->toBe([]);
    });

    it('asks for the permission, reads the status and arms or disarms the wake', function () {
        $this->bridge->respondTo('Calls.Permission', ['status' => 'asking'])
            ->respondTo('Calls.Status', ['recent' => true, 'ended' => true, 'call_log' => true, 'phone_state' => false, 'watching' => true])
            ->respondTo('Calls.Ended', ['watching' => true]);

        expect(app(Calls::class)->askPermission('crm-calls'))->toBeTrue()
            ->and(app(Calls::class)->status())->toBe(['recent' => true, 'ended' => true, 'call_log' => true, 'phone_state' => false, 'watching' => true])
            ->and(app(Calls::class)->watch())->toBeTrue()
            ->and(app(Calls::class)->stopWatching())->toBeTrue();

        $this->bridge->assertCalled('Calls.Permission', fn ($params) => $params['id'] === 'crm-calls');
        $this->bridge->assertCalled('Calls.Ended', fn ($params) => $params === ['on' => true]);
        $this->bridge->assertCalled('Calls.Ended', fn ($params) => $params === ['on' => false]);
    });
});

it('never reads a contact name or dials: the call list is read for its number, type, time and length only', function () {
    $kotlin = (string) file_get_contents(dirname(__DIR__, 2).'/resources/android/CallFunctions.kt');

    expect($kotlin)->toContain('class CallEndedReceiver')
        ->not->toContain('CACHED_NAME')
        ->not->toContain('ContactsContract')
        ->not->toContain('ACTION_CALL')
        ->not->toContain('Log.i(');
});

describe('push with a screen and buttons', function () {
    it('tells the screen, the record and the button of a tapped push, once, with only good shapes', function () {
        $this->bridge->respondTo('Push.Opened', ['found' => true, 'open' => 'lead', 'open_id' => 'l-1', 'action' => 'call']);
        expect(app(Push::class)->tapped())->toBe(['url' => null, 'open' => 'lead', 'open_id' => 'l-1', 'action' => 'call']);

        $this->bridge->respondTo('Push.Opened', ['found' => true, 'url' => 'https://demo.sylloo.app/employee', 'open' => 'Lead Card', 'open_id' => 'l-1']);
        expect(app(Push::class)->tapped())->toBe(['url' => 'https://demo.sylloo.app/employee', 'open' => null, 'open_id' => null, 'action' => null])
            ->and(app(Push::class)->opened())->toBe('https://demo.sylloo.app/employee');

        $this->bridge->respondTo('Push.Opened', ['found' => false]);
        expect(app(Push::class)->tapped())->toBeNull();
    });

    it('hands the phone the app\'s button labels, good keys only', function () {
        $this->bridge->respondTo('Push.Labels', ['saved' => 1]);

        expect(app(Push::class)->labels(['crm.call' => ' Call ', 'Bad Key' => 'x', 'crm.empty' => '']))->toBeTrue();
        $this->bridge->assertCalled('Push.Labels', fn ($params) => $params['labels'] === ['crm.call' => 'Call']);
    });
});

describe('the build hook', function () {
    beforeEach(function () {
        $this->build = sys_get_temp_dir().'/sylloo-native-calls-'.bin2hex(random_bytes(4));
        mkdir($this->build.'/app/src/main', 0755, true);
        $made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
        file_put_contents($this->build.'/app/src/main/AndroidManifest.xml', $made);
        $this->original = BackupManifest::apply($made);
        config()->set('sylloo-app-native.push.google_services', '');

        $this->runHook = function (bool $recent, bool $ended, bool $sms = false): string {
            config()->set('sylloo-app-native.features', ['print' => false, 'scan_keys' => false, 'scan_camera' => false, 'sms_send' => $sms, 'notice_schedule' => false, 'calls_recent' => $recent, 'calls_ended' => $ended]);

            expect(Artisan::call('nativephp:sylloo-app-native:copy-assets', [
                '--platform' => 'android',
                '--build-path' => $this->build,
                '--plugin-path' => dirname(__DIR__, 2),
                '--app-id' => 'app.sylloo.app',
            ]))->toBe(0);

            return (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');
        };
    });

    afterEach(function () {
        (new Filesystem)->deleteDirectory($this->build);
    });

    it('has no call permission and no receiver when both are off', function () {
        expect(($this->runHook)(false, false))->toBe($this->original)
            ->not->toContain('READ_CALL_LOG')
            ->not->toContain('READ_PHONE_STATE')
            ->not->toContain('CallEndedReceiver')
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_calls_recent">false</bool>')
            ->toContain('<bool name="sylloo_feature_calls_ended">false</bool>');
    });

    it('adds each job\'s own lines only, once, and takes them out again', function () {
        $recent = ($this->runHook)(true, false);

        expect($recent)->toContain('android.permission.READ_CALL_LOG')
            ->not->toContain('READ_PHONE_STATE')
            ->not->toContain('CallEndedReceiver');

        $both = ($this->runHook)(true, true);
        ($this->runHook)(true, true);
        $twice = (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');
        $application = strpos($both, '<application');

        expect($twice)->toBe($both)
            ->and(substr_count($both, 'android.permission.READ_CALL_LOG'))->toBe(1)
            ->and(substr_count($both, 'android.permission.READ_PHONE_STATE'))->toBe(1)
            ->and(strpos($both, 'CallEndedReceiver'))->toBeGreaterThan($application)->toBeLessThan(strpos($both, '</application>'))
            ->and($both)->toContain('android.intent.action.PHONE_STATE')
            ->and(simplexml_load_string($both))->not->toBeFalse()
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_calls_ended">true</bool>')
            ->and(($this->runHook)(false, false))->toBe($this->original);
    });

    it('writes READ_PHONE_STATE once when phone SMS has it too, and keeps it when only one is switched off', function () {
        $both = ($this->runHook)(false, true, true);

        expect(substr_count($both, 'android.permission.READ_PHONE_STATE'))->toBe(1)
            ->and(simplexml_load_string($both))->not->toBeFalse()
            ->and(substr_count(($this->runHook)(false, true, false), 'android.permission.READ_PHONE_STATE'))->toBe(1)
            ->and(substr_count(($this->runHook)(false, false, true), 'android.permission.READ_PHONE_STATE'))->toBe(1)
            ->and(($this->runHook)(false, false, false))->toBe($this->original);
    });
});
