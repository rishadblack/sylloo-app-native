<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\PinSettingsScreen;

/**
 * The native face of PIN and lock settings ({@see PinSettingsScreen}).
 */
class PinSettings extends ScreenFace
{
    protected function screenClass(): string
    {
        return PinSettingsScreen::class;
    }
}
