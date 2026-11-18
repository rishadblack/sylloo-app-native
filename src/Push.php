<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\NoticeOpened;
use Rishadblack\SyllooAppNative\Events\PushOpened;
use Rishadblack\SyllooAppNative\Events\PushPermissionAnswered;
use Rishadblack\SyllooAppNative\Events\PushTokenAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Notices to the phone through Firebase Cloud Messaging (bridge functions Push.Permission, Push.Token, Push.Opened,
 * Push.Labels; plan §4.6, §7.9). The answers arrive as {@see PushPermissionAnswered} and {@see PushTokenAnswered}; a
 * tapped notice sends {@see PushOpened} (an address) or {@see NoticeOpened} (a screen) while the app runs and is kept
 * for {@see self::tapped()} when it was closed.
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
        return $this->tapped()['url'] ?? null;
    }

    /**
     * The notice the person tapped to open the app, once: its address and / or its screen (`open`, `open_id`, and the
     * pressed button as `action`, `open` for a tap on the notice itself; device-api.md §1.1). Null when none.
     *
     * @return array{url: string|null, open: string|null, open_id: string|null, action: string|null}|null
     */
    public function tapped(): ?array
    {
        $answer = Bridge::call('Push.Opened') ?? [];

        if (($answer['found'] ?? null) === false) {
            return null;
        }

        $url = $answer['url'] ?? null;
        $open = $answer['open'] ?? null;
        $open = is_string($open) && preg_match('/^[a-z0-9_.-]{1,40}$/', $open) === 1 ? $open : null;
        $id = $answer['open_id'] ?? null;
        $action = $answer['action'] ?? null;

        $tapped = [
            'url' => is_string($url) && str_starts_with($url, 'https://') ? $url : null,
            'open' => $open,
            'open_id' => $open !== null && is_string($id) && preg_match('/^[A-Za-z0-9_\-:.]{1,64}$/', $id) === 1 ? $id : null,
            'action' => $open !== null ? (is_string($action) && preg_match('/^[a-z0-9_.-]{1,40}$/', $action) === 1 ? $action : 'open') : null,
        ];

        return $tapped['url'] === null && $tapped['open'] === null ? null : $tapped;
    }

    /**
     * The texts of the buttons a push may ask for (`actions[].label` is a key of this map, e.g. `crm.call`), already
     * in the person's language. A button whose label is not here is not shown.
     *
     * @param  array<string, string>  $labels
     */
    public function labels(array $labels): bool
    {
        $kept = [];

        foreach ($labels as $key => $label) {
            if (is_string($key) && preg_match('/^[a-z0-9_.-]{1,40}$/', $key) === 1 && is_string($label) && trim($label) !== '') {
                $kept[$key] = mb_substr(trim($label), 0, 40);
            }
        }

        return Bridge::call('Push.Labels', ['labels' => $kept === [] ? new \stdClass : $kept]) !== null;
    }
}
