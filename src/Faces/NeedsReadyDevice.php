<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Native\Mobile\Edge\CallbackRegistry;
use Rishadblack\SyllooAppBase\Language\Language;

/**
 * For an app's own native screen (a NativeComponent): call {@see self::deviceReady()} first in mount(); when the phone
 * is not ready it replaces the screen with the shared one it must show ({@see ReadyDevice}) and returns false.
 *
 *     public function mount(): void
 *     {
 *         if (! $this->deviceReady()) {
 *             return;
 *         }
 *         // … the screen's own work
 *     }
 */
trait NeedsReadyDevice
{
    protected function deviceReady(): bool
    {
        Language::apply();

        $screen = ReadyDevice::screen();

        if ($screen === null) {
            return true;
        }

        // Called from mount(), before NativePHP has made the screen's callback list: replace() draws a last frame of
        // this screen on the way out, which needs that list ("must not be accessed before initialization").
        $this->nativeCallbacks ??= new CallbackRegistry;
        $this->replace(NativeRoutes::path($screen) ?? '/');

        return false;
    }
}
