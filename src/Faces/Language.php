<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\LanguageScreen;

/**
 * The native face of the language choice ({@see LanguageScreen}).
 */
class Language extends ScreenFace
{
    protected function screenClass(): string
    {
        return LanguageScreen::class;
    }
}
