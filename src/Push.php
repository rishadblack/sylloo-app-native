<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\PushOpened;
use Rishadblack\SyllooAppNative\Events\PushPermissionAnswered;
use Rishadblack\SyllooAppNative\Events\PushTokenAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Notices to the phone through Firebase Cloud Messaging (bridge functions Push.Permission, Push.Token, Push.Opened;
 * plan §4.6, §7.9). The answers arrive as {@see PushPermissionAnswered} and {@see PushTokenAnswered}; a tapped notice
 * sends {@see PushOpened} while the app runs and is kept for {@see self::opened()} when it was closed.
 */
class Push
{
    /**
     * Ask for permission to show notices (Android 13 and newer ask the person once).
     */
    public function askPermission(?string $id = null): bool
    {
        return (bool) (Bridge::call('Push.Permission', array_filter(['id' => $id]))['started'] ?? false);
    }

    /**
     * Ask Firebase for this phone's token (sent to the office with `POST push-token`).
     */
    public function token(?string $id = null): bool
    {
        return (bool) (Bridge::call('Push.Token', array_filter(['id' => $id]))['started'] ?? false);
    }

    /**
     * The address of the notice the person tapped to open the app, once; null when none.
     */
    public function opened(): ?string
    {
        $url = Bridge::call('Push.Opened')['url'] ?? null;

        return is_string($url) && str_starts_with($url, 'https://') ? $url : null;
    }
}
