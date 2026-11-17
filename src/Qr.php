<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Contracts\QrScanner;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The phone's QR scanner (bridge function Qr.Scan, plan §4.6): its own camera screen, CameraX + ZXing core (Apache
 * 2.0, offline, no Google services). The text arrives as {@see Events\QrAnswered}; it is only data, the caller decides
 * what it means.
 */
class Qr implements QrScanner
{
    public function start(string $id): bool
    {
        return (bool) (Bridge::call('Qr.Scan', ['id' => $id])['started'] ?? false);
    }
}
