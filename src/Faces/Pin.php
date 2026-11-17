<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\PinScreen;

/**
 * The native face of the PIN pad (step 3) ({@see PinScreen}).
 */
class Pin extends ScreenFace
{
    protected function screenClass(): string
    {
        return PinScreen::class;
    }
}
