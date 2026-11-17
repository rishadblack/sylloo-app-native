<?php

/*
 * The native faces of the shared screens (plan §4.6 part 1). The logic, the texts and the rules are the base's
 * (rishadblack/sylloo-app-base); this config only says where each face lives in the app's native routes.
 */
return [

    'screens' => [

        /*
         * The native path of each screen, as the base's screen names (Screens\ScreenRoutes). The app registers them with
         * Rishadblack\SyllooAppNative\Faces\NativeRoutes::register() in its routes/mobile.php (loaded only on the phone
         * and in tests). `false` leaves a screen out (the app draws it). A deep link `<scheme>://activate?code=…` opens
         * `/activate` with the code; `<scheme>://signed-in` (the browser handing back) opens `/signed-in`.
         */
        'paths' => [
            'activate' => '/activate',
            'sign_in' => '/sign-in',
            'signed_in' => '/signed-in',
            'create_pin' => '/create-pin',
            'pin' => '/pin',
            'lock' => '/lock',
            'state' => '/state',
            'businesses' => '/businesses',
            'pin_settings' => '/settings/pin',
            'about' => '/about',
            'language' => '/language',
        ],

        /*
         * Where `home` leads after sign-in (a native path; the app's first own screen).
         */
        'home' => '/',

        /*
         * The PIN pad: the fewest and the most digits (the base's PIN rules check the PIN itself).
         */
        'pin_pad' => ['min' => 4, 'max' => 6],
    ],

    /*
     * Push notices (Firebase Cloud Messaging, plan §7.9). The app's google-services.json is read at build time (the
     * copy_assets hook) and never goes into git. No file: the build has no push (Push.Token answers `not_set_up`).
     */
    'push' => [
        'google_services' => env('SYLLOO_GOOGLE_SERVICES_JSON', base_path('google-services.json')),
    ],

];
