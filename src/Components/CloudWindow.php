<?php

namespace Rishadblack\SyllooAppNative\Components;

use Native\Mobile\Edge\Components\Native\NativeBladeComponent;

/**
 * `<native:sylloo-window …/>`: see {@see \Rishadblack\SyllooAppNative\Elements\CloudWindow}.
 */
class CloudWindow extends NativeBladeComponent
{
    protected bool $isSelfClosing = true;

    protected function elementType(): string
    {
        return 'sylloo_window';
    }
}
