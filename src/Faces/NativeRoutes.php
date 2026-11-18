<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Illuminate\Support\Facades\Route;

/**
 * Where each native face lives (config `sylloo-app-native.screens.paths`), and where `home` and `back` lead. The app
 * calls {@see self::register()} in its routes/mobile.php; a path set to `false` leaves the screen to the app. The phone
 * SMS faces are drawn only with `features.sms_send` on.
 */
final class NativeRoutes
{
    /** The native face of each shared screen (the base's screen names), plus the browser's hand-back. */
    public const FACES = [
        'activate' => Activate::class,
        'sign_in' => SignIn::class,
        'signed_in' => SignedIn::class,
        'create_pin' => CreatePin::class,
        'pin' => Pin::class,
        'lock' => Lock::class,
        'state' => State::class,
        'businesses' => Businesses::class,
        'pin_settings' => PinSettings::class,
        'about' => About::class,
        'language' => Language::class,
        'messages_accept' => MessagesAccept::class,
        'messages' => Messages::class,
    ];

    /** Faces of a switched job: drawn only while its switch (`features.<name>`) is on. */
    public const OPTIONAL = [
        'messages_accept' => 'sms_send',
        'messages' => 'sms_send',
    ];

    public static function register(): void
    {
        foreach (self::FACES as $screen => $face) {
            $path = self::path($screen);

            if ($path !== null) {
                Route::native($path, $face);
            }
        }
    }

    /**
     * The native path of a screen, `home` (config `screens.home`), or null when the app left the screen out.
     */
    public static function path(string $screen): ?string
    {
        if ($screen === 'home') {
            return '/'.ltrim((string) config('sylloo-app-native.screens.home', '/'), '/');
        }

        if (isset(self::OPTIONAL[$screen]) && config('sylloo-app-native.features.'.self::OPTIONAL[$screen], false) !== true) {
            return null;
        }

        $path = config('sylloo-app-native.screens.paths.'.$screen);

        return is_string($path) && $path !== '' ? '/'.ltrim($path, '/') : null;
    }
}
