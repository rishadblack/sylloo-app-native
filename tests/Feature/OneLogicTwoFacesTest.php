<?php

use Illuminate\Support\Sleep;
use Rishadblack\SyllooAppBase\Livewire as Web;
use Rishadblack\SyllooAppBase\Screens\Handover;
use Rishadblack\SyllooAppBase\Screens\Hooks;
use Rishadblack\SyllooAppBase\Screens\Question;
use Rishadblack\SyllooAppBase\Testing\PretendOffice;
use Rishadblack\SyllooAppBase\Testing\RecordingBrowser;
use Rishadblack\SyllooAppBase\Testing\TestDevice;
use Rishadblack\SyllooAppNative\Faces;

/*
 * One logic, two faces (plan §4.6 part 1): every shared screen, at each of its steps, offers the person the same fields
 * and the same buttons in the base's web face and in this package's native face, and both are exactly what the base's
 * screen logic (its ScreenView: Field, Button) says. The faces add nothing and leave nothing out.
 */

beforeEach(function () {
    Sleep::fake();
    $this->office = PretendOffice::start();
});

dataset('screens and steps', function () {
    $code = ['code' => '1234-5678-1234-5678'];

    return [
        'activate: the code' => [Web\Activate::class, Faces\Activate::class, fn () => TestDevice::bind(activated: false), [], 'code'],
        'activate: the confirm card' => [Web\Activate::class, Faces\Activate::class, fn () => TestDevice::bind(activated: false), [[$code, 'activate']], 'confirm'],
        'activate: every device in use' => [Web\Activate::class, Faces\Activate::class, function (PretendOffice $office) {
            TestDevice::bind(activated: false);
            $office->refuse('activate', 'device_limit_reached_app', 409);
        }, [[$code, 'activate']], 'limit'],
        'activate: the app\'s question' => [Web\Activate::class, Faces\Activate::class, function () {
            TestDevice::bind(activated: false);
            app(Hooks::class)->ask('activate', 'reset', fn () => new Question('Reset terminal for the new code?', 'Another business.', 'Reset', 'Cancel', true, detail: '14 sales are safe', lines: [['text' => 'Sales waiting', 'count' => 0]]));
        }, [[$code, 'activate']], 'code'],
        'sign in: choose' => [Web\SignIn::class, Faces\SignIn::class, function () {
            RecordingBrowser::bind();
            TestDevice::bind();
        }, [], 'choose'],
        'sign in: the people signed in here' => [Web\SignIn::class, Faces\SignIn::class, function () {
            RecordingBrowser::bind();
            $device = TestDevice::bind();
            $device->addMember('member-1', 'Rahim');
            $device->addMember('member-2', 'Karim');
        }, [], 'choose'],
        'sign in: waiting for the browser' => [Web\SignIn::class, Faces\SignIn::class, function () {
            RecordingBrowser::bind();
            TestDevice::bind();
        }, [[[], 'browser']], 'waiting'],
        'create PIN' => [Web\CreatePin::class, Faces\CreatePin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember(pin: null)->memberKey());
        }, [], null],
        'PIN pad' => [Web\Pin::class, Faces\Pin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember()->memberKey());
        }, [], null],
        'PIN pad: a wrong PIN' => [Web\Pin::class, Faces\Pin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember()->memberKey());
        }, [[['pin' => '2468'], 'enter']], null],
        'lock' => [Web\Lock::class, Faces\Lock::class, function () {
            $device = TestDevice::bind();
            $device->enter($device->addMember());
        }, [], null],
        'lock: sign out asks first' => [Web\Lock::class, Faces\Lock::class, function () {
            $device = TestDevice::bind();
            $device->enter($device->addMember());
        }, [[[], 'sign_out']], null],
        'state: plan ended, with the app\'s lines' => [Web\State::class, Faces\State::class, function () {
            RecordingBrowser::bind();
            TestDevice::bind(['blocked' => 'plan_ended']);
            app(Hooks::class)->lines('state.plan_ended', fn () => [['text' => 'Sales are safe on this phone', 'count' => 14, 'variant' => 'success']]);
        }, [], null],
        'state: business paused' => [Web\State::class, Faces\State::class, fn () => TestDevice::bind(['blocked' => 'business_suspended']), [], null],
        'state: employee app off, office down' => [Web\State::class, Faces\State::class, function (PretendOffice $office) {
            TestDevice::bind(['blocked' => 'employee_app_off']);
            $office->down();
        }, [[[], 'check_again']], null],
        'businesses' => [Web\Businesses::class, Faces\Businesses::class, fn () => TestDevice::bind(), [], null],
        'businesses: multi-business' => [Web\Businesses::class, Faces\Businesses::class, function () {
            config(['sylloo-app-base.app.multi_business' => true]);
            TestDevice::bind();
        }, [], null],
        'PIN and lock settings' => [Web\PinSettings::class, Faces\PinSettings::class, function () {
            $device = TestDevice::bind();
            $device->enter($device->addMember());
        }, [], null],
        'PIN and lock settings: deactivate asks first' => [Web\PinSettings::class, Faces\PinSettings::class, function () {
            $device = TestDevice::bind();
            $device->enter($device->addMember());
        }, [[[], 'deactivate']], null],
        'about' => [Web\About::class, Faces\About::class, fn () => TestDevice::bind(), [], null],
        'language' => [Web\Language::class, Faces\Language::class, fn () => TestDevice::bind(), [], null],
    ];
});

