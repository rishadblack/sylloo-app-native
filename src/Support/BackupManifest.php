<?php

namespace Rishadblack\SyllooAppNative\Support;

/**
 * App backup off (nativephp-plan.md §7.10, §10): the phone's private folder holds the activation, sign-ins, the
 * encrypted saved data and what waits to be sent, so none of it may leave the phone through Android's cloud backup
 * (Android 6–11: `android:allowBackup`, `android:fullBackupContent`) or a device-to-device transfer (Android 12+:
 * `android:dataExtractionRules`, which `allowBackup="false"` alone does not stop).
 *
 * Written by the copy_assets build hook on the generated `AndroidManifest.xml` (NativePHP writes
 * `android:allowBackup="true"` and has no switch), with two rule files in `res/xml/` that exclude every domain.
 */
final class BackupManifest
{
    public const FULL_BACKUP_RES = 'xml/sylloo_backup_rules.xml';

    public const DATA_EXTRACTION_RES = 'xml/sylloo_data_extraction_rules.xml';

    /** Every domain Android backs up or transfers (the `device_*` ones are the credential-protected storage). */
    public const DOMAINS = ['root', 'file', 'database', 'sharedpref', 'external', 'device_root', 'device_file', 'device_database', 'device_sharedpref'];

    /**
     * The manifest's `<application>` with backup off and the two rule files named; any earlier values of these three
     * attributes are replaced, every other attribute is kept.
     */
    public static function apply(string $manifest): string
    {
        return (string) preg_replace_callback('/<application\b[^>]*>/s', function (array $match): string {
            $tag = (string) preg_replace('/\s+android:(allowBackup|fullBackupContent|dataExtractionRules)="[^"]*"/', '', $match[0]);
            $attributes = "\n        android:allowBackup=\"false\""
                ."\n        android:fullBackupContent=\"@xml/sylloo_backup_rules\""
                ."\n        android:dataExtractionRules=\"@xml/sylloo_data_extraction_rules\"";

            return (string) preg_replace('/^<application\b/', '<application'.$attributes, $tag);
        }, $manifest, 1);
    }

    /**
     * `res/xml/sylloo_backup_rules.xml` (Android 6–11): nothing is backed up.
     */
    public static function fullBackupRules(): string
    {
        return '<?xml version="1.0" encoding="utf-8"?>'."\n"
            .'<!-- Written by the build hook of rishadblack/sylloo-app-native: app backup off. -->'."\n"
            .'<full-backup-content>'."\n"
            .self::excludes('    ')
            .'</full-backup-content>'."\n";
    }

    /**
     * `res/xml/sylloo_data_extraction_rules.xml` (Android 12+): nothing to the cloud, nothing to a new phone.
     */
    public static function dataExtractionRules(): string
    {
        return '<?xml version="1.0" encoding="utf-8"?>'."\n"
            .'<!-- Written by the build hook of rishadblack/sylloo-app-native: app backup and device transfer off. -->'."\n"
            .'<data-extraction-rules>'."\n"
            .'    <cloud-backup>'."\n".self::excludes('        ').'    </cloud-backup>'."\n"
            .'    <device-transfer>'."\n".self::excludes('        ').'    </device-transfer>'."\n"
            .'</data-extraction-rules>'."\n";
    }

    protected static function excludes(string $indent): string
    {
        $lines = '';

        foreach (self::DOMAINS as $domain) {
            $lines .= $indent.'<exclude domain="'.$domain.'" path="." />'."\n";
        }

        return $lines;
    }
}
