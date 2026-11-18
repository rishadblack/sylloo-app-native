<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Android's folder chooser answered Calls.Folder: `picked` (the folder's short name; read access to that folder only,
 * kept across restarts), `cancelled` (the folder chosen before stays, its name if any) or `failed`.
 */
class CallsFolderAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status = 'cancelled',
        public ?string $id = null,
        public ?string $name = null,
    ) {}
}
