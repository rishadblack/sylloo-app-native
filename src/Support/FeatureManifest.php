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
    public const FEATURES = ['print', 'scan_keys', 'scan_camera', 'sms_send', 'notice_schedule', 'calls_recent', 'calls_ended', 'calls_recording'];

    public const VALUES_RES = 'values/sylloo_features.xml';

    /**
     * What each job adds to the manifest. Print: Bluetooth connect (Android 12+; classic BLUETOOTH up to Android 11),
     * no scan (only paired printers are listed); USB needs no permission (Android asks the person per printer), only
     * the optional feature lines so the app also installs on devices without Bluetooth or USB host. Camera scanning:
     * the CAMERA permission is already in the package's `nativephp.json` (Qr.Scan, Camera.Take), so only the optional
     * camera feature lines (the till also installs on a device without a back camera and uses a typing scanner). Phone
     * SMS: sending SMS, the SIM list (READ_PHONE_STATE, only this job adds it), the exact alarm that wakes the sending
     * job, the foreground notice while it sends, and the optional telephony feature line. Timed notices: the exact alarm
     * (USE_EXACT_ALARM on Android 13+, SCHEDULE_EXACT_ALARM before) and the restart broadcast that arms them again
     * (POST_NOTIFICATIONS is already the package's, for push). Work calls (crm-call-recording-plan.md CR2): reading the
     * call list (READ_CALL_LOG) for `calls_recent`; hearing that a call ended (READ_PHONE_STATE, shared with phone SMS)
     * for `calls_ended`. Call recordings (CR3, `calls_recording`) add nothing: Android's folder chooser gives the read
     * grant of the one folder the person picks, so no storage permission is ever asked. A permission an earlier switched-on job of this list already added is not written twice.
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
        'sms_send' => [
            '<uses-permission android:name="android.permission.SEND_SMS" />',
            '<uses-permission android:name="android.permission.READ_PHONE_STATE" />',
            '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
            '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
            '<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />',
            '<uses-feature android:name="android.hardware.telephony" android:required="false" />',
        ],
        'notice_schedule' => [
            '<uses-permission android:name="android.permission.USE_EXACT_ALARM" />',
            '<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />',
            '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />',
        ],
        'calls_recent' => [
            '<uses-permission android:name="android.permission.READ_CALL_LOG" />',
        ],
        'calls_ended' => [
            '<uses-permission android:name="android.permission.READ_PHONE_STATE" />',
        ],
        'calls_recording' => [],
    ];

    /**
     * What a job adds inside `<application>` (before its end), in its own marked block (`<feature>_app`). Phone SMS:
     * the receivers of the sent / delivered answers and of the exact alarm (not exported), and WorkManager's
     * foreground service with the dataSync type Android 14 wants for the "Sending the shop's messages" notice. Timed
     * notices: the alarm receiver, the restart receiver (after a restart or an app update) and the two small screens
     * of a tapped notice (open / Call, and Later's choices). Work calls: the receiver of the end of a call
     * (PHONE_STATE). None of them is exported.
     *
     * @var array<string, list<string>>
     */
    public const APPLICATION_ENTRIES = [
        'sms_send' => [
            '<receiver android:name="com.rishadblack.plugins.sylloo_app_native.SmsResultReceiver" android:exported="false" />',
            '<receiver android:name="com.rishadblack.plugins.sylloo_app_native.MessagesAlarmReceiver" android:exported="false" />',
            '<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />',
        ],
        'notice_schedule' => [
            '<receiver android:name="com.rishadblack.plugins.sylloo_app_native.NoticeAlarmReceiver" android:exported="false" />',
            '<receiver android:name="com.rishadblack.plugins.sylloo_app_native.NoticeRestartReceiver" android:exported="false"><intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /><action android:name="android.intent.action.MY_PACKAGE_REPLACED" /></intent-filter></receiver>',
            '<activity android:name="com.rishadblack.plugins.sylloo_app_native.NoticeOpenActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" android:excludeFromRecents="true" android:taskAffinity="" />',
            '<activity android:name="com.rishadblack.plugins.sylloo_app_native.NoticeLaterActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar" android:exported="false" android:excludeFromRecents="true" android:taskAffinity="" />',
        ],
        'calls_ended' => [
            '<receiver android:name="com.rishadblack.plugins.sylloo_app_native.CallEndedReceiver" android:exported="false"><intent-filter><action android:name="android.intent.action.PHONE_STATE" /></intent-filter></receiver>',
        ],
    ];

    /**
     * The manifest with the block of each switched-on job, and without the block of each one that is off.
     *
     * @param  array<string, bool>  $features
     */
    public static function apply(string $manifest, array $features): string
    {
        // Every block out first, so a permission two jobs share is written by the first one switched on.
        foreach (self::FEATURES as $feature) {
            $manifest = self::remove($manifest, $feature);
            $manifest = self::remove($manifest, $feature.'_app');
        }

        foreach (self::FEATURES as $feature) {
            if (($features[$feature] ?? false) && self::ENTRIES[$feature] !== []) {
                $manifest = self::add($manifest, $feature);
            }

            if (($features[$feature] ?? false) && (self::APPLICATION_ENTRIES[$feature] ?? []) !== []) {
                $manifest = self::addInApplication($manifest, $feature);
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
            // Android's manifest merger refuses the same permission twice in one file (sms_send and notice_schedule
            // both need the exact alarm): one an earlier block or the app already has is not written again.
            if (preg_match('/<uses-permission android:name="([^"]+)"/', $entry, $name) === 1 && str_contains($manifest, 'android:name="'.$name[1].'"')) {
                continue;
            }

            $block .= "    {$entry}\n";
        }

        $block .= "    <!-- /sylloo-app-native:{$feature} -->";

        return (string) preg_replace('/(\s*<application\b)/', $block.'$1', $manifest, 1);
    }

    protected static function addInApplication(string $manifest, string $feature): string
    {
        $block = "\n        <!-- sylloo-app-native:{$feature}_app -->\n";

        foreach (self::APPLICATION_ENTRIES[$feature] as $entry) {
            $block .= "        {$entry}\n";
        }

        $block .= "        <!-- /sylloo-app-native:{$feature}_app -->";

        return (string) preg_replace('/(\s*<\/application>)/', $block.'$1', $manifest, 1);
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
