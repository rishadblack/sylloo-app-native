<?php

use Livewire\Features\SupportTesting\Testable;
use Livewire\Livewire;
use Native\Mobile\Testing\Native;
use Native\Mobile\Testing\TestableComponent;
use Rishadblack\SyllooAppBase\Screens\Hooks;
use Rishadblack\SyllooAppBase\Screens\Screen;
use Rishadblack\SyllooAppBase\Screens\ScreenState;
use Rishadblack\SyllooAppNative\Tests\TestCase;

pest()->extend(TestCase::class)->in('Feature');

/**
 * The base screen a native face draws.
 *
 * @return class-string<Screen>
 */
function screenOf(TestableComponent $face): string
{
    $instance = $face->instance();

    return (fn () => $this->screenClass())->call($instance);
}

/**
 * The callback expressions wired in a native face's current tree, by callback id.
 *
 * @return array<int, string>
 */
function nativeExpressions(TestableComponent $face): array
{
    return array_flip((fn () => $this->callbacks()->expressions())->call($face));
}

/**
 * What a native face offers the person now, read from its rendered tree and the callbacks wired in it:
 * - fields: `native:model="fields.<name>"` bindings, PIN pad keys (`pad('<name>', …)`), select choices
 *   (`choose('<name>')`), and a tiles field when its tiles are there;
 * - actions: `act('x')` (buttons, the question's answers), a tile's `act('x', 'key')`, a PIN pad's OK
 *   (`padDone('<name>')` runs its field's action);
 * - tiles: the tiles' keys;
 * - labels: the labels of the buttons that run a screen action.
 *
 * @return array{fields: list<string>, actions: list<string>, tiles: list<string>, labels: list<string>}
 */
function nativeOffer(TestableComponent $face): array
{
    $expressions = nativeExpressions($face);
    $view = app(screenOf($face))->view(ScreenState::fromArray($face->get('state')));
    $ids = [];
    $buttons = [];

    // A callback sits in the props (inputs, buttons) or on the node itself (a pressable row, a top bar action).
    $walk = function (array $node) use (&$walk, &$ids, &$buttons): void {
        foreach ([...($node['props'] ?? []), ...$node] as $key => $value) {
            if (str_starts_with((string) $key, 'on_') && is_int($value)) {
                $ids[] = $value;
            }
        }

        $press = $node['props']['on_press'] ?? $node['on_press'] ?? null;

        if (($node['type'] ?? null) === 'button' && is_int($press)) {
            $buttons[] = [$press, (string) ($node['props']['label'] ?? '')];
        }

        foreach ($node['children'] ?? [] as $child) {
            $walk($child);
        }
    };
    $walk($face->tree());

    $fieldActions = [];

    foreach ($view->fields as $field) {
        $fieldActions[$field->name] = $field->action;
    }

    $fields = [];
    $actions = [];
    $tiles = [];
    $tileFields = [];

    foreach (array_unique($ids) as $id) {
        $expression = $expressions[$id] ?? '';

        if (preg_match("/^__syncProperty\('fields\.([a-z_]+)'\)$/", $expression, $m) === 1
            || preg_match("/^(?:pad|padErase|choose)\('([a-z_]+)'/", $expression, $m) === 1) {
            $fields[] = $m[1];
        } elseif (preg_match("/^padDone\('([a-z_]+)'\)$/", $expression, $m) === 1) {
            $fields[] = $m[1];
            $actions[] = (string) ($fieldActions[$m[1]] ?? '');
        } elseif (preg_match("/^act\('([a-z_]+)', '([^']*)'\)$/", $expression, $m) === 1) {
            $actions[] = $m[1];
            $tiles[] = $m[2];
            $tileFields[] = $m[1];
        } elseif (preg_match("/^act\('([a-z_]+)'\)$/", $expression, $m) === 1) {
            $actions[] = $m[1];
        }
    }

    foreach ($view->fields as $field) {
        if ($field->type === 'tiles' && in_array($field->action, $tileFields, true)) {
            $fields[] = $field->name;
        }
    }

    $labels = [];

    foreach ($buttons as [$id, $label]) {
        if (preg_match("/^act\('[a-z_]+'\)$/", $expressions[$id] ?? '') === 1) {
            $labels[] = $label;
        }
    }

    return [
        'fields' => sorted($fields),
        'actions' => sorted(array_filter($actions)),
        'tiles' => sorted($tiles),
        'labels' => sorted($labels),
    ];
}

