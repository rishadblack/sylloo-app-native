<?php

namespace Rishadblack\SyllooAppNative\Data;

use Carbon\CarbonImmutable;

/**
 * One work call of the call list (Calls.Recent): a call whose number ends in one of the app's compare keys. A private
 * call is never one of these: the phone hands only work calls over.
 */
final class PhoneCall
{
    public function __construct(
        public readonly string $callId,
        public readonly string $number,
        public readonly string $key,
        public readonly string $direction,
        public readonly int $startedAtMs,
        public readonly int $durationSeconds,
    ) {}

    /**
     * A row of the bridge's answer, or null when its shape is wrong.
     *
     * @param  array<string, mixed>  $row
     */
    public static function fromBridge(array $row): ?self
    {
        $callId = $row['call_id'] ?? null;
        $number = $row['number'] ?? null;
        $key = $row['key'] ?? null;
        $direction = $row['direction'] ?? null;
        $started = $row['started_at_ms'] ?? null;
        $duration = $row['duration_seconds'] ?? null;

        if (! is_string($callId) || preg_match('/^[A-Za-z0-9_\-:.]{1,64}$/', $callId) !== 1
            || ! is_string($number) || trim($number) === '' || mb_strlen($number) > 30
            || ! is_string($key) || preg_match('/^\d{6,15}$/', $key) !== 1
            || ! in_array($direction, ['in', 'out'], true) || ! is_numeric($started) || ! is_numeric($duration)) {
            return null;
        }

        return new self($callId, trim($number), $key, $direction, (int) $started, max(0, min(86_400, (int) $duration)));
    }

    public function startedAt(): CarbonImmutable
    {
        return CarbonImmutable::createFromTimestampMs($this->startedAtMs, 'UTC');
    }
}
