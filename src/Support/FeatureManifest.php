<?php

namespace Rishadblack\SyllooAppNative\Support;

/**
 * The Android parts of the switched jobs (config `sylloo-app-native.features`), written by the copy_assets build hook.
 *
 * Why "add when on" and not "remove when off": NativePHP merges every permission of a plugin's `nativephp.json` into
 * the manifest on every build, with no switch. So the till's permissions are not in `nativephp.json`; the hook (which
 * runs after that merge, on the same generated manifest, before Gradle) adds them in its own marked block when a job is
 * on, and takes its block out again when it is off. Permissions another plugin or the app declares are never touched.
 */
final class FeatureManifest
{
    public const FEATURES = ['print', 'scan_keys', 'scan_camera'];

    public const VALUES_RES = 'values/sylloo_features.xml';

    /**
     * What each job adds to the manifest. Print: Bluetooth connect (Android 12+; classic BLUETOOTH up to Android 11),
     * no scan (only paired printers are listed); USB needs no permission (Android asks the person per printer), only
     * the optional feature lines so the app also installs on devices without Bluetooth or USB host. Camera scanning:
     * the CAMERA permission is already in the package's `nativephp.json` (Qr.Scan, Camera.Take), so only the optional
     * camera feature lines (the till also installs on a device without a back camera and uses a typing scanner).
     *
     * @var array<string, list<string>>
     */
    public const ENTRIES = [
        'print' => [
            '<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
            '<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
            '<uses-feature android:name="android.hardware.bluetooth" android:required="false" />',
            '<uses-feature android:name="android.hardware.usb.host" android:required="false" />',
        ],
        'scan_keys' => [],
        'scan_camera' => [
            '<uses-feature android:name="android.hardware.camera" android:required="false" />',
            '<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />',
        ],
    ];

    /**
     * The manifest with the block of each switched-on job, and without the block of each one that is off.
     *
     * @param  array<string, bool>  $features
     */
    public static function apply(string $manifest, array $features): string
    {
        foreach (self::FEATURES as $feature) {
            $manifest = self::remove($manifest, $feature);

            if (($features[$feature] ?? false) && self::ENTRIES[$feature] !== []) {
                $manifest = self::add($manifest, $feature);
            }
        }

        return $manifest;
    }

    /**
     * `res/values/sylloo_features.xml`: the switches as Android booleans (`sylloo_feature_print`, …), read by the Kotlin
     * jobs so a switched-off job refuses on the phone too.
     *
     * @param  array<string, bool>  $features
     */
    public static function values(array $features): string
    {
        $lines = [];

        foreach (self::FEATURES as $feature) {
            $lines[] = '    <bool name="sylloo_feature_'.$feature.'">'.(($features[$feature] ?? false) ? 'true' : 'false').'</bool>';
        }

        return '<?xml version="1.0" encoding="utf-8"?>'."\n"
            .'<!-- Written by the build hook of rishadblack/sylloo-app-native (config sylloo-app-native.features). -->'."\n"
            .'<resources xmlns:tools="http://schemas.android.com/tools" tools:keep="@bool/sylloo_feature_*">'."\n"
            .implode("\n", $lines)."\n"
            .'</resources>'."\n";
    }

    /**
     * @return array<string, bool>
     */
    public static function fromConfig(): array
    {
        $features = [];

        foreach (self::FEATURES as $feature) {
            $features[$feature] = (bool) config('sylloo-app-native.features.'.$feature, false);
        }

        return $features;
    }

    protected static function add(string $manifest, string $feature): string
    {
        $block = "\n    <!-- sylloo-app-native:{$feature} -->\n";

        foreach (self::ENTRIES[$feature] as $entry) {
            $block .= "    {$entry}\n";
        }

        $block .= "    <!-- /sylloo-app-native:{$feature} -->";

        return (string) preg_replace('/(\s*<application\b)/', $block.'$1', $manifest, 1);
    }

    /**
     * Every block of the job, also one NativePHP's next build left glued to the end of a line (its permission merge
     * writes its lines without a line break before what follows), so a rebuild never adds a second copy.
     */
    protected static function remove(string $manifest, string $feature): string
    {
        $name = preg_quote($feature, '/');

        return (string) preg_replace('/(?:\r?\n)?[ \t]*<!-- sylloo-app-native:'.$name.' -->.*?<!-- \/sylloo-app-native:'.$name.' -->/s', '', $manifest);
    }
}
