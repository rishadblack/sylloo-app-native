<?php

namespace Rishadblack\SyllooAppNative\Facades;

use Illuminate\Support\Facades\Facade;
use Rishadblack\SyllooAppNative\Data\ClockReading;

/**
 * @method static ClockReading|null elapsed()
 *
 * @see \Rishadblack\SyllooAppNative\Clock
 */
class Clock extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Rishadblack\SyllooAppNative\Clock::class;
    }
}
