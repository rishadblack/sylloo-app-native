<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppNative\Notices;
use Rishadblack\SyllooAppNative\Support\BackupManifest;

/*
 * Timed notices (crm-employee-app-plan.md §4.4): Notice.Schedule / Notice.Cancel / Notice.Opened, off unless the app
 * turns on features.notice_schedule, with the exact alarm, the restart receiver and the notice's screens in the build
 * only then. The rules of a notice itself are tested in Kotlin (NoticeRulesTest).
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
    $this->notice = [
        'id' => 'crm-f-1',
        'group' => 'crm',
        'channel' => 'crm_reminders',
        'channel_name' => 'Reminders',
        'at_ms' => 1_795_000_000_000,
        'title' => 'Call Rahim Store',
        'body' => 'Show samples · 15:00',
        'open' => 'lead',
        'open_id' => 'l-1',
        'call' => ['label' => 'Call'],
        'later' => ['label' => 'Later', 'options' => [['key' => '15m', 'label' => 'In 15 min', 'minutes' => 15]]],
    ];
});

it('answers not_set_up or nothing without asking the phone while switched off', function () {
    $notices = app(Notices::class);

    expect($notices->enabled())->toBeFalse()
        ->and($notices->schedule($this->notice))->toBe('not_set_up')
        ->and($notices->cancel('crm-f-1'))->toBeFalse()
        ->and($notices->cancelGroup('crm'))->toBeFalse()
        ->and($notices->opened())->toBeNull();

    $this->bridge->assertNothingCalled();
});

describe('switched on', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.notice_schedule', true);
    });

    it('sets a notice with its fields and passes on the phone\'s refusal', function () {
        $this->bridge->respondTo('Notice.Schedule', ['scheduled' => true, 'exact' => true]);

        expect(app(Notices::class)->schedule($this->notice))->toBe('scheduled');
        $this->bridge->assertCalled('Notice.Schedule', fn ($params) => $params['id'] === 'crm-f-1' && $params['at_ms'] === 1_795_000_000_000 && $params['call'] === ['label' => 'Call']);

        $this->bridge->respondTo('Notice.Schedule', ['scheduled' => false, 'reason' => 'invalid']);
        expect(app(Notices::class)->schedule($this->notice))->toBe('invalid');
    });

    it('refuses a notice without a good id, time or screen before asking the phone', function () {
        foreach ([['id' => 'bad id'], ['at_ms' => '15:00'], ['open' => 'Lead Card']] as $bad) {
            expect(app(Notices::class)->schedule([...$this->notice, ...$bad]))->toBe('invalid');
        }

        $this->bridge->assertNotCalled('Notice.Schedule');
    });

    it('cancels one notice or a whole group', function () {
        $this->bridge->respondTo('Notice.Cancel', ['cancelled' => true, 'count' => 2]);

        expect(app(Notices::class)->cancel('crm-f-1'))->toBeTrue()
            ->and(app(Notices::class)->cancelGroup('crm'))->toBeTrue();
        $this->bridge->assertCalled('Notice.Cancel', fn ($params) => ($params['group'] ?? null) === 'crm');
    });

    it('tells what a tapped notice opens, once, with only good shapes', function () {
        $this->bridge->respondTo('Notice.Opened', ['found' => true, 'open' => 'lead', 'open_id' => 'l-1', 'action' => 'call']);
        expect(app(Notices::class)->opened())->toBe(['open' => 'lead', 'open_id' => 'l-1', 'action' => 'call']);

        $this->bridge->respondTo('Notice.Opened', ['found' => true, 'open' => 'lead', 'open_id' => '../x y', 'action' => 'dial']);
        expect(app(Notices::class)->opened())->toBe(['open' => 'lead', 'open_id' => null, 'action' => 'open']);

        $this->bridge->respondTo('Notice.Opened', ['found' => false]);
        expect(app(Notices::class)->opened())->toBeNull();
    });
});

it('keeps phone numbers out of what the phone keeps: Call only opens the app', function () {
    $kotlin = (string) file_get_contents(dirname(__DIR__, 2).'/resources/android/NoticeFunctions.kt');

    expect($kotlin)->toContain('class NoticeRestartReceiver')->toContain('class NoticeLaterActivity')
        ->not->toContain('ACTION_DIAL')
        ->not->toContain('ACTION_CALL')
        ->not->toContain('"number"');
});

describe('the build hook', function () {
    beforeEach(function () {
        $this->build = sys_get_temp_dir().'/sylloo-native-notice-'.bin2hex(random_bytes(4));
        mkdir($this->build.'/app/src/main', 0755, true);
        $made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
        file_put_contents($this->build.'/app/src/main/AndroidManifest.xml', $made);
        $this->original = BackupManifest::apply($made);
        config()->set('sylloo-app-native.push.google_services', '');

        $this->runHook = function (bool $notices, bool $sms = false): string {
            config()->set('sylloo-app-native.features', ['print' => false, 'scan_keys' => false, 'scan_camera' => false, 'sms_send' => $sms, 'notice_schedule' => $notices]);

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

    it('has no alarm, no restart receiver and no notice screens when off', function () {
        expect(($this->runHook)(false))->toBe($this->original)
            ->not->toContain('EXACT_ALARM')
            ->not->toContain('RECEIVE_BOOT_COMPLETED')
            ->not->toContain('Notice')
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_notice_schedule">false</bool>');
    });

    it('adds them once when on, the receivers and screens inside the application, and takes everything out again', function () {
        $manifest = ($this->runHook)(true);
        ($this->runHook)(true);
        $twice = (string) file_get_contents($this->build.'/app/src/main/AndroidManifest.xml');
        $application = strpos($manifest, '<application');
        $end = strpos($manifest, '</application>');

        expect($twice)->toBe($manifest)
            ->and(substr_count($manifest, 'android.permission.USE_EXACT_ALARM'))->toBe(1)
            ->and(substr_count($manifest, 'android.permission.RECEIVE_BOOT_COMPLETED'))->toBe(1)
            ->and(strpos($manifest, 'USE_EXACT_ALARM'))->toBeLessThan($application)
            ->and(strpos($manifest, 'NoticeRestartReceiver'))->toBeGreaterThan($application)->toBeLessThan($end)
            ->and(strpos($manifest, 'NoticeLaterActivity'))->toBeGreaterThan($application)->toBeLessThan($end)
            ->and(simplexml_load_string($manifest))->not->toBeFalse()
            ->and(file_get_contents($this->build.'/app/src/main/res/values/sylloo_features.xml'))
            ->toContain('<bool name="sylloo_feature_notice_schedule">true</bool>')
            ->and(($this->runHook)(false))->toBe($this->original);
    });

    it('writes the exact alarm only once when phone SMS is on too, and keeps it when only one is switched off', function () {
        $both = ($this->runHook)(true, true);

        expect(substr_count($both, 'android.permission.USE_EXACT_ALARM'))->toBe(1)
            ->and(substr_count($both, 'android.permission.SCHEDULE_EXACT_ALARM'))->toBe(1)
            ->and(simplexml_load_string($both))->not->toBeFalse()
            ->and(substr_count(($this->runHook)(true, false), 'android.permission.USE_EXACT_ALARM'))->toBe(1)
            ->and(substr_count(($this->runHook)(false, true), 'android.permission.USE_EXACT_ALARM'))->toBe(1)
            ->and(($this->runHook)(false, false))->toBe($this->original);
    });
});
