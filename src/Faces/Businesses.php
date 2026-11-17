<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\BusinessesScreen;

/**
 * The native face of Businesses ({@see BusinessesScreen}).
 */
class Businesses extends ScreenFace
{
    protected function screenClass(): string
    {
        return BusinessesScreen::class;
    }
}
