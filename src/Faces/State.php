<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\StateScreen;

/**
 * The native face of the state screens ({@see StateScreen}): every shared state and every state an app registers
 * (StateRegistry), with the counted lines an app adds at `state.<name>`. Releasing runs by itself until the device is
 * clean.
 */
class State extends ScreenFace
{
    use PollsTheScreen;

    protected function screenClass(): string
    {
        return StateScreen::class;
    }
}
