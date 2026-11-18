<?php

namespace Rishadblack\SyllooAppNative;

use Illuminate\Support\ServiceProvider;
use Rishadblack\SyllooAppBase\Contracts\MessagingPhone;
use Rishadblack\SyllooAppBase\Contracts\SecretStore;
use Rishadblack\SyllooAppNative\Commands\CopyAssetsCommand;
use Rishadblack\SyllooAppNative\Commands\SendWaitingCommand;
use Rishadblack\SyllooAppNative\Faces\NativePieces;

/**
 * Sylloo App's phone package: the native faces of the shared screens (Faces\*, view `sylloo-app-native::face`, routes
 * by Faces\NativeRoutes::register() in the app's routes/mobile.php, the app's native pieces in Faces\NativePieces) and
 * the Kotlin jobs (Clock, Secret, Lock, Location, Camera, Qr, Push, Sync, Update, Window, Phone; the switched Printer,
 * ScanKeys, Scanner, Sms, Messages, Notices, Calls and CallRecordings, off unless config `features` turns them on) and the cloud window
 * element (`<native:sylloo-window>`). The app binds what it uses (QrScanner, SecretStore, WaitingSender).
 */
class SyllooAppNativeServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__.'/../config/sylloo-app-native.php', 'sylloo-app-native');

        $this->app->singleton(Clock::class);
        $this->app->singleton(NativePieces::class);

        foreach ([Secret::class, Lock::class, Location::class, Camera::class, Qr::class, Push::class, Sync::class, Update::class, Window::class, Phone::class, Printer::class, ScanKeys::class, Scanner::class, Sms::class, Messages::class, Notices::class, Calls::class, CallRecordings::class] as $job) {
            $this->app->singleton($job);
        }

        // Phone SMS for the base's screens (an app may bind its own first); answers "not available" while
        // features.sms_send is off.
        if (interface_exists(MessagingPhone::class)) {
            $this->app->bindIf(MessagingPhone::class, fn ($app) => $app->make(Messages::class));
        }
    }

    public function boot(): void
    {
        $this->loadViewsFrom(__DIR__.'/../resources/views', 'sylloo-app-native');
        $this->loadTranslationsFrom(__DIR__.'/../resources/lang', 'sylloo-app-native');

        // Always: WorkManager's background run calls it through the ephemeral artisan.
        $this->commands([SendWaitingCommand::class]);

        if ($this->app->runningInConsole()) {
            $this->publishes([__DIR__.'/../config/sylloo-app-native.php' => config_path('sylloo-app-native.php')], 'sylloo-app-native-config');

            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}
