<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\SignInScreen;

/**
 * Sign in, opened by the browser handing back (`<scheme>://signed-in`): it asks the office at once instead of waiting
 * for the next poll, as the base says the app does ({@see SignInScreen}).
 */
class SignedIn extends SignIn
{
    public function mount(): void
    {
        parent::mount();

        if (($this->state['step'] ?? null) === 'waiting' && ($this->state['go'] ?? null) === null) {
            $this->act('poll');
        }
    }
}
