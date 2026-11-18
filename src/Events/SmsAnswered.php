<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The outcome of Sms.Send: `status` sent | delivered (later, when the network said so) | failed (with `reason`:
 * no_signal, no_service, no_balance, wrong_number, radio_off, blocked, permission, other) | no_answer (Android gave no
 * answer in time: it may still have gone). The number and the text are never in it.
 */
class SmsAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public ?string $reason = null,
    ) {}
}
