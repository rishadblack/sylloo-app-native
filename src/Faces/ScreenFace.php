<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Illuminate\View\View;
use Native\Mobile\Attributes\Locked;
use Native\Mobile\Attributes\On;
use Native\Mobile\Edge\NativeComponent;
use Native\Mobile\Facades\Browser;
use Native\Mobile\Facades\Dialog;
use Rishadblack\SyllooAppBase\Contracts\BrowserOpener;
use Rishadblack\SyllooAppBase\Contracts\DeviceEntries;
use Rishadblack\SyllooAppBase\Language\Language;
use Rishadblack\SyllooAppBase\Screens\Field;
use Rishadblack\SyllooAppBase\Screens\Screen;
use Rishadblack\SyllooAppBase\Screens\ScreenState;
use Rishadblack\SyllooAppBase\Screens\ScreenView;
use Rishadblack\SyllooAppBase\Support\AppInfo;
use Rishadblack\SyllooAppNative\Events\LockAnswered;
use Rishadblack\SyllooAppNative\Events\QrAnswered;

/**
 * The native face of a shared screen (plan §4.6 part 1): the base's screen logic ({@see Screen}) drawn with NativePHP's
 * native components. It is the native twin of the base's web face (Livewire\ScreenPage) and does the same, no more: it
 * keeps the screen's state (locked: the native side can not change it) and the typed fields, sends each action to the
 * logic (`act('action')`, a tile's `act('pick', 'key')`), and does what the logic asks: go to a screen (replace), open
 * the browser (the app's BrowserOpener, else a Chrome tab), show a short message (a toast), ask a question (a modal).
 * It holds no rule and no sentence: every check, button and text comes from the base's screen.
 *
 * Every face draws with one view (`sylloo-app-native::face`). Only the drawing of the PIN pad lives here (its keys add
 * a digit and run the field's action at the most digits, as the web face's pad does).
 */
abstract class ScreenFace extends NativeComponent
{
    /** @var array<string, mixed> */
    #[Locked]
    public array $state = [];

    /** @var array<string, string> */
    public array $fields = [];

    /**
     * @return class-string<Screen>
     */
    abstract protected function screenClass(): string;

    public function mount(): void
    {
        Language::apply();

        $screen = $this->screen();
        $state = $screen->start($this->input());

        foreach ($screen->view($state)->fields as $field) {
            $value = $state->data[$field->name] ?? '';
            $this->fields[$field->name] = is_scalar($value) ? (string) $value : '';
        }

        $this->keep($state);
    }

    /**
     * Run an action of the screen: a button, a tile (`$value` is its key), the PIN pad's OK. A value an event appends
     * after it (a text input's text) is not used.
     */
    public function act(string $action, mixed $value = null): void
    {
        Language::apply();

        $screen = $this->screen();
        $state = $screen->act(ScreenState::fromArray($this->state), $action, $this->fields, is_scalar($value) ? (string) $value : null);

        foreach ($screen->view($state)->fields as $field) {
            if (in_array($field->type, ['pin', 'pin_pad'], true)) {
                $this->fields[$field->name] = '';
            }
        }

        $this->keep($state);
    }

    /**
     * A text field's keyboard "done": take its text (a stale debounced value can not win) and run the view's primary
     * action, as the web face's form submit does.
     */
    public function submit(string $field, mixed $text = null): void
    {
        if (is_string($text)) {
            $this->fields[$field] = $text;
        }

        $primary = $this->primaryAction();

        if ($primary !== null) {
            $this->act($primary);
        }
    }

    /**
     * A select's choice: the native select hands back the label, the base wants the key.
     */
    public function choose(string $field, mixed $label = null): void
    {
        $options = $this->field($field)?->options ?? [];
        $key = array_search((string) $label, array_map(fn ($option) => is_scalar($option) ? (string) $option : '', $options), true);

        if ($key !== false) {
            $this->fields[$field] = (string) $key;
        }
    }

    /**
     * A PIN pad key: one more digit; at the most digits the field's action runs by itself.
     */
    public function pad(string $field, string $digit): void
    {
        $pin = ($this->fields[$field] ?? '').preg_replace('/\D/', '', $digit);
        $max = self::padLimits()['max'];

        if (mb_strlen($pin) > $max) {
            return;
        }

        $this->fields[$field] = $pin;

        if (mb_strlen($pin) === $max) {
            $this->padDone($field);
        }
    }

    public function padErase(string $field): void
    {
        $this->fields[$field] = mb_substr($this->fields[$field] ?? '', 0, -1);
    }

    /**
     * The PIN pad's OK: the field's action, once the fewest digits are in.
     */
    public function padDone(string $field): void
    {
        $action = $this->field($field)?->action;

        if ($action !== null && mb_strlen($this->fields[$field] ?? '') >= self::padLimits()['min']) {
            $this->act($action);
        }
    }

