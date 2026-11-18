<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * Android's answer to Calls.Permission: `granted` (every permission of the switched-on call jobs) or `denied`, with
 * each one apart (`call_log`: the call list, `phone_state`: the end of a call).
 */
class CallsPermissionAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status = 'denied',
        public ?string $id = null,
        public bool $call_log = false,
        public bool $phone_state = false,
    ) {}
}
