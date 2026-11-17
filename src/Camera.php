<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Events\CameraAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * The app's own camera (bridge function Camera.Take, plan §4.6, §7.4, §7.5): a selfie with the front camera and the
 * face outline, or a photo with the back camera; never the gallery. Made small on the phone in Kotlin (the phone's PHP
 * has no gd): the long side at most `maxSide` px. The answer arrives as {@see CameraAnswered}; the photo is in the
 * app's no-backup folder: delete it after a good send ({@see self::forget()}).
 */
class Camera
{
    public function selfie(?string $id = null, int $maxSide = 1024): bool
    {
        return $this->take('front', true, $maxSide, $id);
    }

    public function photo(?string $id = null, int $maxSide = 1600): bool
    {
        return $this->take('back', false, $maxSide, $id);
    }

    public function take(string $lens = 'front', bool $outline = true, int $maxSide = 1280, ?string $id = null, int $quality = 80): bool
    {
        return (bool) (Bridge::call('Camera.Take', array_filter([
            'lens' => $lens === 'back' ? 'back' : 'front',
            'outline' => $outline,
            'max_side' => max(320, min(2048, $maxSide)),
            'quality' => max(40, min(95, $quality)),
            'id' => $id,
        ], fn ($value) => $value !== null))['started'] ?? false);
    }

    /**
     * Delete a photo this job made (only inside its own `sylloo-photos` folder).
     */
    public function forget(string $path): bool
    {
        $real = realpath($path);

        if ($real === false || ! str_contains(str_replace('\\', '/', $real), '/sylloo-photos/') || ! str_ends_with($real, '.jpg')) {
            return false;
        }

        return @unlink($real);
    }
}
