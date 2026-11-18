<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppBase\Contracts\DeviceEntries;
use Rishadblack\SyllooAppBase\Contracts\MessagingPhone;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The background job that sends the business's SMS from this phone (bridge functions Messages.Start, Messages.Work,
 * Messages.Stop, Messages.Status; phone-messaging-plan.md §5): the whole loop runs in Kotlin, also with the app closed
 * — ask the office what waits (`GET messages` with the device key), claim one, send it from the chosen SIM, report it,
 * wait the office's pace, the next; it stops at the daily limit and honours a pause. An exact alarm, the wake push
 * `{wake: messages}` and every return to the app wake it.
 *
 * The device key: PHP is not running in the background, so {@see self::start()} hands the key, the office's address
 * and the app version to the phone, which keeps the key in the Keystore vault (the same as Secret.*, never in a
 * backup) and the rest in its own settings. The base's `Messaging\PhoneMessaging::keepGoing()` calls it at every
 * return to the app; {@see self::stop()} forgets them. Off unless `features.sms_send` (then everything answers false
 * or empty without asking the phone). The base's screens use it as `Contracts\MessagingPhone`.
 */
class Messages implements MessagingPhone
{
    public function __construct(protected Sms $sms) {}

    public function available(): bool
    {
        return $this->sms->enabled();
    }

    public function hasPermission(): bool
    {
        return $this->available() && $this->sms->hasPermission();
    }

    public function askPermission(): bool
    {
        return in_array($this->sms->askPermission(), ['granted', 'asking'], true);
    }

    public function sims(): ?array
    {
        $answer = $this->sms->sims();

        if ($answer['status'] !== 'listed') {
            return null;
        }

        return array_map(fn (array $sim) => ['slot' => $sim['slot'], 'label' => $sim['label']], $answer['sims']);
    }

    public function start(string $baseUrl, string $deviceKey, string $appVersion): bool
    {
        if (! $this->available() || $deviceKey === '' || $baseUrl === '') {
            return false;
        }

        $business = $this->businessName();

        $status = Bridge::call('Messages.Start', [
            'base_url' => $baseUrl,
            'device_key' => $deviceKey,
            'app_version' => $appVersion,
            'notice_title' => (string) __('sylloo-app-native::pieces.messages_notice_title'),
            'notice_text' => $business !== null ? (string) __('sylloo-app-native::pieces.messages_notice_text', ['business' => $business]) : '',
            // A local office (http://…) only outside production; the phone refuses http otherwise.
            'allow_http' => ! app()->isProduction(),
        ])['status'] ?? null;

        return $status === 'started';
    }

    public function wake(): bool
    {
        if (! $this->available()) {
            return false;
        }

        return (Bridge::call('Messages.Work')['status'] ?? null) === 'started';
    }

    public function stop(): bool
    {
        // Also when switched off: forgetting the key is always safe.
        return (Bridge::call('Messages.Stop')['status'] ?? null) === 'stopped';
    }

    public function status(?int $slot = null): array
    {
        if (! $this->available()) {
            return [];
        }

        $answer = Bridge::call('Messages.Status', $slot !== null ? ['slot' => $slot] : []) ?? [];

        if (($answer['status'] ?? null) !== 'ok') {
            return [];
        }

        unset($answer['status']);

        return $answer;
    }

    protected function businessName(): ?string
    {
        if (! app()->bound(DeviceEntries::class)) {
            return null;
        }

        $name = app(DeviceEntries::class)->current()->state->businessName();

        return is_string($name) && $name !== '' ? $name : null;
    }
}
