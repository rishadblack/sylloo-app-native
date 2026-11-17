<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\LockAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Android's fingerprint / face prompt (bridge functions Lock.Status and Lock.Prompt, plan §4.6, B3): a shortcut for
 * the PIN, never instead of it. The answer arrives as {@see LockAnswered}.
 */
class Lock
{
    /**
     * `available` | `none_enrolled` | `no_hardware` | `unavailable`; null off the phone.
     */
    public function status(): ?string
    {
        $status = Bridge::call('Lock.Status')['status'] ?? null;

        return is_string($status) ? $status : null;
    }

    public function isAvailable(): bool
    {
        return $this->status() === 'available';
    }

    /**
     * Show the prompt. True when it opened; its answer is the {@see LockAnswered} event.
     */
    public function prompt(string $title, ?string $subtitle = null, ?string $cancel = null, ?string $id = null): bool
    {
        return (bool) (Bridge::call('Lock.Prompt', array_filter([
            'title' => $title,
            'subtitle' => $subtitle,
            'cancel' => $cancel,
            'id' => $id,
        ], fn ($value) => $value !== null))['started'] ?? false);
    }
}
