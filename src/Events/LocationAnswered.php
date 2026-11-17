<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Location.Fix: `status` ok | timeout | denied | off; with ok the reading (see Data\LocationReading::fromEvent()).
 */
class LocationAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public ?float $latitude = null,
        public ?float $longitude = null,
        public ?float $accuracy = null,
        public bool $fake = false,
        public ?int $gpsTimeMs = null,
        public ?int $fixElapsedMs = null,
        public ?int $elapsedMs = null,
        public ?int $bootCount = null,
        public ?string $provider = null,
    ) {}
}
