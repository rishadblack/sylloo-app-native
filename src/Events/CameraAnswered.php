<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Camera.Take: `status` taken | cancelled | denied | failed; with taken the photo's `path` (the app's no-backup folder), size and lens.
 */
class CameraAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public ?string $path = null,
        public int $width = 0,
        public int $height = 0,
        public int $bytes = 0,
        public ?string $lens = null,
    ) {}
}
