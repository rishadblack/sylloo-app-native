<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Qr.Scan: `status` scanned (with `text`) | cancelled | denied | failed.
 */
class QrAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public ?string $text = null,
    ) {}
}
