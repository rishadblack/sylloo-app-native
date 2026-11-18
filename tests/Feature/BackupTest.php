<?php

use Illuminate\Filesystem\Filesystem;
use Illuminate\Support\Facades\Artisan;
use Rishadblack\SyllooAppNative\Support\BackupManifest;

/*
 * App backup off (nativephp-plan.md §7.10, §10): the build hook turns Android's cloud backup and the device-to-device
 * transfer off for every app, whatever NativePHP writes, so the phone's private data never leaves it.
 */

beforeEach(function () {
    $this->build = sys_get_temp_dir().'/sylloo-native-backup-'.bin2hex(random_bytes(4));
    mkdir($this->build.'/app/src/main', 0755, true);
    $this->made = (string) file_get_contents(dirname(__DIR__).'/Fixtures/AndroidManifest.xml');
    file_put_contents($this->build.'/app/src/main/AndroidManifest.xml', $this->made);
    config()->set('sylloo-app-native.push.google_services', '');

    $this->runHook = function (): string {
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

it('turns NativePHP\'s allowBackup="true" off and names the two rule files, once, keeping the rest', function () {
    expect($this->made)->toContain('android:allowBackup="true"');

    $manifest = ($this->runHook)();
    $twice = ($this->runHook)();
    $application = simplexml_load_string($manifest)->application->attributes('http://schemas.android.com/apk/res/android');

    expect($twice)->toBe($manifest)
        ->and($manifest)->not->toContain('android:allowBackup="true"')
        ->and(substr_count($manifest, 'android:allowBackup='))->toBe(1)
        ->and((string) $application['allowBackup'])->toBe('false')
        ->and((string) $application['fullBackupContent'])->toBe('@xml/sylloo_backup_rules')
        ->and((string) $application['dataExtractionRules'])->toBe('@xml/sylloo_data_extraction_rules')
        ->and((string) $application['label'])->toBe('Sylloo App')
        ->and((string) $application['theme'])->toBe('@style/Theme.AndroidPHP')
        ->and($manifest)->toContain('android:name="com.nativephp.mobile.ui.MainActivity"');

    // Nothing outside <application> changes.
    expect(strstr($manifest, '<application', true))->toBe(strstr($this->made, '<application', true));
});

it('writes rule files that exclude every domain from the cloud backup and the transfer to a new phone', function () {
    ($this->runHook)();

    $full = simplexml_load_file($this->build.'/app/src/main/res/xml/sylloo_backup_rules.xml');
    $extraction = simplexml_load_file($this->build.'/app/src/main/res/xml/sylloo_data_extraction_rules.xml');
    $domains = fn (SimpleXMLElement $node) => array_map(fn ($exclude) => (string) $exclude['domain'].':'.(string) $exclude['path'], iterator_to_array($node->exclude, false));
    $all = array_map(fn ($domain) => $domain.':.', BackupManifest::DOMAINS);

    expect($full->getName())->toBe('full-backup-content')
        ->and($domains($full))->toBe($all)
        ->and($extraction->getName())->toBe('data-extraction-rules')
        ->and($domains($extraction->{'cloud-backup'}))->toBe($all)
        ->and($domains($extraction->{'device-transfer'}))->toBe($all)
        ->and(isset($full->include) || isset($extraction->{'cloud-backup'}->include))->toBeFalse();
});

it('replaces earlier backup values a manifest may carry', function () {
    $other = str_replace('android:allowBackup="true"', 'android:allowBackup="true" android:fullBackupContent="@xml/backup" android:dataExtractionRules="@xml/rules"', $this->made);

    $manifest = BackupManifest::apply($other);

    expect($manifest)->not->toContain('@xml/backup"')
        ->not->toContain('@xml/rules"')
        ->and(BackupManifest::apply($manifest))->toBe($manifest)
        ->and(simplexml_load_string($manifest))->not->toBeFalse();
});
