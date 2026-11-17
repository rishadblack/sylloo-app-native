<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * A tapped notice while the app runs: its address (check it with the window's rules before opening it).
 */
class PushOpened implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $url,
    ) {}
}
