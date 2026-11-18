<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\NoticeOpened;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The phone's own timed notices (bridge functions Notice.Schedule, Notice.Cancel, Notice.Opened): shown by an alarm at
 * their minute, also offline, with the app closed and after a restart. Off unless the app turns on
 * `sylloo-app-native.features.notice_schedule` (then every call answers without asking the phone, and the build has no
 * alarm permission and no receiver).
 *
 * Every text is the app's, already translated; quiet hours and working time are the app's rules: the phone only shows
 * each notice at the time it was given. A tap opens the app and keeps `open` / `open_id` / `action` (`open` or `call`)
 * for {@see opened()} (also sent as {@see NoticeOpened}); Call only opens the app with the action `call` (the app opens
 * the dialer itself); Later offers the app's choices (minutes, or a fixed time) and shows it again. Nothing throws.
 */
class Notices
{
    public const SCHEDULED = 'scheduled';

    public const NOT_SET_UP = 'not_set_up';

    public const INVALID = 'invalid';

    public const FAILED = 'failed';

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.notice_schedule', false);
    }

    /**
     * Set (or set again, by its id) one timed notice. Fields: `id` (`[A-Za-z0-9_\-:.]{1,64}`), `at_ms` (epoch ms),
     * `title`, `body`, `open` (`[a-z0-9_.-]{1,40}`), optional `open_id`, `group` (default `default`), `channel`,
     * `channel_name`, `call` (`['label' => …]`), `later` (`['label' => …, 'options' => [['key', 'label', 'minutes' |
     * 'at_ms'], …]]`, at most 3). Answers `scheduled`, `not_set_up`, `invalid` or `failed`.
     *
     * @param  array<string, mixed>  $notice
     */
    public function schedule(array $notice): string
    {
        if (! $this->enabled()) {
            return self::NOT_SET_UP;
        }

        if (! is_string($notice['id'] ?? null) || preg_match('/^[A-Za-z0-9_\-:.]{1,64}$/', $notice['id']) !== 1
            || ! is_int($notice['at_ms'] ?? null) || ! is_string($notice['open'] ?? null) || preg_match('/^[a-z0-9_.-]{1,40}$/', $notice['open']) !== 1) {
            return self::INVALID;
        }

        $answer = Bridge::call('Notice.Schedule', array_filter($notice, fn ($value) => $value !== null)) ?? [];

        if (($answer['scheduled'] ?? false) === true) {
            return self::SCHEDULED;
        }

        return in_array($answer['reason'] ?? null, [self::NOT_SET_UP, self::INVALID], true) ? $answer['reason'] : self::FAILED;
    }

    /**
     * Take away one timed notice (its alarm, and the notice if it shows).
     */
    public function cancel(string $id): bool
    {
        return $this->enabled() && (Bridge::call('Notice.Cancel', ['id' => $id])['cancelled'] ?? false) === true;
    }

    /**
     * Take away every timed notice of a group (e.g. at sign-out).
     */
    public function cancelGroup(string $group): bool
    {
        return $this->enabled() && (Bridge::call('Notice.Cancel', ['group' => $group])['cancelled'] ?? false) === true;
    }

    /**
     * What the last tapped timed notice opens, once: `['open' => …, 'open_id' => …|null, 'action' => 'open'|'call']`,
     * or null.
     *
     * @return array{open: string, open_id: string|null, action: string}|null
     */
    public function opened(): ?array
    {
        if (! $this->enabled()) {
            return null;
        }

        $answer = Bridge::call('Notice.Opened') ?? [];
        $open = $answer['open'] ?? null;

        if (($answer['found'] ?? false) !== true || ! is_string($open) || preg_match('/^[a-z0-9_.-]{1,40}$/', $open) !== 1) {
            return null;
        }

        $id = $answer['open_id'] ?? null;

        return [
            'open' => $open,
            'open_id' => is_string($id) && preg_match('/^[A-Za-z0-9_\-:.]{1,64}$/', $id) === 1 ? $id : null,
            'action' => ($answer['action'] ?? null) === 'call' ? 'call' : 'open',
        ];
    }
}
