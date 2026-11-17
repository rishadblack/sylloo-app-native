<?php

namespace Rishadblack\SyllooAppNative\Tests;

use Illuminate\Support\Facades\Blade;
use Illuminate\Support\ServiceProvider;
use Jantinnerezo\LivewireAlert\LivewireAlertServiceProvider;
use Livewire\LivewireServiceProvider;
use Native\Mobile\Edge\ElementRegistry;
use Native\Mobile\Edge\NativeTagPrecompiler;
use Native\Mobile\NativeServiceProvider;
use Native\Mobile\UI\NativeUIServiceProvider;
use Orchestra\Testbench\TestCase as Orchestra;
use Rishadblack\SyllooAppBase\Rest\WaitingList;
use Rishadblack\SyllooAppBase\SyllooAppBaseServiceProvider;
use Rishadblack\SyllooAppNative\SyllooAppNativeServiceProvider;
use Rishadblack\WireBootstrap\WireBootstrapServiceProvider;
use Rishadblack\WireModals\WireModalsServiceProvider;

/**
 * A bare phone app: the base (with its web faces, for the "one logic, two faces" checks), NativePHP Mobile with its UI
 * plugin, and this package.
 */
abstract class TestCase extends Orchestra
{
    protected function setUp(): void
    {
        parent::setUp();

        WaitingList::forgetAllowed();
        $this->registerNativeUiElements();
    }

    /**
     * What an app's NativeServiceProvider::plugins() does for nativephp/mobile-ui and this package: their elements and
     * Blade tags (a bare Testbench app has no plugins list, so NativePHP finds no plugin by itself).
     */
    protected function registerNativeUiElements(): void
    {
        $components = [];

        foreach ([dirname(__DIR__).'/vendor/nativephp/mobile-ui/nativephp.json', dirname(__DIR__).'/nativephp.json'] as $file) {
            $manifest = json_decode((string) file_get_contents($file), true);
            $components = [...$components, ...($manifest['components'] ?? [])];
        }

        foreach ($components as $component) {
            if (class_exists($component['element']) && ! ElementRegistry::has($component['type'])) {
                ElementRegistry::register($component['type'], $component['element']);
            }

            if (is_array($component['element_events'] ?? null)) {
                NativeTagPrecompiler::registerElementEvents($component['element_events']);
            }

            if (class_exists($component['blade'])) {
                Blade::component('native-'.str_replace(['.', '_'], '-', $component['type']), $component['blade']);
            }
        }
    }

    /**
     * @return array<int, class-string<ServiceProvider>>
     */
    protected function getPackageProviders($app): array
    {
        return [
            LivewireServiceProvider::class,
            WireBootstrapServiceProvider::class,
            WireModalsServiceProvider::class,
            LivewireAlertServiceProvider::class,
            SyllooAppBaseServiceProvider::class,
            NativeServiceProvider::class,
            NativeUIServiceProvider::class,
            SyllooAppNativeServiceProvider::class,
        ];
    }

    protected function defineEnvironment($app): void
    {
        $app['config']->set('app.key', 'base64:'.base64_encode(random_bytes(32)));
        $app['config']->set('logging.default', 'null');
        $app['config']->set('nativephp.version', '2.0.0');
        $app['config']->set('database.default', 'testing');
        $app['config']->set('sylloo-app-base.app.name', 'Sylloo App');
    }
}
