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
            // Phone SMS (only with features.sms_send on).
            'messages_accept' => '/messages/accept',
            'messages' => '/messages',
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

    /*
     * The jobs a till needs and other apps do not (the Android till plan §3.4, §7). Each is off unless the app turns it
     * on. The build hook (copy_assets) reads these at build time: it writes them into the app (`res/values/
     * sylloo_features.xml`, so the Kotlin job refuses too) and adds the Android permissions only a switched-on job needs.
     *
     * - print: Print.Receipt / Print.Printers / Print.Drawer (receipt picture to an ESC/POS printer over Bluetooth, USB
     *   or the network; the cash drawer). On: the build adds BLUETOOTH_CONNECT (Android 12+) and BLUETOOTH (Android 11
     *   and older); USB needs no manifest permission (Android asks the person per printer). Off: none of them.
     * - scan_keys: Scan.Keys (a scanner that types, while no field has the cursor). Needs no permission.
     * - scan_camera: Scan.Camera (product barcodes and QR codes with the back camera, one or many in a row). Uses the
     *   CAMERA permission the package already declares for Qr.Scan / Camera.Take; on, the build adds the optional
     *   camera feature lines.
     * - sms_send: Sms.Send / Sms.Sims / Sms.Permission and the background job Messages.* (the business's SMS sent from
     *   this phone's SIM, phone-messaging-plan.md). On: the build adds SEND_SMS, READ_PHONE_STATE (the SIM list), the
     *   exact alarm, the foreground notice and its receivers; and the screens `messages_accept` / `messages` get their
     *   native routes. Off: none of them (the base's config `messages.enabled` must be on too).
     * - notice_schedule: Notice.Schedule / Notice.Cancel / Notice.Opened (the phone's own timed notices, e.g. CRM
     *   follow-up reminders, shown at their minute also offline and with the app closed). On: the build adds the exact
     *   alarm, the restart receiver and the notice's small screens. Off: none of them, and every call answers
     *   `not_set_up`.
     * - calls_recent: Calls.Recent / Calls.Permission / Calls.Status (the work calls of the call list: only the calls
     *   whose number ends in one of the app's compare keys are handed over; crm-call-recording-plan.md CR2). On: the
     *   build adds READ_CALL_LOG. Off: not in the APK, every call answers `not_set_up`.
     * - calls_ended: Calls.Ended (wakes the app's background command a few seconds after a call ends, also with the
     *   app closed). On: the build adds READ_PHONE_STATE (written once when phone SMS has it too) and the PHONE_STATE
     *   receiver. Off: neither.
     * - calls_recording: Calls.Folder / Calls.FolderStatus / Calls.Recording / Calls.RecordingDrop (the phone's own call
     *   recorder's file of a saved work call, found in the one folder the person chose and copied for the app to send;
     *   crm-call-recording-plan.md CR3). Needs no manifest permission (Android's folder chooser gives the read grant);
     *   off, every call answers `not_set_up`.
     *
     * A change needs a new build (the permissions are in the APK).
     */
    'features' => [
        'print' => (bool) env('SYLLOO_NATIVE_PRINT', false),
        'scan_keys' => (bool) env('SYLLOO_NATIVE_SCAN_KEYS', false),
        'scan_camera' => (bool) env('SYLLOO_NATIVE_SCAN_CAMERA', false),
        'sms_send' => (bool) env('SYLLOO_NATIVE_SMS_SEND', false),
        'notice_schedule' => (bool) env('SYLLOO_NATIVE_NOTICE_SCHEDULE', false),
        'calls_recent' => (bool) env('SYLLOO_NATIVE_CALLS_RECENT', false),
        'calls_ended' => (bool) env('SYLLOO_NATIVE_CALLS_ENDED', false),
        'calls_recording' => (bool) env('SYLLOO_NATIVE_CALLS_RECORDING', false),
    ],

    /*
     * Scan.Keys: how a scanner's typing is told from a person's. Characters at most `gap_ms` apart, ended by Enter, or
     * by `end_ms` of quiet when `end_on_gap`; at least `min_length` characters.
     */
    'scan_keys' => [
        'gap_ms' => 60,
        'end_ms' => 150,
        'min_length' => 4,
        'end_on_gap' => true,
    ],

];