    /**
     * A button of a piece an app added at a hook place ({@see NativePieces}).
     */
    public function piece(string $name, mixed ...$args): void
    {
        app(NativePieces::class)->run($this, $name, ...$args);
    }

    /**
     * The QR scanner's answer to Activate's "Scan QR code" ({@see ScanQr}).
     */
    #[On(QrAnswered::class, when: ['id' => ScanQr::ID])]
    public function qrAnswered(string $status, ?string $text = null): void
    {
        ScanQr::answered($this, $status, $text);
    }

    /**
     * The fingerprint prompt's answer to a piece of {@see Fingerprint} (lock screen shortcut, PIN settings switch).
     */
    #[On(LockAnswered::class)]
    public function lockAnswered(string $status, ?string $id = null): void
    {
        if ($id === Fingerprint::UNLOCK_ID || $id === Fingerprint::TURN_ON_ID) {
            Fingerprint::answered($this, $status, $id);
        }
    }

    /**
     * Go to the app's first screen (a piece does this after the fingerprint unlocked the phone).
     */
    public function goHome(): void
    {
        $this->goTo('home');
    }

    /**
     * Open another shared screen on top of this one (the top bar's About and Language, as the web layout's footer).
     */
    public function openScreen(string $screen): void
    {
        $path = NativeRoutes::path($screen);

        if ($path !== null) {
            $this->navigate($path);
        }
    }

    /**
     * Put a value in a field (a piece action does this: the scanned code into the code field).
     */
    public function fill(string $field, string $value): void
    {
        $this->fields[$field] = $value;
    }

    /**
     * Show a short message on the phone.
     */
    public function toast(string $text): void
    {
        Dialog::toast($text);
    }

    public function render(): View
    {
        Language::apply();

        $screen = $this->screen();
        $state = ScreenState::fromArray($this->state);
        $view = $screen->view($state);
        $context = $screen->context($state);
        $pieces = app(NativePieces::class);

        $places = [];

        foreach ($view->places as $place) {
            $places[$place] = $pieces->for($place, $context);
        }

        return view('sylloo-app-native::face', [
            'screen' => $screen->name(),
            'view' => $view,
            'fieldErrors' => $state->errors,
            'question' => $state->question,
            'context' => $context,
            'placeLines' => $screen->placeLines($state),
            'places' => $places,
            'header' => $this->header(),
            'pad' => self::padLimits(),
        ]);
    }

    /**
     * The top of every face, as the base's layout shows it: the business (or the app), LIVE / PRACTICE, the steps.
     *
     * @return array{title: string, practice: bool, mode: string|null}
     */
    protected function header(): array
    {
        $device = rescue(fn () => app(DeviceEntries::class)->current()->state, null, false);
        $activated = $device?->isActivated() ?? false;

        return [
            'title' => $activated ? (string) ($device->businessName() ?? AppInfo::name()) : AppInfo::name(),
            'practice' => $activated && $device->isPractice(),
            'mode' => $activated ? ($device->isPractice() ? 'practice' : 'live') : null,
        ];
    }

    protected function screen(): Screen
    {
        return app($this->screenClass());
    }

    /**
     * What the face was opened with: a deep link's query (`?code=`, `?state=`), as the web face reads `request()`.
     *
     * @return array<string, mixed>
     */
    protected function input(): array
    {
        return array_filter((array) $this->query(), 'is_string');
    }

    /**
     * Keep the state and do what it asks of the face.
     */
    protected function keep(ScreenState $state): void
    {
        $this->state = $state->toArray();

        if ($state->open !== null && $state->open !== '') {
            app()->bound(BrowserOpener::class)
                ? app(BrowserOpener::class)->open($state->open)
                : Browser::inApp($state->open);
        }

        if ($state->toast !== null) {
            $this->toast($state->toast['text']);
        }

        if ($state->go !== null) {
            $this->goTo($state->go);
        }
    }

    /**
     * Go to the screen the logic named (replacing this one: the steps are not a stack), `home`, or `back`.
     */
    protected function goTo(string $screen): void
    {
        if ($screen === 'back') {
            $this->back();

            return;
        }

        $this->replace(NativeRoutes::path($screen) ?? NativeRoutes::path('home') ?? '/');
    }

    protected function field(string $name): ?Field
    {
        foreach ($this->currentView()->fields as $field) {
            if ($field->name === $name) {
                return $field;
            }
        }

        return null;
    }

    protected function primaryAction(): ?string
    {
        foreach ($this->currentView()->buttons as $button) {
            if ($button->variant === 'primary') {
                return $button->action;
            }
        }

        return null;
    }

    protected function currentView(): ScreenView
    {
        return $this->screen()->view(ScreenState::fromArray($this->state));
    }

    /**
     * @return array{min: int, max: int}
     */
    public static function padLimits(): array
    {
        $min = max(1, (int) config('sylloo-app-native.screens.pin_pad.min', 4));

        return ['min' => $min, 'max' => max($min, (int) config('sylloo-app-native.screens.pin_pad.max', 6))];
    }
}
