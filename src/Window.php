<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The cloud window's phone side (plan §7.6). The window itself is the element `<native:sylloo-window>`
 * (Elements\CloudWindow); its rules are Window\WindowSession's. Window.Clear removes what the window keeps (cookies,
 * page storage, cache): after a release or a deactivation (device-api §3).
 */
class Window
{
    public function clear(): bool
    {
        return (bool) (Bridge::call('Window.Clear')['cleared'] ?? false);
    }
}
