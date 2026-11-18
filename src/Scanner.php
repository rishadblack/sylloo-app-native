<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\ScanCameraClosed;
use Rishadblack\SyllooAppNative\Events\ScanReceived;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Product barcodes and QR codes with the back camera (bridge function Scan.Camera; the Android till plan §3.4): the
 * package's own scan screen, CameraX + ZXing core (offline, no Google services), the same as {@see Qr}. Reads EAN-13,
 * EAN-8, UPC-A, UPC-E, Code 128, Code 39 and QR. Off unless the app turns on `sylloo-app-native.features.scan_camera`.
 *
 * Each code arrives at once as {@see ScanReceived} (`source` camera, `format`, `id`). With `continuous` the screen
 * stays open for many in a row (a beep and a buzz per read, the same code again within 1.5 s ignored, a Done button);
 * otherwise the first read closes it. When it closes: {@see ScanCameraClosed}.
 *
 * Answers: `started` | `not_set_up` (feature off) | `failed` (no phone / no answer).
 */
class Scanner
{
    public const STARTED = 'started';

    public const NOT_SET_UP = 'not_set_up';

    public const FAILED = 'failed';

    public function cameraEnabled(): bool
    {
        return (bool) config('sylloo-app-native.features.scan_camera', false);
    }

    public function camera(string $id, bool $continuous = true): string
    {
        if (! $this->cameraEnabled()) {
            return self::NOT_SET_UP;
        }

        $status = Bridge::call('Scan.Camera', ['id' => $id, 'continuous' => $continuous])['status'] ?? null;

        return in_array($status, [self::STARTED, self::NOT_SET_UP], true) ? $status : self::FAILED;
    }
}
