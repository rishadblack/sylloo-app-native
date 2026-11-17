<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\SignInScreen;

/**
 * The native face of Sign in (step 2) ({@see SignInScreen}): the name tiles, "Sign in with browser" (a Chrome tab),
 * and the waiting screen, which asks the office again by itself.
 */
class SignIn extends ScreenFace
{
    use PollsTheScreen;

    protected function screenClass(): string
    {
        return SignInScreen::class;
    }
}
