<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Sending what waits, also with the app closed (bridge functions Sync.Schedule and Sync.Cancel, plan §4.6, §7.7):
 * Android's WorkManager runs the artisan command `sylloo-app-native:send-waiting` (Commands\SendWaitingCommand, which
 * asks the app's Contracts\WaitingSender) once the phone is online, and with `$periodMinutes` again every period (at
 * least 15 minutes, Android's floor). One run waits at a time.
 */
class Sync
{
    public const COMMAND = 'sylloo-app-native:send-waiting';

    public function schedule(int $periodMinutes = 0, string $command = self::COMMAND): bool
    {
        return (bool) (Bridge::call('Sync.Schedule', [
            'command' => $command,
            'period_minutes' => max(0, $periodMinutes),
        ])['scheduled'] ?? false);
    }

    public function cancel(): bool
    {
        return (bool) (Bridge::call('Sync.Cancel')['cancelled'] ?? false);
    }
}
