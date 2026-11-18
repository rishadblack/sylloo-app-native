<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;
use Rishadblack\SyllooAppNative\Notices;

/**
 * A tapped timed notice (Notice.Schedule) while the app runs: what to open (`open`, `open_id`), how (`action`: `open`
 * or `call`) and the notice's `id`. The same answer is kept once for {@see Notices::opened()}.
 */
class NoticeOpened implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $open = '',
        public ?string $open_id = null,
        public string $action = 'open',
        public ?string $id = null,
    ) {}
}
