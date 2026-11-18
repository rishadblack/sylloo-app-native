<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * The answer of Sms.Permission (SEND_SMS and READ_PHONE_STATE): `status` granted | denied.
 */
class SmsPermissionAnswered implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $status,
        public ?string $id = null,
    ) {}
}
