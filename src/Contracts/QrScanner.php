<?php

namespace Rishadblack\SyllooAppNative\Contracts;

use Rishadblack\SyllooAppNative\Events\QrAnswered;
use Rishadblack\SyllooAppNative\Qr;

/**
 * Reads a QR code with the phone's camera, without internet and without Google services (plan §4.6 part 2, `Qr.Scan`).
 * Scanning is a screen of its own, so the answer comes later: {@see QrAnswered}
 * (`#[On(QrAnswered::class)]` on the screen that asked). The package's own is {@see Qr};
 * until an app binds a scanner, "Scan QR code" on Activate says to type the code.
 */
interface QrScanner
{
    /**
     * Open the scanner. True when it opened; the text arrives as a QrAnswered event with the same id.
     */
    public function start(string $id): bool;
}
