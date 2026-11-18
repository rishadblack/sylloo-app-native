<?php

namespace Rishadblack\SyllooAppNative\Contracts;

/**
 * Whether a person on this phone chose "Use your fingerprint" (plan B3: the PIN always, a fingerprint or face only as
 * a shortcut). The app keeps the choice with the person's sign-in (and removes it with it).
 */
interface FingerprintChoices
{
    public function isOn(string $entryId, string $memberKey): bool;

    public function set(string $entryId, string $memberKey, bool $on): void;
}
