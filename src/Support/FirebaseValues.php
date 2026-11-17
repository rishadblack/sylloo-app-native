<?php

namespace Rishadblack\SyllooAppNative\Support;

/**
 * Firebase's start values from a `google-services.json`, as Google's gradle plugin would write them (`res/values`
 * strings that FirebaseInitProvider reads). The package writes them itself at build time (the copy_assets hook) so
 * the app needs no extra gradle plugin, and the json file stays outside git (config `sylloo-app-native.push.google_services`).
 */
final class FirebaseValues
{
    /**
     * The values for the app id, or null when the file has no Android client for it.
     *
     * @param  array<string, mixed>  $json
     * @return array<string, string>|null
     */
    public static function fromJson(array $json, string $appId): ?array
    {
        $project = is_array($json['project_info'] ?? null) ? $json['project_info'] : [];
        $client = null;

        foreach ((array) ($json['client'] ?? []) as $candidate) {
            if (is_array($candidate) && ($candidate['client_info']['android_client_info']['package_name'] ?? null) === $appId) {
                $client = $candidate;
                break;
            }
        }

        $appIdValue = $client['client_info']['mobilesdk_app_id'] ?? null;
        $apiKey = $client['api_key'][0]['current_key'] ?? null;

        if (! is_string($appIdValue) || $appIdValue === '' || ! is_string($apiKey) || $apiKey === '') {
            return null;
        }

        return array_filter([
            'google_app_id' => $appIdValue,
            'gcm_defaultSenderId' => self::text($project['project_number'] ?? null),
            'google_api_key' => $apiKey,
            'google_crash_reporting_api_key' => $apiKey,
            'project_id' => self::text($project['project_id'] ?? null),
            'google_storage_bucket' => self::text($project['storage_bucket'] ?? null),
            'firebase_database_url' => self::text($project['firebase_url'] ?? null),
        ], fn ($value) => $value !== null);
    }

    /**
     * @param  array<string, string>  $values
     */
    public static function xml(array $values): string
    {
        $lines = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Written by sylloo-app-native at build time from google-services.json. Do not edit, never commit. -->', '<resources>'];

        foreach ($values as $name => $value) {
            $lines[] = sprintf('    <string name="%s" translatable="false">%s</string>', $name, htmlspecialchars($value, ENT_XML1 | ENT_QUOTES, 'UTF-8'));
        }

        $lines[] = '</resources>';

        return implode("\n", $lines)."\n";
    }

    protected static function text(mixed $value): ?string
    {
        return is_scalar($value) && (string) $value !== '' ? (string) $value : null;
    }
}
