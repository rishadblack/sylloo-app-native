<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\LockScreen;

/**
 * The native face of the Lock screen ({@see LockScreen}).
 */
class Lock extends ScreenFace
{
    protected function screenClass(): string
    {
        return LockScreen::class;
    }
}
