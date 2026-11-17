<?php

namespace Rishadblack\SyllooAppNative\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;
use Rishadblack\SyllooAppNative\Support\FirebaseValues;

/**
 * copy_assets build hook (Android):
 *
 * - Firebase: turns the app's `google-services.json` (outside git; config `sylloo-app-native.push.google_services`)
 *   into `res/values/sylloo_firebase.xml`, so Firebase starts without Google's gradle plugin. No file: no values, and
 *   push answers `not_set_up`.
 * - The pure-Kotlin JVM tests (`resources/android-tests`) and their shared cases (`tests/Fixtures/window-cases.json`)
 *   go to the native project's unit tests (`gradlew testDebugUnitTest`). They are not app code, so they live outside
 *   `resources/android`.
 *
 * @see NativePluginHookCommand
 */
class CopyAssetsCommand extends NativePluginHookCommand
{
    protected $signature = 'nativephp:sylloo-app-native:copy-assets';

    protected $description = 'Copy assets for the sylloo-app-native plugin';

    public const FIREBASE_RES = 'values/sylloo_firebase.xml';

    public const TEST_PACKAGE = 'com/rishadblack/plugins/sylloo_app_native';

    public function handle(): int
    {
        if (! $this->isAndroid() || $this->buildPath() === '') {
            return self::SUCCESS;
        }

        $this->firebase();
        $this->jvmTests();

        return self::SUCCESS;
    }

    protected function firebase(): void
    {
        $target = $this->buildPath().'/app/src/main/res/'.self::FIREBASE_RES;
        $source = (string) config('sylloo-app-native.push.google_services', '');
        $json = $source !== '' && is_file($source) ? json_decode((string) file_get_contents($source), true) : null;
        $values = is_array($json) ? FirebaseValues::fromJson($json, $this->appId()) : null;

        if ($values === null) {
            if (is_file($target)) {
                @unlink($target);
            }

            $this->line('sylloo-app-native: no google-services.json for '.$this->appId().'; push is not set up in this build.');

            return;
        }

        $this->ensureDirectory(dirname($target));
        file_put_contents($target, FirebaseValues::xml($values));
        $this->line('sylloo-app-native: Firebase values written.');
    }

    protected function jvmTests(): void
    {
        $plugin = $this->pluginPath() !== '' ? $this->pluginPath() : dirname(__DIR__, 2);
        $tests = $plugin.'/resources/android-tests';

        if (! is_dir($tests)) {
            return;
        }

        $target = $this->buildPath().'/app/src/test/java/'.self::TEST_PACKAGE;
        $this->ensureDirectory($target);

        foreach (glob($tests.'/*.kt') ?: [] as $file) {
            copy($file, $target.'/'.basename($file));
        }

        $fixture = $plugin.'/tests/Fixtures/window-cases.json';

        if (is_file($fixture)) {
            $resources = $this->buildPath().'/app/src/test/resources';
            $this->ensureDirectory($resources);
            copy($fixture, $resources.'/window-cases.json');
        }
    }
}
