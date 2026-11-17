<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Illuminate\Support\Facades\Date;
use Native\Mobile\Attributes\Locked;
use Native\Mobile\Attributes\Poll;
use Rishadblack\SyllooAppBase\Screens\ScreenState;

/**
 * The faces of screens that ask again by themselves (Sign in waiting for the browser, Releasing): while the base's
 * view has a `poll` time and no question is open, run `poll` every that many seconds (at least 2), as the web face's
 * `wire:poll` does. Only these faces wake every second; the others never poll.
 */
trait PollsTheScreen
{
    #[Locked]
    public float $polledAt = 0.0;

    #[Poll(1000)]
    public function tick(): void
    {
        $state = ScreenState::fromArray($this->state);
        $seconds = $this->screen()->view($state)->poll;

        $now = Date::now()->getPreciseTimestamp(3) / 1000;

        if ($seconds === null || $state->question !== null || $now - $this->polledAt < max(2, $seconds)) {
            return;
        }

        $this->polledAt = $now;
        $this->act('poll');
    }
}
