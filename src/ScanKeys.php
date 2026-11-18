<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\ScanReceived;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * A scanner that types (USB or Bluetooth keyboard mode) while no field has the cursor (bridge function Scan.Keys; the
 * Android till plan §3.4, §7.2). Off unless the app turns on `sylloo-app-native.features.scan_keys`. Once `on()`, the
 * phone groups fast key strokes ending in Enter (or a short quiet gap) into one code and sends it as
 * {@see ScanReceived}; a person's typing and typing into a focused text field are left alone. Settings from config
 * `sylloo-app-native.scan_keys`.
 *
 * Answers: `on` | `off` | `not_set_up` (feature off) | `failed` (no phone / no answer).
 */
class ScanKeys
{
    public const ON = 'on';

    public const OFF = 'off';

    public const NOT_SET_UP = 'not_set_up';

    public const FAILED = 'failed';

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.scan_keys', false);
    }

    public function on(): string
    {
        $settings = (array) config('sylloo-app-native.scan_keys', []);

        return $this->call([
            'on' => true,
            'gap_ms' => (int) ($settings['gap_ms'] ?? 60),
            'end_ms' => (int) ($settings['end_ms'] ?? 150),
            'min_length' => (int) ($settings['min_length'] ?? 4),
            'end_on_gap' => (bool) ($settings['end_on_gap'] ?? true),
        ]);
    }

    public function off(): string
    {
        return $this->call(['on' => false]);
    }

    /**
     * @param  array<string, mixed>  $parameters
     */
    protected function call(array $parameters): string
    {
        if (! $this->enabled()) {
            return self::NOT_SET_UP;
        }

        $status = Bridge::call('Scan.Keys', $parameters)['status'] ?? null;

        return in_array($status, [self::ON, self::OFF, self::NOT_SET_UP], true) ? $status : self::FAILED;
    }
}
