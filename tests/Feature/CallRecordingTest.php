<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppNative\CallRecordings;
use Rishadblack\SyllooAppNative\Events\CallsFolderAnswered;
use Rishadblack\SyllooAppNative\Support\BackupManifest;

/*
 * Call recordings (crm-call-recording-plan.md CR3): Calls.Folder, Calls.FolderStatus, Calls.Recording and
 * Calls.RecordingDrop, off unless the app turns on features.calls_recording, and never a permission in the build. Which
 * file is a call's recording is tested in Kotlin (RecordingRulesTest).
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge();
    $this->sha = hash('sha256', 'sound');
});

it('asks the phone nothing while recordings are off', function () {
    $recordings = app(CallRecordings::class);

    expect($recordings->enabled())->toBeFalse()
        ->and($recordings->chooseFolder('crm-folder'))->toBeFalse()
        ->and($recordings->forgetFolder())->toBeFalse()
        ->and($recordings->status())->toBe(['on' => false, 'folder' => false, 'name' => null])
        ->and($recordings->recording('7:41-1', 1_772_441_400_000, 245)->status)->toBe('not_set_up')
        ->and($recordings->drop('7:41-1'))->toBeFalse();

    $this->bridge->assertNothingCalled();
});

describe('switched on', function () {
    beforeEach(function () {
        config()->set('sylloo-app-native.features.calls_recording', true);
    });

    it('opens the folder chooser, reads the folder and lets it go', function () {
        $this->bridge->respondTo('Calls.Folder', ['status' => 'asking'])
            ->respondTo('Calls.FolderStatus', ['on' => true, 'folder' => true, 'name' => ' Recordings/Call ']);

        expect(app(CallRecordings::class)->chooseFolder('crm-folder'))->toBeTrue()
            ->and(app(CallRecordings::class)->status())->toBe(['on' => true, 'folder' => true, 'name' => 'Recordings/Call']);

        $this->bridge->assertCalled('Calls.Folder', fn ($params) => $params === ['id' => 'crm-folder']);

        $this->bridge->respondTo('Calls.Folder', ['status' => 'forgotten']);
        expect(app(CallRecordings::class)->forgetFolder())->toBeTrue();
        $this->bridge->assertCalled('Calls.Folder', fn ($params) => $params === ['forget' => true]);
    });

    it('hands the phone the call and passes on only a copy with a good shape', function () {
        $this->bridge->respondTo('Calls.Recording', ['status' => 'found', 'path' => '/data/user/0/app.sylloo.app/files/sylloo_recordings/7_41-1.mp4', 'extension' => 'mp4', 'size' => 5120, 'sha256' => $this->sha]);

        $file = app(CallRecordings::class)->recording('7:41-1', 1_772_441_400_000, 245, '711000111', 60, 40 * 1024 * 1024);

        expect($file->found())->toBeTrue()
            ->and($file->extension)->toBe('mp4')
            ->and($file->size)->toBe(5120)
            ->and($file->sha256)->toBe($this->sha);

        $this->bridge->assertCalled('Calls.Recording', fn ($params) => $params === [
            'call_id' => '7:41-1', 'started_at_ms' => 1_772_441_400_000, 'duration_seconds' => 245, 'key' => '711000111',
            'max_minutes' => 60, 'max_bytes' => 40 * 1024 * 1024,
        ]);
    });

    it('refuses a copy with a bad shape and passes on the phone\'s reasons', function (array $answer, string $status) {
        $this->bridge->respondTo('Calls.Recording', $answer);

        expect(app(CallRecordings::class)->recording('7:41-1', 1_772_441_400_000, 245)->status)->toBe($status);
    })->with([
        'an AMR sound' => [['status' => 'found', 'path' => '/x/7_41-1.amr', 'extension' => 'amr', 'size' => 10, 'sha256' => str_repeat('a', 64)], 'failed'],
        'no hash' => [['status' => 'found', 'path' => '/x/7_41-1.mp4', 'extension' => 'mp4', 'size' => 10], 'failed'],
        'a path out' => [['status' => 'found', 'path' => '/x/../7_41-1.mp4', 'extension' => 'mp4', 'size' => 10, 'sha256' => str_repeat('a', 64)], 'failed'],
        'none' => [['status' => 'none'], 'none'],
        'ambiguous' => [['status' => 'ambiguous'], 'ambiguous'],
        'no folder' => [['status' => 'no_folder'], 'no_folder'],
        'nonsense' => [['status' => 'weird'], 'failed'],
    ]);

    it('tells which answers will not change, so the app stops asking', function () {
        $this->bridge->respondTo('Calls.Recording', ['status' => 'too_long']);
        expect(app(CallRecordings::class)->recording('7:41-1', 1_772_441_400_000, 245)->final())->toBeTrue();

        $this->bridge->respondTo('Calls.Recording', ['status' => 'none']);
        expect(app(CallRecordings::class)->recording('7:41-1', 1_772_441_400_000, 245)->final())->toBeFalse();
    });

    it('never asks for a bad call id and drops the copy of a good one', function () {
        $this->bridge->respondTo('Calls.RecordingDrop', ['dropped' => true]);

        expect(app(CallRecordings::class)->recording('../etc', 1_772_441_400_000, 245)->status)->toBe('invalid')
            ->and(app(CallRecordings::class)->drop('../etc'))->toBeFalse()
            ->and(app(CallRecordings::class)->drop('7:41-1'))->toBeTrue();

        $this->bridge->assertNotCalled('Calls.Recording');
    });

    it('hears the folder chooser\'s answer as an event', function () {
        $event = new CallsFolderAnswered('picked', 'crm-folder', 'Recordings/Call');

        expect($event->status)->toBe('picked')->and($event->name)->toBe('Recordings/Call');
    });
});

it('reads the folder only through Android\'s chooser: no storage permission, never deletes the person\'s files', function () {
    $kotlin = (string) file_get_contents(dirname(__DIR__, 2).'/resources/android/RecordingFunctions.kt');

    expect($kotlin)->toContain('ACTION_OPEN_DOCUMENT_TREE')
        ->toContain('takePersistableUriPermission')
        ->not->toContain('READ_EXTERNAL_STORAGE')
        ->not->toContain('MANAGE_EXTERNAL_STORAGE')
        ->not->toContain('READ_MEDIA_AUDIO')
        ->not->toContain('DocumentsContract.deleteDocument')
        ->not->toContain('Log.i(');
});

it('adds nothing to the build but its switch', function () {
    $build = sys_get_temp_dir().'/sylloo-native-recordings-'.bin2hex(random_bytes(4));
    mkdir($build.'/app/src/main', 0755, true);
    $made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
    file_put_contents($build.'/app/src/main/AndroidManifest.xml', $made);
    config()->set('sylloo-app-native.push.google_services', '');

    try {
        foreach ([true, false] as $on) {
            config()->set('sylloo-app-native.features', ['calls_recording' => $on]);

            expect(Artisan::call('nativephp:sylloo-app-native:copy-assets', [
                '--platform' => 'android',
                '--build-path' => $build,
                '--plugin-path' => dirname(__DIR__, 2),
                '--app-id' => 'app.sylloo.app',
            ]))->toBe(0)
                ->and((string) file_get_contents($build.'/app/src/main/AndroidManifest.xml'))->toBe(BackupManifest::apply($made))
                ->and(file_get_contents($build.'/app/src/main/res/values/sylloo_features.xml'))
                ->toContain('<bool name="sylloo_feature_calls_recording">'.($on ? 'true' : 'false').'</bool>');
        }
    } finally {
        (new Filesystem)->deleteDirectory($build);
    }
});
