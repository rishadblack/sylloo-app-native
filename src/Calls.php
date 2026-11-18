<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Data\CallsRead;
use Rishadblack\SyllooAppNative\Data\PhoneCall;
use Rishadblack\SyllooAppNative\Events\CallsPermissionAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The phone's work calls (crm-call-recording-plan.md §3, §6, CR2): bridge functions Calls.Recent, Calls.Ended,
 * Calls.Permission and Calls.Status. Each job is off unless the app turns it on (`features.calls_recent`,
 * `features.calls_ended`): off, nothing is asked of the phone and the build has no call permission.
 *
 * The app hands over the compare keys of its own records (the last `digits` digits of each number); the phone hands
 * back only the calls whose number ends in one of them. A call with any other number is never handed over, not even its
 * number or its time: a private call never leaves the phone's call list. The answers here are checked again (shape, and
 * the key against the app's keys). Nothing throws.
 */
class Calls
{
    public const OK = 'ok';

    public const NOT_SET_UP = 'not_set_up';

    public const NO_PERMISSION = 'no_permission';

    public const FAILED = 'failed';

    public const DEFAULT_DIGITS = 9;

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.calls_recent', false);
    }

    public function endedEnabled(): bool
    {
        return (bool) config('sylloo-app-native.features.calls_ended', false);
    }

    /**
     * The compare key of a number: its last `digits` digits, or null for a number with fewer (as the phone's rule).
     */
    public static function key(?string $number, int $digits = self::DEFAULT_DIGITS): ?string
    {
        $plain = preg_replace('/\D/', '', (string) $number) ?? '';
        $digits = max(6, min(15, $digits));

        return strlen($plain) < $digits ? null : substr($plain, -$digits);
    }

    /**
     * What the phone has: the switches, the two permissions and whether the end of a call wakes the app now.
     *
     * @return array{recent: bool, ended: bool, call_log: bool, phone_state: bool, watching: bool}
     */
    public function status(): array
    {
        $off = ['recent' => false, 'ended' => false, 'call_log' => false, 'phone_state' => false, 'watching' => false];

        if (! $this->enabled() && ! $this->endedEnabled()) {
            return $off;
        }

        $answer = Bridge::call('Calls.Status') ?? [];

        return [
            'recent' => $this->enabled() && ($answer['recent'] ?? false) === true,
            'ended' => $this->endedEnabled() && ($answer['ended'] ?? false) === true,
            'call_log' => $this->enabled() && ($answer['call_log'] ?? false) === true,
            'phone_state' => $this->endedEnabled() && ($answer['phone_state'] ?? false) === true,
            'watching' => $this->endedEnabled() && ($answer['watching'] ?? false) === true,
        ];
    }

    /**
     * Ask Android for the permissions of the switched-on call jobs; the answer comes as {@see CallsPermissionAnswered}.
     * False when nothing was asked (off, or no screen to ask on).
     */
    public function askPermission(?string $id = null): bool
    {
        if (! $this->enabled() && ! $this->endedEnabled()) {
            return false;
        }

        $status = Bridge::call('Calls.Permission', array_filter(['id' => $id]))['status'] ?? null;

        return in_array($status, ['asking', 'granted'], true);
    }

    /**
     * The work calls after the row `$afterId` (and not before `$sinceMs`), at most `$limit`.
     *
     * @param  list<string>  $keys  the compare keys: only digits, each exactly `$digits` long
     */
    public function recent(array $keys, int $digits = self::DEFAULT_DIGITS, int $afterId = 0, int $sinceMs = 0, int $limit = 50): CallsRead
    {
        if (! $this->enabled()) {
            return new CallsRead(self::NOT_SET_UP);
        }

        $digits = max(6, min(15, $digits));
        $keys = array_values(array_unique(array_filter($keys, fn ($key) => is_string($key) && preg_match('/^\d{'.$digits.'}$/', $key) === 1)));

        $answer = Bridge::call('Calls.Recent', [
            'keys' => $keys,
            'digits' => $digits,
            'after_id' => max(0, $afterId),
            'since_ms' => max(0, $sinceMs),
            'limit' => max(1, min(100, $limit)),
        ]);

        $status = $answer['status'] ?? null;

        if ($status !== self::OK) {
            return new CallsRead(in_array($status, [self::NOT_SET_UP, self::NO_PERMISSION], true) ? $status : self::FAILED);
        }

        $calls = [];

        foreach ((array) ($answer['calls'] ?? []) as $row) {
            $call = is_array($row) ? PhoneCall::fromBridge($row) : null;

            // Again here: a call whose number is not one of the keys is never passed on.
            if ($call !== null && in_array($call->key, $keys, true) && self::key($call->number, $digits) === $call->key) {
                $calls[] = $call;
            }
        }

        $lastId = $answer['last_id'] ?? null;

        return new CallsRead(self::OK, $calls, is_numeric($lastId) ? max(0, (int) $lastId) : null, ($answer['more'] ?? false) === true);
    }

    /**
     * Wake the app's background command (default `sylloo-app-native:send-waiting`) a few seconds after each call ends,
     * also with the app closed. False when it is off.
     */
    public function watch(?string $command = null): bool
    {
        if (! $this->endedEnabled()) {
            return false;
        }

        return (Bridge::call('Calls.Ended', array_filter(['on' => true, 'command' => $command]))['watching'] ?? false) === true;
    }

    /**
     * Stop the wake (taken off the list, said no, signed out, released).
     */
    public function stopWatching(): bool
    {
        if (! $this->endedEnabled()) {
            return false;
        }

        Bridge::call('Calls.Ended', ['on' => false]);

        return true;
    }
}
