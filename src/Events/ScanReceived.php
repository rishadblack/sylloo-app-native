<?php

namespace Rishadblack\SyllooAppNative\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Native\Mobile\Events\Contracts\DispatchableFromNative;

/**
 * One code from a scanner: `source` keys (a scanner that types, Scan.Keys: the key strokes grouped by the phone) or
 * camera (Scan.Camera, with the barcode `format`, e.g. ean_13, code_128, qr_code, and the caller's `id`). The code is
 * only data: the till decides what it is (a product barcode, a scale label, a serial number).
 *
 * `continuous` (camera only, null from a scanner that types): whether continuous scan was on for this code. False: this
 * was the one code, the screen is closing.
 */
class ScanReceived implements DispatchableFromNative
{
    use Dispatchable;

    public function __construct(
        public string $code,
        public string $source = 'keys',
        public ?string $format = null,
        public ?string $id = null,
        public ?bool $continuous = null,
    ) {}
}
