<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Print.Receipt / Print.Drawer (and of Print.Printers' permission question): `status` printed | opened |
 * no_printer | not_permitted | failed | not_set_up (and permitted for `job` permission); `job` receipt | drawer |
 * permission; `reason` a short code. Read it with `Data\PrintOutcome::fromEvent()`.
 */
class PrintAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public string $job = 'receipt',
        public ?string $reason = null,
    ) {}
}
