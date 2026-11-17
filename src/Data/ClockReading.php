<?php

namespace Rishadblack\SyllooAppNative\Data;

/**
 * One reading of the phone's running timer.
 */
final readonly class ClockReading
{
    public function __construct(
        public int $elapsedMs,
        public ?int $bootCount,
        public ?int $wallMs,
    ) {}
}
