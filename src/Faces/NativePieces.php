<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Closure;

/**
 * What an app adds at a hook place of a native face (plan §4.4 "Hooks"), the native counterpart of the base's
 * Hooks::add() pieces (those are HTML, for the web faces). A piece is a native Blade view (`<native:*>` tags) drawn at
 * its place with the screen's `$context`; a button in it runs a piece action with `@press="piece('name')"`. The base's
 * counted lines (Hooks::lines()) and questions (Hooks::ask()) need nothing here: the native faces draw them already.
 *
 *     app(NativePieces::class)
 *         ->add('activate.below_code', 'sylloo-app-native::pieces.scan-qr')
 *         ->action('scan_qr', fn (ScreenFace $face) => $face->fill('code', '1111-2222-3333-4444'));
 *
 * Bound as a singleton; register in a service provider's boot().
 */
class NativePieces
{
    /** @var array<string, list<string|Closure(array<string, mixed>): ?string>> */
    protected array $pieces = [];

    /** @var array<string, Closure(ScreenFace, mixed...): void> */
    protected array $actions = [];

    /**
     * Draw a native view at a place: a view name, or a closure that returns one for the context (or null).
     *
     * @param  string|Closure(array<string, mixed>): ?string  $view
     */
    public function add(string $place, string|Closure $view): static
    {
        $this->pieces[$place][] = $view;

        return $this;
    }

    /**
     * The view names to draw at a place now.
     *
     * @param  array<string, mixed>  $context
     * @return list<string>
     */
    public function for(string $place, array $context = []): array
    {
        $views = [];

        foreach ($this->pieces[$place] ?? [] as $piece) {
            $view = is_string($piece) ? $piece : $piece($context);

            if (is_string($view) && $view !== '') {
                $views[] = $view;
            }
        }

        return $views;
    }

    /**
     * A piece action: what a piece's button does, with the face (to fill a field, show a message).
     *
     * @param  Closure(ScreenFace, mixed...): void  $run
     */
    public function action(string $name, Closure $run): static
    {
        $this->actions[$name] = $run;

        return $this;
    }

    public function hasAction(string $name): bool
    {
        return isset($this->actions[$name]);
    }

    public function run(ScreenFace $face, string $name, mixed ...$args): void
    {
        if (isset($this->actions[$name])) {
            ($this->actions[$name])($face, ...$args);
        }
    }

    /**
     * Forget every piece and action (tests).
     */
    public function flush(): void
    {
        $this->pieces = [];
        $this->actions = [];
    }
}
