<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\LocationAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * One fresh location reading with a time limit (bridge function Location.Fix, plan §4.6, §7.4): 10 seconds online,
 * 30 offline. Never a last-known position, never in the background. The answer arrives as {@see LocationAnswered}
 * (read it with Data\LocationReading::fromEvent()).
 */
class Location
{
    public const MIN_SECONDS = 2;

    public const MAX_SECONDS = 60;

    /**
     * Start one reading. True when it started (the permission is asked for first when needed).
     */
    public function fix(int $seconds = 10, ?string $id = null): bool
    {
        $seconds = max(self::MIN_SECONDS, min(self::MAX_SECONDS, $seconds));

        return (bool) (Bridge::call('Location.Fix', array_filter([
            'timeout_ms' => $seconds * 1000,
            'id' => $id,
        ], fn ($value) => $value !== null))['started'] ?? false);
    }
}
