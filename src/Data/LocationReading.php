<?php

namespace Rishadblack\SyllooAppNative\Data;

use Rishadblack\SyllooAppNative\Events\LocationAnswered;

/**
 * One fresh location reading (Location.Fix): the place, its accuracy in metres, Android's fake mark, the fix's own time
 * (satellite time for a GPS fix, in ms since 1970: the time proof's "GPS time"), the running timer at the fix and when
 * the answer was made, and the restart count.
 */
final readonly class LocationReading
{
    public function __construct(
        public float $latitude,
        public float $longitude,
        public float $accuracy,
        public bool $fake,
        public ?int $gpsTimeMs,
        public ?int $fixElapsedMs,
        public ?int $elapsedMs,
        public ?int $bootCount,
        public string $provider,
    ) {}

    /**
     * The reading of an `ok` answer; null for any other answer.
     */
    public static function fromEvent(LocationAnswered $event): ?self
    {
        if ($event->status !== 'ok' || $event->latitude === null || $event->longitude === null) {
            return null;
        }

        return new self(
            latitude: (float) $event->latitude,
            longitude: (float) $event->longitude,
            accuracy: (float) ($event->accuracy ?? 0),
            fake: (bool) $event->fake,
            gpsTimeMs: $event->gpsTimeMs,
            fixElapsedMs: $event->fixElapsedMs,
            elapsedMs: $event->elapsedMs,
            bootCount: $event->bootCount !== null && $event->bootCount >= 0 ? $event->bootCount : null,
            provider: (string) $event->provider,
        );
    }

    /**
     * Whether the fix came from the satellites (its time is then satellite time).
     */
    public function isGps(): bool
    {
        return $this->provider === 'gps';
    }

    /**
     * The fix's own time as ISO 8601 UTC, or null.
     */
    public function gpsTime(): ?string
    {
        return $this->gpsTimeMs !== null && $this->gpsTimeMs > 0
            ? gmdate('Y-m-d\TH:i:s', intdiv($this->gpsTimeMs, 1000)).sprintf('.%03dZ', $this->gpsTimeMs % 1000)
            : null;
    }
}
