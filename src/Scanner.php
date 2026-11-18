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
 * Each code arrives at once as {@see ScanReceived} (`source` camera, `format`, `id`, `continuous`). Continuous scan on:
 * the screen stays open for many in a row (a beep, a buzz and a green flash per read; an item held under the camera
 * counts once, the same code counts again after it was out of sight for 0.8 s; a Done button); off: the first read
 * closes it and only that code is sent.
 *
 * The screen has a "Continuous scan" switch next to the torch. The person's choice is kept on the phone (per app) and
 * wins next time: `$continuous` is only the first-time default. With `$switchable` false the switch is hidden and
 * `$continuous` alone decides (for a caller that always wants one code, such as a search field). When it closes: {@see ScanCameraClosed}.
 *
 * Answers: `started` | `not_set_up` (feature off) | `failed` (no phone / no answer).
 */
class Scanner
{
    public const STARTED = 'started';

    public const NOT_SET_UP = 'not_set_up';

    public const FAILED = 'failed';

    public const SHOWN = 'shown';

    public const NOT_OPEN = 'not_open';

    public const OK = 'ok';

    public const WARN = 'warn';

    public const ERROR = 'error';

    public const KINDS = [self::OK, self::WARN, self::ERROR];

    /** The camera screen shows at most this many letters (Kotlin's ScanAnswer.MAX). */
    public const ANSWER_MAX = 80;

    public function cameraEnabled(): bool
    {
        return (bool) config('sylloo-app-native.features.scan_camera', false);
    }

    public function camera(string $id, bool $continuous = true, bool $switchable = true): string
    {
        if (! $this->cameraEnabled()) {
            return self::NOT_SET_UP;
        }

        $status = Bridge::call('Scan.Camera', [
            'id' => $id,
            'continuous' => $continuous,
            'switchable' => $switchable,
            // The switch's text, its On / Off (TalkBack) and the Done button, in the app's language (the screen is Kotlin).
            'continuous_label' => (string) __('sylloo-app-native::pieces.scan_continuous'),
            'on_label' => (string) __('sylloo-app-native::pieces.scan_on'),
            'off_label' => (string) __('sylloo-app-native::pieces.scan_off'),
            'done_label' => (string) __('sylloo-app-native::pieces.scan_done'),
        ])['status'] ?? null;

        return in_array($status, [self::STARTED, self::NOT_SET_UP], true) ? $status : self::FAILED;
    }

    /**
     * One line back to the open camera screen, under the aim box for about 2 s, after a {@see ScanReceived}: "Added:
     * Rice 5 kg × 2" (`ok`, green), a warning (`warn`, amber) or "No product with code …" (`error`, red). `$id` is the
     * screen's id from {@see self::camera()} (null: whatever camera screen is open). Works for the till's own scans and
     * for the remote scanner alike.
     *
     * Answers: `shown` | `not_open` (no camera screen of that id is open) | `not_set_up` (feature off) | `failed`.
     */
    public function answer(?string $id, string $text, string $kind = self::OK): string
    {
        if (! $this->cameraEnabled()) {
            return self::NOT_SET_UP;
        }

        $text = trim((string) preg_replace('/\s+/u', ' ', $text));

        if ($text === '') {
            return self::FAILED;
        }

        $status = Bridge::call('Scan.Answer', array_filter([
            'id' => $id,
            'text' => mb_substr($text, 0, self::ANSWER_MAX),
            'kind' => in_array($kind, self::KINDS, true) ? $kind : self::OK,
        ], fn ($value) => $value !== null))['status'] ?? null;

        return in_array($status, [self::SHOWN, self::NOT_OPEN, self::NOT_SET_UP], true) ? $status : self::FAILED;
    }
}
