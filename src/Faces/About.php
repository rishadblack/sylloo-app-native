<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\AboutScreen;

/**
 * The native face of About ({@see AboutScreen}).
 */
class About extends ScreenFace
{
    protected function screenClass(): string
    {
        return AboutScreen::class;
    }
}
