<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The camera scanner (Scan.Camera) closed: `status` done (codes were read) | cancelled (closed before a read) | denied
 * (no camera permission) | failed (no camera); `count` the codes it sent as {@see ScanReceived}.
 */
class ScanCameraClosed implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public int $count = 0,
    ) {}
}
