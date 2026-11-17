<?php

namespace Rishadblack\SyllooAppNative\Elements;

use Native\Mobile\Edge\CallbackRegistry;
use Native\Mobile\Edge\Element;
use Rishadblack\SyllooAppNative\Window\WindowSession;

/**
 * The cloud window element (`<native:sylloo-window>`, plan §7.6), drawn on Android by this package's own web view
 * (`WindowRenderer.kt`): the business's pages between the native bars, with no access to the phone. Its rules come
 * from the shared base's WindowRules through {@see WindowSession}:
 *
 *     <native:sylloo-window class="w-full flex-1" :src="$window['src']" :hosts="$window['hosts']"
 *         :paths="$window['paths']" :allow-http="$window['allow_http']" :keep-alive-path="$window['keep_alive_path']"
 *         :keep-alive-seconds="$window['keep_alive_seconds']"
 *
 *         @leave="leave" @ended="ended" @navigated="navigated" @failed="failed" />
 *
 * Each callback gets one text: `leave` an address for the normal browser, `ended` why the session ended, `navigated`
 * the address shown, `failed` the error code of a page that did not load.
 */
class CloudWindow extends Element
{
    protected string $type = 'sylloo_window';

    /** @var array<string, mixed> */
    protected array $windowProps = [];

    /** @var array<string, string> */
    protected array $callbacks = [];

    public const EVENTS = ['leave', 'ended', 'navigated', 'failed'];

    public static function make(string $src = ''): static
    {
        $element = new static;
        $element->windowProps['src'] = $src;

        return $element;
    }

    /**
     * @return string[]
     */
    public static function elementEvents(): array
    {
        return self::EVENTS;
    }

    public function applyAttributes(array $attrs): void
    {
        if (isset($attrs['src'])) {
            $this->windowProps['src'] = (string) $attrs['src'];
        }

        foreach (['hosts', 'paths'] as $list) {
            if (isset($attrs[$list])) {
                $this->windowProps[$list] = array_values(array_filter((array) $attrs[$list], 'is_string'));
            }
        }

        foreach (['allow-http' => 'allow_http', 'allowHttp' => 'allow_http'] as $attr => $prop) {
            if (isset($attrs[$attr])) {
                $this->windowProps[$prop] = filter_var($attrs[$attr], FILTER_VALIDATE_BOOLEAN);
            }
        }

        foreach (['keep-alive-path' => 'keep_alive_path', 'keepAlivePath' => 'keep_alive_path'] as $attr => $prop) {
            if (isset($attrs[$attr])) {
                $this->windowProps[$prop] = (string) $attrs[$attr];
            }
        }

        foreach (['keep-alive-seconds' => 'keep_alive_seconds', 'keepAliveSeconds' => 'keep_alive_seconds'] as $attr => $prop) {
            if (isset($attrs[$attr])) {
                $this->windowProps[$prop] = max(30, (int) $attrs[$attr]);
            }
        }

        foreach (self::EVENTS as $event) {
            if (isset($attrs['_'.$event])) {
                $this->callbacks[$event] = (string) $attrs['_'.$event];
            }
        }

        $this->applyA11yAttributes($attrs);
    }

    public function on(string $event, string $method): static
    {
        if (in_array($event, self::EVENTS, true)) {
            $this->callbacks[$event] = $method;
        }

        return $this;
    }

    protected function resolveProps(CallbackRegistry $registry): array
    {
        $props = $this->windowProps + ['hosts' => [], 'paths' => [], 'allow_http' => false, 'keep_alive_path' => '', 'keep_alive_seconds' => 240];

        foreach ($this->callbacks as $event => $method) {
            $props['on_'.$event] = $registry->register($method);
        }

        return $props;
    }
}
