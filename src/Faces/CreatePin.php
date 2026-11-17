<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\CreatePinScreen;

/**
 * The native face of Create PIN (step 3) ({@see CreatePinScreen}).
 */
class CreatePin extends ScreenFace
{
    protected function screenClass(): string
    {
        return CreatePinScreen::class;
    }
}
