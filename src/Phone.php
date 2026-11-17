<?php

namespace Rishadblack\SyllooAppNative;

use Illuminate\Support\Facades\Cache;
use Native\Mobile\Facades\Device;

/**
 * About the phone itself: the name Activate sends as the device name (plan §7.2: "This will sign you out of Redmi
 * Note 12"). From NativePHP's own Device.GetInfo (no job of ours needed): the name the person gave the phone in its
 * settings, else maker and model ("Xiaomi Redmi Note 12"). Read once and kept.
 */
class Phone
{
    public const CACHE_KEY = 'sylloo-app-native.phone-name';

    public const MAX = 60;

    public function name(): ?string
    {
        $name = Cache::get(self::CACHE_KEY);

        if (is_string($name) && $name !== '') {
            return $name;
        }

        $name = $this->read();

        if ($name !== null) {
            Cache::forever(self::CACHE_KEY, $name);
        }

        return $name;
    }

    protected function read(): ?string
    {
        $info = json_decode((string) rescue(fn () => Device::getInfo(), '', false), true);

        if (! is_array($info)) {
            return null;
        }

        $name = trim((string) ($info['name'] ?? ''));

        if ($name === '') {
            $name = trim(((string) ($info['manufacturer'] ?? '')).' '.((string) ($info['model'] ?? '')));
        }

        $name = trim((string) preg_replace('/[\x00-\x1F\x7F]+/u', ' ', $name));

        return $name === '' ? null : mb_substr($name, 0, self::MAX);
    }
}
