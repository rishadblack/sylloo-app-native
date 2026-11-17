<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\ActivateScreen;

/**
 * The native face of Activate (step 1) ({@see ActivateScreen}). Its confirm card shows the STAFF / EMPLOYEE mark; an app adds "Scan QR code" at `activate.below_code` ({@see NativePieces}).
 */
class Activate extends ScreenFace
{
    protected function screenClass(): string
    {
        return ActivateScreen::class;
    }
}
