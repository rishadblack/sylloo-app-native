<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Push.Token: `status` ok (with `token`) | changed (Firebase renewed the token) | not_set_up (no google-services.json in the build) | failed.
 */
class PushTokenAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
        public ?string $token = null,
    ) {}
}