/**
 * What the base's web face offers the person now, read from its HTML: `wire:model="fields.<name>"` (and a tiles field
 * when its tiles are there), the actions it runs (`run('x')`, a tile's `run('x', 'key')`, the PIN pad's
 * `$wire.run('x')`; not the `wire:poll`, which no person presses), the tiles' keys, and the whole HTML (for the labels).
 *
 * @return array{fields: list<string>, actions: list<string>, tiles: list<string>, html: string}
 */
function webOffer(Testable $face, string $screen): array
{
    $html = html_entity_decode($face->html(), ENT_QUOTES);
    $view = app($screen)->view(ScreenState::fromArray($face->get('state')));
    $offered = (string) preg_replace("/wire:poll[^=]*=\"run\('poll'\)\"/", '', $html);

    preg_match_all('/wire:model(?:\.[a-z.]+)?="fields\.([a-z_]+)"/', $offered, $models);
    preg_match_all("/run\('([a-z_]+)'(?:,\s*'([^']*)')?\)/", $offered, $runs, PREG_SET_ORDER);

    $fields = $models[1];
    $actions = [];
    $tiles = [];
    $tileFields = [];

    foreach ($runs as $run) {
        $actions[] = $run[1];

        if (($run[2] ?? '') !== '') {
            $tiles[] = $run[2];
            $tileFields[] = $run[1];
        }
    }

    foreach ($view->fields as $field) {
        if ($field->type === 'tiles' && in_array($field->action, $tileFields, true)) {
            $fields[] = $field->name;
        }
    }

    return [
        'fields' => sorted($fields),
        'actions' => sorted($actions),
        'tiles' => sorted($tiles),
        'html' => $html,
    ];
}

/**
 * What the base's logic offers in a state: its fields, its actions (not the poll, unless a button runs it too), the
 * tiles' keys and the button labels.
 * While a question is open: no fields, only the two answers.
 *
 * @param  class-string<Screen>  $screen
 * @param  array<string, mixed>  $state
 * @return array{fields: list<string>, actions: list<string>, tiles: list<string>, labels: list<string>}
 */
function baseOffer(string $screen, array $state): array
{
    $state = ScreenState::fromArray($state);

    if ($state->question !== null) {
        return [
            'fields' => [],
            'actions' => ['answer_no', 'answer_yes'],
            'tiles' => [],
            'labels' => sorted([$state->question['cancel'], $state->question['confirm']]),
        ];
    }

    $view = app($screen)->view($state);
    $buttons = array_map(fn ($button) => $button->action, $view->buttons);
    $tiles = [];

    foreach ($view->fields as $field) {
        if ($field->type === 'tiles') {
            $tiles = [...$tiles, ...array_map('strval', array_keys($field->options))];
        }
    }

    return [
        'fields' => sorted(array_map(fn ($field) => $field->name, $view->fields)),
        'actions' => sorted(in_array('poll', $buttons, true) ? $view->actions() : array_diff($view->actions(), ['poll'])),
        'tiles' => sorted($tiles),
        'labels' => sorted(array_map(fn ($button) => $button->label, $view->buttons)),
    ];
}

/**
 * @param  iterable<string>  $values
 * @return list<string>
 */
function sorted(iterable $values): array
{
    $values = array_values(array_unique([...$values]));
    sort($values);

    return $values;
}

/**
 * Open one screen in both faces, each in a fresh world, and walk the same steps in each: the web face (the base's
 * Livewire page) and the native face (this package's). Each step is the fields typed, then the action run.
 *
 * @param  Closure(): void  $world  builds the device, the office's answers and the app's hooks
 * @param  class-string  $web  the base's web face
 * @param  class-string  $native  this package's native face
 * @param  list<array{0: array<string, string>, 1: string, 2?: string|null}>  $steps
 * @return array{web: Testable, native: TestableComponent}
 */
function bothFaces(Closure $world, string $web, string $native, array $steps = []): array
{
    $fresh = function () use ($world): void {
        session()->flush();
        app(Hooks::class)->flush();
        $world();
    };

    $fresh();
    $webFace = Livewire::test($web);

    foreach ($steps as $step) {
        foreach ($step[0] as $field => $value) {
            $webFace->set('fields.'.$field, $value);
        }

        $webFace->call('run', $step[1], $step[2] ?? null);
    }

    $fresh();
    $nativeFace = Native::test($native);

    foreach ($steps as $step) {
        foreach ($step[0] as $field => $value) {
            $nativeFace->set('fields.'.$field, $value);
        }

        $nativeFace->call('act', $step[1], $step[2] ?? null);
    }

    return ['web' => $webFace, 'native' => $nativeFace];
}
