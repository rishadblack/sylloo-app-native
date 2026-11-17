<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Data\ClockReading;

/**
 * The phone's running timer and restart count (bridge function Clock.Elapsed).
 */
class Clock
{
    /**
     * Read the running timer. Null when not running on a phone (no bridge) or the call failed.
     */
    public function elapsed(): ?ClockReading
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        $result = nativephp_call('Clock.Elapsed', '{}');

        if (! $result) {
            return null;
        }

        $decoded = json_decode($result, true);

        if (! is_array($decoded)) {
            return null;
        }

        // The bridge wraps a success as {"status":"success","data":{...}}; accept the bare payload too.
        $data = is_array($decoded['data'] ?? null) ? $decoded['data'] : $decoded;

        if (! isset($data['elapsed_ms'])) {
            return null;
        }

        return new ClockReading(
            elapsedMs: (int) $data['elapsed_ms'],
            bootCount: isset($data['boot_count']) && (int) $data['boot_count'] >= 0 ? (int) $data['boot_count'] : null,
            wallMs: isset($data['wall_ms']) ? (int) $data['wall_ms'] : null,
        );
    }
}
