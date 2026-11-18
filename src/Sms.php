<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\SmsAnswered;
use Rishadblack\SyllooAppNative\Events\SmsPermissionAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * SMS from this phone's SIM (bridge functions Sms.Send, Sms.Sims, Sms.Permission; phone-messaging-plan.md §5). Off
 * unless the app turns on `sylloo-app-native.features.sms_send` (then every call answers `not_set_up` without asking
 * the phone, and the build has no SMS permission).
 *
 * The business's SMS are sent by the background job ({@see Messages}); this is the plain call for one SMS. A long or
 * Bangla text goes in parts (the phone splits it). The outcome comes as {@see SmsAnswered}; Android's permission
 * answer as {@see SmsPermissionAnswered}. Nothing throws.
 */
class Sms
{
    public const STARTED = 'started';

    public const NOT_SET_UP = 'not_set_up';

    public const PERMISSION = 'permission';

    public const NO_SIM = 'no_sim';

    public const FAILED = 'failed';

    public const MAX_TEXT = 2000;

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.sms_send', false);
    }

    /**
     * Send one SMS from a SIM slot (null: the phone's SMS SIM). `to` in E.164 (+8801712345678).
     */
    public function send(string $to, string $text, ?int $slot = null, ?string $id = null): string
    {
        if (! $this->enabled()) {
            return self::NOT_SET_UP;
        }

        if (preg_match('/^\+[1-9]\d{6,14}$/', $to) !== 1 || $text === '' || mb_strlen($text) > self::MAX_TEXT) {
            return self::FAILED;
        }

        $status = Bridge::call('Sms.Send', array_filter(['to' => $to, 'text' => $text, 'slot' => $slot, 'id' => $id], fn ($value) => $value !== null))['status'] ?? null;

        return in_array($status, [self::STARTED, self::NOT_SET_UP, self::PERMISSION, self::NO_SIM], true) ? $status : self::FAILED;
    }

    /**
     * The SIMs: `['status' => listed | permission | not_set_up, 'sims' => [['slot' => 0, 'label' => 'SIM 1 · Robi',
     * 'operator' => 'Robi'], …], 'can_send' => bool, 'default_slot' => ?int]`. Android gives no SIM number.
     *
     * @return array{status: string, sims: list<array{slot: int, label: string, operator: string}>, can_send: bool, default_slot: int|null}
     */
    public function sims(): array
    {
        if (! $this->enabled()) {
            return ['status' => self::NOT_SET_UP, 'sims' => [], 'can_send' => false, 'default_slot' => null];
        }

        $answer = Bridge::call('Sms.Sims') ?? [];
        $sims = [];

        foreach (is_array($answer['sims'] ?? null) ? $answer['sims'] : [] as $sim) {
            if (is_array($sim) && is_int($sim['slot'] ?? null) && is_string($sim['label'] ?? null)) {
                $sims[] = ['slot' => $sim['slot'], 'label' => $sim['label'], 'operator' => is_string($sim['operator'] ?? null) ? $sim['operator'] : ''];
            }
        }

        return [
            'status' => is_string($answer['status'] ?? null) ? $answer['status'] : self::FAILED,
            'sims' => $sims,
            'can_send' => ($answer['can_send'] ?? false) === true,
            'default_slot' => is_int($answer['default_slot'] ?? null) ? $answer['default_slot'] : null,
        ];
    }

    /**
     * Whether Android allows sending SMS and listing the SIMs.
     */
    public function hasPermission(): bool
    {
        $sims = $this->sims();

        return $sims['status'] === 'listed' && $sims['can_send'];
    }

    /**
     * Ask Android's question (SEND_SMS, READ_PHONE_STATE): `granted` (already), `asking` (the answer comes as
     * {@see SmsPermissionAnswered}), `not_set_up` or `failed`.
     */
    public function askPermission(?string $id = null): string
    {
        if (! $this->enabled()) {
            return self::NOT_SET_UP;
        }

        $status = Bridge::call('Sms.Permission', array_filter(['id' => $id], fn ($value) => $value !== null))['status'] ?? null;

        return in_array($status, ['granted', 'asking', self::NOT_SET_UP], true) ? $status : self::FAILED;
    }
}
