<?php

/**
 * Manifest and file checks for sylloo-app-native. Run with: ./vendor/bin/pest
 */
beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifest = json_decode(file_get_contents($this->pluginPath.'/nativephp.json'), true);
    $this->kotlin = implode("\n", array_map('file_get_contents', glob($this->pluginPath.'/resources/android/*.kt')));
});

it('has a valid manifest with the jobs of plan §4.6', function () {
    expect($this->manifest)->toBeArray()
        ->and($this->manifest['namespace'])->toBe('SyllooAppNative')
        ->and(array_column($this->manifest['bridge_functions'], 'name'))->toBe([
            'Clock.Elapsed', 'Secret.Set', 'Secret.Get', 'Secret.Delete', 'Lock.Status', 'Lock.Prompt', 'Location.Fix',
            'Camera.Take', 'Qr.Scan', 'Push.Permission', 'Push.Token', 'Push.Opened', 'Push.Labels', 'Sync.Schedule', 'Sync.Cancel',
            'Update.Install', 'Window.Clear', 'Print.Receipt', 'Print.Printers', 'Print.Drawer', 'Scan.Keys',
            'Scan.Camera', 'Scan.Answer', 'Sms.Send', 'Sms.Sims', 'Sms.Permission', 'Messages.Start', 'Messages.Work',
            'Messages.Stop', 'Messages.Status', 'Notice.Schedule', 'Notice.Cancel', 'Notice.Opened', 'Calls.Recent',
            'Calls.Ended', 'Calls.Permission', 'Calls.Status', 'Calls.Folder', 'Calls.FolderStatus', 'Calls.Recording', 'Calls.RecordingDrop',
        ]);
});

it('keeps the till jobs Bluetooth and USB lines out of the manifest (the build hook adds them only when switched on)', function () {
    $android = json_encode($this->manifest['android']);

    expect($android)->not->toContain('BLUETOOTH')
        ->not->toContain('usb');
});

it('gives every job the context only, so the background run can use them with no screen', function () {
    foreach ($this->manifest['bridge_functions'] as $function) {
        expect($function['android_params'] ?? null)->toBe(['context'], $function['name']);
    }
});

it('declares the permissions and only free, open dependencies', function () {
    expect($this->manifest['android']['permissions'])->toEqualCanonicalizing([
        'android.permission.USE_BIOMETRIC', 'android.permission.CAMERA', 'android.permission.ACCESS_FINE_LOCATION',
        'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.POST_NOTIFICATIONS',
        'android.permission.REQUEST_INSTALL_PACKAGES',
    ])->and($this->manifest['android']['dependencies']['implementation'])->toBe([
        'androidx.biometric:biometric:1.1.0',
        'com.google.guava:guava:33.3.1-android',
        'com.google.zxing:core:3.5.3',
        'androidx.work:work-runtime-ktx:2.10.0',
        'com.google.firebase:firebase-messaging:24.1.0',
        'androidx.camera:camera-core:1.4.1',
        'androidx.camera:camera-camera2:1.4.1',
        'androidx.camera:camera-lifecycle:1.4.1',
        'androidx.camera:camera-view:1.4.1',
    ])->and($this->manifest['android']['permissions'])->not->toContain('android.permission.ACCESS_BACKGROUND_LOCATION');
});

it('has a Kotlin class for every Android bridge function', function () {
    foreach ($this->manifest['bridge_functions'] as $function) {
        $parts = explode('.', $function['android']);
        $class = array_pop($parts);
        $object = array_pop($parts);

        expect($this->kotlin)->toContain('package '.implode('.', $parts))
            ->toContain("object {$object}")
            ->toMatch('/object '.$object.' \{.*?class '.$class.'\(/s');
    }
});

it('has the activities and the messaging service in its Kotlin, none of them open to other apps', function () {
    foreach ([...$this->manifest['android']['activities'], ...$this->manifest['android']['services']] as $component) {
        expect($this->kotlin)->toContain('class '.ltrim($component['name'], '.').' ')
            ->and($component['exported'])->toBeFalse();
    }
});

it('adds the cloud window element with its renderer and events', function () {
    $window = $this->manifest['components'][0];

    expect($window['type'])->toBe('sylloo_window')
        ->and(class_exists($window['element']))->toBeTrue()
        ->and(class_exists($window['blade']))->toBeTrue()
        ->and($window['element_events'])->toBe(['leave', 'ended', 'navigated', 'failed'])
        ->and($this->kotlin)->toContain('object '.substr(strrchr($window['android_renderer'], '.'), 1));
});

it('lists every event class it sends from Kotlin', function () {
    preg_match_all('/SyllooEvents\.send\("(\w+)"/', $this->kotlin, $sent);

    foreach (array_unique($sent[1]) as $event) {
        expect($this->manifest['events'])->toContain('Rishadblack\\SyllooAppNative\\Events\\'.$event);
    }

    foreach ($this->manifest['events'] as $event) {
        expect(class_exists($event))->toBeTrue($event);
    }
});

it('keeps the JVM tests out of the app code', function () {
    expect(glob($this->pluginPath.'/resources/android/*Test.kt'))->toBe([])
        ->and(glob($this->pluginPath.'/resources/android-tests/*Test.kt'))->not->toBe([]);
});

it('is a nativephp-plugin composer package', function () {
    $composer = json_decode(file_get_contents($this->pluginPath.'/composer.json'), true);

    expect($composer['type'])->toBe('nativephp-plugin')
        ->and($composer['extra']['nativephp']['manifest'])->toBe('nativephp.json');
});
