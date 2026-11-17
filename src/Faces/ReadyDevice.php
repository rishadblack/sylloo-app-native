<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Screens\FirstScreen;

/**
 * The native twin of the base's `sylloo.device` middleware, for the app's own native screens (native routes have no
 * HTTP middleware). Both ask the base's FirstScreen, so the order and the rules live once.
 */
final class ReadyDevice
{
    /**
     * The shared screen (a name of NativeRoutes::FACES) the phone must show first, or null when it is ready (then the
     * idle time starts again, as each page the person opens does on the web).
     */
    public static function screen(): ?string
    {
        return app(FirstScreen::class)->name();
    }
}