it('offers the same fields and buttons in both faces, as the base says', function (string $web, string $native, Closure $world, array $steps, ?string $step) {
    ['web' => $webFace, 'native' => $nativeFace] = bothFaces(fn () => $world($this->office), $web, $native, $steps);

    $screen = screenOf($nativeFace);
    $webState = $webFace->get('state');
    $nativeState = $nativeFace->get('state');

    // Both faces stand at the same place of the screen.
    expect($nativeState['step'] ?? null)->toBe($webState['step'] ?? null)
        ->and($nativeState['question'] ?? null)->toEqual($webState['question'] ?? null);

    if ($step !== null) {
        expect($nativeState['step'])->toBe($step);
    }

    $base = baseOffer($screen, $nativeState);
    $nativeOffer = nativeOffer($nativeFace);
    $webOffer = webOffer($webFace, $screen);

    // The native face: exactly the base's fields, actions, tiles and button labels.
    expect($nativeOffer)->toEqual($base);

    // The web face: exactly the same fields, actions and tiles, and every label.
    expect($webOffer['fields'])->toEqual($base['fields'])
        ->and($webOffer['actions'])->toEqual($base['actions'])
        ->and($webOffer['tiles'])->toEqual($base['tiles']);

    foreach ($base['labels'] as $label) {
        expect($webOffer['html'])->toContain($label);
    }
})->with('screens and steps');

dataset('journeys', function () {
    $code = ['code' => '1234-5678-1234-5678'];

    return [
        'activate: a short code' => [Web\Activate::class, Faces\Activate::class, fn () => TestDevice::bind(activated: false), [[['code' => '1234'], 'activate']], ['step' => 'code', 'go' => null, 'errors' => ['code' => 'The business code has 16 digits.']]],
        'activate: code, confirm, continue' => [Web\Activate::class, Faces\Activate::class, fn () => TestDevice::bind(activated: false), [[$code, 'activate'], [[], 'continue']], ['step' => 'confirm', 'go' => 'sign_in']],
        'activate: refused' => [Web\Activate::class, Faces\Activate::class, function (PretendOffice $office) {
            TestDevice::bind(activated: false);
            $office->refuse('activate', 'code_invalid', 422);
        }, [[$code, 'activate']], ['step' => 'code', 'go' => null]],
        'sign in: browser, then allowed' => [Web\SignIn::class, Faces\SignIn::class, function () {
            RecordingBrowser::bind();
            TestDevice::bind();
        }, [[[], 'browser'], [[], 'poll']], ['step' => 'waiting', 'go' => 'create_pin']],
        'sign in: a name tapped' => [Web\SignIn::class, Faces\SignIn::class, function () {
            RecordingBrowser::bind();
            TestDevice::bind()->addMember('member-1', 'Rahim');
        }, [[[], 'pick', 'member-1']], ['go' => 'pin']],
        'create PIN: two different PINs' => [Web\CreatePin::class, Faces\CreatePin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember(pin: null)->memberKey());
        }, [[['pin' => '1357', 'pin_confirmation' => '2468'], 'save']], ['go' => null, 'errors' => ['pin_confirmation' => 'The two PINs are not the same.']]],
        'create PIN: saved' => [Web\CreatePin::class, Faces\CreatePin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember(pin: null)->memberKey());
        }, [[['pin' => '1357', 'pin_confirmation' => '1357'], 'save']], ['go' => 'home']],
        'PIN pad: wrong, then right' => [Web\Pin::class, Faces\Pin::class, function () {
            Handover::put('member', TestDevice::bind()->addMember()->memberKey());
        }, [[['pin' => '2468'], 'enter'], [['pin' => '1357'], 'enter']], ['go' => 'home']],
        'state: business paused, checked again' => [Web\State::class, Faces\State::class, fn () => TestDevice::bind(['blocked' => 'business_suspended']), [[[], 'check_again']], ['go' => 'home']],
        'state: office down' => [Web\State::class, Faces\State::class, function (PretendOffice $office) {
            TestDevice::bind(['blocked' => 'employee_app_off']);
            $office->down();
        }, [[[], 'check_again']], ['go' => null, 'toast' => ['text' => 'Sylloo can not be reached. Check the internet connection and try again.', 'variant' => 'warning']]],
    ];
});

it('ends the same way in both faces: the same step, errors, message, question and next screen', function (string $web, string $native, Closure $world, array $steps, array $expected) {
    ['web' => $webFace, 'native' => $nativeFace] = bothFaces(fn () => $world($this->office), $web, $native, $steps);

    $outcome = fn (array $state) => [
        'step' => $state['step'],
        'errors' => $state['errors'],
        'problem' => $state['problem'],
        'go' => $state['go'],
        'opens' => filled($state['open']),
        'toast' => $state['toast'],
        'question' => $state['question'],
    ];

    expect($outcome($nativeFace->get('state')))->toEqual($outcome($webFace->get('state')));
    expect($outcome($nativeFace->get('state')))->toMatchArray($expected);
})->with('journeys');
