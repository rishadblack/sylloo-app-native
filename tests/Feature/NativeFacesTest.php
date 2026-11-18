<?php

use Illuminate\Http\Client\Request;
use Illuminate\Support\Sleep;
use Native\Mobile\Edge\Element;
use Native\Mobile\Edge\Elements\Column;
use Native\Mobile\Edge\NativeComponent;
use Native\Mobile\Edge\NativeRouter;
use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppBase\Contracts\AppUpdater;
use Rishadblack\SyllooAppBase\Livewire as Web;
use Rishadblack\SyllooAppBase\Pin\Presence;
use Rishadblack\SyllooAppBase\Screens\Handover;
use Rishadblack\SyllooAppBase\Screens\Hooks;
use Rishadblack\SyllooAppBase\Screens\Question;
use Rishadblack\SyllooAppBase\Testing\PretendOffice;
use Rishadblack\SyllooAppBase\Testing\RecordingBrowser;
use Rishadblack\SyllooAppBase\Testing\TestDevice;
use Rishadblack\SyllooAppNative\Contracts\QrScanner;
use Rishadblack\SyllooAppNative\Events\QrAnswered;
use Rishadblack\SyllooAppNative\Faces;
use Rishadblack\SyllooAppNative\Faces\NativePieces;
use Rishadblack\SyllooAppNative\Faces\NativeRoutes;
use Rishadblack\SyllooAppNative\Faces\ScanQr;

beforeEach(function () {
    Sleep::fake();
    $this->office = PretendOffice::start();
    $this->browser = RecordingBrowser::bind();
    app(NativePieces::class)->flush();
});

describe('Activate', function () {
    it('activates with the typed code and shows the confirm card with LIVE and EMPLOYEE', function () {
        $device = TestDevice::bind(activated: false);

        Native::test(Faces\Activate::class)
            ->assertSee('Activate this device')
            ->set('fields.code', '1234-5678-1234-5678')
            ->tap('Activate this device')
            ->assertSee('Device activated')
            ->assertSee('Demo Ltd')
            ->assertSee('LIVE')
            ->assertSee('EMPLOYEE')
            ->tap('Continue')
            ->assertReplacedWith('/sign-in');

        expect($device->state->isActivated())->toBeTrue();
        $this->office->assertCalled('activate', fn (Request $request) => $request['code'] === '1234567812345678');
    });

    it('runs Activate from the keyboard\'s done with the text typed', function () {
        TestDevice::bind(activated: false);

        Native::test(Faces\Activate::class)
            ->call('submit', 'code', '1234-5678-1234-5678')
            ->assertSee('Device activated');
    });

    it('shows the base\'s sentence at the code', function () {
        TestDevice::bind(activated: false);

        Native::test(Faces\Activate::class)
            ->set('fields.code', '1234')
            ->tap('Activate this device')
            ->assertSee('The business code has 16 digits.');

        $this->office->assertNotCalled('activate');
    });

    it('takes the code of an activation link', function () {
        TestDevice::bind(activated: false);
        NativeRoutes::register();

        Native::visit('/activate?code=1111222233334444')->assertSet('fields.code', '1111-2222-3333-4444');
    });

    it('asks the app\'s question in a modal, and sends nothing on No', function () {
        TestDevice::bind(activated: false);
        app(Hooks::class)->ask('activate', 'reset', fn () => new Question(
            'Reset terminal for the new code?', 'Another business.', 'Reset', 'Cancel', true,
            detail: '14 sales are safe', lines: [['text' => 'Sales waiting to be sent', 'count' => 0, 'variant' => 'success']],
        ));

        $face = Native::test(Faces\Activate::class)
            ->set('fields.code', '1234-5678-1234-5678')
            ->tap('Activate this device')
            ->assertElement('modal')
            ->assertSee('Reset terminal for the new code?')
            ->assertSee('14 sales are safe')
            ->assertSee('Sales waiting to be sent')
            ->assertMissingElement('button', fn (array $node) => ($node['props']['label'] ?? null) === 'Activate this device');

        $face->tap('Cancel')->assertMissingElement('modal')->assertSee('Activate this device');
        $this->office->assertNotCalled('activate');

        $face->set('fields.code', '1234-5678-1234-5678')->tap('Activate this device')->tap('Reset')->assertSee('Device activated');
        $this->office->assertCalled('activate');
    });

    it('offers Scan QR when the app adds it, and says to type the code until the scanner is there', function () {
        TestDevice::bind(activated: false);
        ScanQr::addTo(app(NativePieces::class));

        Native::test(Faces\Activate::class)
            ->assertSee('Scan QR code')
            ->tap('Scan QR code')
            ->assertNativeCalled('Dialog.Toast', fn ($params) => str_contains(json_encode($params), 'type the code'));
    });

    it('activates at once with the office\'s activation QR, also in an app whose own scheme is another', function () {
        TestDevice::bind(activated: false);
        config()->set('sylloo-app-base.app.link_scheme', 'sylloo-pos');
        ScanQr::addTo(app(NativePieces::class));
        $scanner = bindScanner();

        Native::test(Faces\Activate::class)
            ->tap('Scan QR code')
            ->emitNative(QrAnswered::class, ['status' => 'scanned', 'id' => ScanQr::ID, 'text' => 'sylloo-app://activate?code=1111222233334444'])
            ->assertSet('fields.code', '1111-2222-3333-4444')
            ->assertSee('Device activated')
            ->assertSee('Demo Ltd');

        expect($scanner->ids)->toBe([ScanQr::ID]);
        $this->office->assertCalled('activate', fn ($request) => $request['code'] === '1111222233334444');
    });

    it('asks the app\'s question before a scanned activation too, and sends nothing on No', function () {
        TestDevice::bind(activated: false);
        ScanQr::addTo(app(NativePieces::class));
        bindScanner();
        app(Hooks::class)->ask('activate', 'reset', fn () => new Question('Reset terminal for the new code?', 'Another business.', 'Reset', 'Cancel', true));

        Native::test(Faces\Activate::class)
            ->emitNative(QrAnswered::class, ['status' => 'scanned', 'id' => ScanQr::ID, 'text' => 'sylloo-app://activate?code=1111222233334444'])
            ->assertElement('modal')
            ->assertSee('Reset terminal for the new code?')
            ->tap('Cancel')
            ->assertMissingElement('modal')
            ->assertSet('fields.code', '1111-2222-3333-4444');

        $this->office->assertNotCalled('activate');
    });

    it('only fills the field with 16 digits alone, and the person presses Activate', function () {
        TestDevice::bind(activated: false);
        ScanQr::addTo(app(NativePieces::class));
        bindScanner();

        Native::test(Faces\Activate::class)
            ->emitNative(QrAnswered::class, ['status' => 'scanned', 'id' => ScanQr::ID, 'text' => '1111-2222-3333-4444'])
            ->assertSet('fields.code', '1111-2222-3333-4444')
            ->assertElement('outlined_text_input', fn ($node) => ($node['props']['value'] ?? null) === '1111-2222-3333-4444')
            ->assertSee('Activate this device');

        $this->office->assertNotCalled('activate');
    });

    it('shows each field\'s value on the phone and takes what the person types', function () {
        TestDevice::bind(activated: false);

        Native::test(Faces\Activate::class)
            ->set('fields.device_name', 'Counter 1')
            ->assertElement('outlined_text_input', fn ($node) => ($node['props']['value'] ?? null) === 'Counter 1')
            ->input("__syncProperty('fields.code')", '1234-5678-1234-5678')
            ->assertSet('fields.code', '1234-5678-1234-5678')
            ->tap('Activate this device')
            ->assertSee('Device activated');
    });

    it('says when a scanned code is not an activation code, and leaves a cancelled scan alone', function () {
        TestDevice::bind(activated: false);
        ScanQr::addTo(app(NativePieces::class));
        bindScanner();

        Native::test(Faces\Activate::class)
            ->set('fields.code', '1234')
            ->emitNative(QrAnswered::class, ['status' => 'cancelled', 'id' => ScanQr::ID])
            ->assertSet('fields.code', '1234')
            ->emitNative(QrAnswered::class, ['status' => 'scanned', 'id' => 'another-scan', 'text' => '1111222233334444'])
            ->assertSet('fields.code', '1234')
            ->emitNative(QrAnswered::class, ['status' => 'scanned', 'id' => ScanQr::ID, 'text' => 'https://example.com/p/1111222233334444'])
            ->assertSet('fields.code', '1234')
            ->assertNativeCalled('Dialog.Toast', fn ($params) => str_contains(json_encode($params), 'not an activation code'));

        $this->office->assertNotCalled('activate');
    });

    it('says to type the code when the camera is refused or does not start', function (string $status, string $says) {
        TestDevice::bind(activated: false);
        ScanQr::addTo(app(NativePieces::class));
        bindScanner();

        Native::test(Faces\Activate::class)
            ->emitNative(QrAnswered::class, ['status' => $status, 'id' => ScanQr::ID])
            ->assertNativeCalled('Dialog.Toast', fn ($params) => str_contains(json_encode($params), $says));
    })->with([
        'refused' => ['denied', 'camera is not allowed'],
        'did not start' => ['failed', 'could not start'],
    ]);
});

describe('Sign in', function () {
    it('opens the browser, waits, and asks for a new PIN once allowed', function () {
        TestDevice::bind();

        $face = Native::test(Faces\SignIn::class)
            ->assertSee('Sign in with browser')
            ->tap('Sign in with browser')
            ->assertSee('Waiting for your browser');

        expect($this->browser->last())->toStartWith('https://demo.sylloo.app/employee/device/sign-in/');

        $this->office->pending();
        $face->firePolls()->assertSee('Waiting for your browser')->assertNoNavigation();

        $this->office->answer('sign-in/finish', $this->office->example('sign-in/finish'));
        $face->firePolls()->assertNoNavigation();   // asked a moment ago: it waits the base's poll time

        $this->travel(3)->seconds();
        $face->firePolls()->assertReplacedWith('/create-pin');
    });

    it('asks the office at once when the browser hands back', function () {
        TestDevice::bind();

        $face = Native::test(Faces\SignIn::class)->tap('Sign in with browser');
        $state = $face->get('state');

        // The hand-back opens the signed-in face, which starts where Sign in stands (the base keeps the request).
        Native::test(Faces\SignedIn::class)->assertReplacedWith('/create-pin');

        expect($state['step'])->toBe('waiting');
    });

    it('opens the PIN of the person tapped', function () {
        TestDevice::bind()->addMember('member-1', 'Rahim');

        Native::test(Faces\SignIn::class)
            ->assertSee('Rahim')
            ->tap('Rahim')
            ->assertReplacedWith('/pin');

        expect(Handover::get('member'))->toBe('member-1');
    });
});

describe('PIN', function () {
    it('creates the PIN and goes home', function () {
        $device = TestDevice::bind();
        $member = $device->addMember(pin: null);
        Handover::put('member', $member->memberKey());

        Native::test(Faces\CreatePin::class)
            ->set('fields.pin', '1357')
            ->set('fields.pin_confirmation', '1357')
            ->call('act', 'save')
            ->assertReplacedWith('/');

        expect(app(Presence::class)->isSignedIn())->toBeTrue();
    });

    it('signs in with the pad: each key adds a digit, OK runs Enter', function () {
        Handover::put('member', TestDevice::bind()->addMember()->memberKey());

        Native::test(Faces\Pin::class)
            ->tap('1')->tap('3')->tap('5')
            ->assertSet('fields.pin', '135')
            ->call('padDone', 'pin')
            ->assertNoNavigation()
            ->tap('7')
            ->assertSet('fields.pin', '1357')
            ->call('padDone', 'pin')
            ->assertReplacedWith('/');
    });

    it('runs Enter by itself at the most digits, and clears a wrong PIN', function () {
        config(['sylloo-app-native.screens.pin_pad.max' => 4]);
        Handover::put('member', TestDevice::bind()->addMember()->memberKey());

        $face = Native::test(Faces\Pin::class);

        foreach (str_split('2468') as $digit) {
            $face->call('pad', 'pin', $digit);
        }

        $face->assertSet('fields.pin', '')->assertNoNavigation();

        foreach (str_split('1357') as $digit) {
            $face->call('pad', 'pin', $digit);
        }

        $face->assertReplacedWith('/');
    });

    it('erases the last digit', function () {
        Handover::put('member', TestDevice::bind()->addMember()->memberKey());

        Native::test(Faces\Pin::class)
            ->call('pad', 'pin', '1')->call('pad', 'pin', '2')
            ->call('padErase', 'pin')
            ->assertSet('fields.pin', '1');
    });

    it('opens again from the lock with the PIN of the person who is in', function () {
        $device = TestDevice::bind();
        $device->enter($device->addMember());

        Native::test(Faces\Lock::class)
            ->assertSee('Locked')
            ->set('fields.pin', '1357')
            ->call('padDone', 'pin')
            ->assertReplacedWith('/');

        expect(app(Presence::class)->isLocked())->toBeFalse();
    });
});

describe('state screens', function () {
    it('shows the plan ended with the app\'s counted lines and opens the portal to renew', function () {
        TestDevice::bind(['plan' => ['active' => false, 'ends_on' => '2026-10-01', 'in_grace' => false, 'valid_until' => '2026-10-08'], 'business_portal_url' => 'https://sylloo.eu/businesses/1/billing']);
        app(Hooks::class)->lines('state.plan_ended', fn () => [['text' => 'Sales are safe on this phone', 'count' => 14, 'variant' => 'success']]);

        $face = Native::test(Faces\State::class)
            ->assertSee('Plan ended')
            ->assertSee('Sales are safe on this phone')
            ->assertSee('14');

        $face->call('act', 'renew_plan');

        expect($this->browser->last())->toBe('https://sylloo.eu/businesses/1/billing');
    });

    it('goes home when the office says the device may work again', function () {
        TestDevice::bind(['blocked' => 'business_suspended']);

        Native::test(Faces\State::class)
            ->assertSee('Business paused')
            ->call('act', 'check_again')
            ->assertReplacedWith('/');
    });

    it('goes home at once when nothing stops the device', function () {
        TestDevice::bind();

        Native::test(Faces\State::class)->assertReplacedWith('/');
    });
});

describe('the other screens', function () {
    it('lists the businesses and opens the sign-in of the one tapped', function () {
        TestDevice::bind();

        Native::test(Faces\Businesses::class)
            ->assertSee('XYZ Trading')
            ->tap('XYZ Trading')
            ->assertReplacedWith('/sign-in');
    });

    it('changes the language with the select (label in, key kept)', function () {
        TestDevice::bind();

        Native::test(Faces\Language::class)
            ->call('choose', 'language', 'বাংলা')
            ->assertSet('fields.language', 'bn');
    });

    it('shows About, with "Check for updates" only when the app can update itself', function () {
        TestDevice::bind();

        Native::test(Faces\About::class)->assertSee('Sylloo App')->assertSee('XYZ Trading')->assertSee('LIVE');

        app()->instance(AppUpdater::class, Mockery::mock(AppUpdater::class));

        $native = Native::test(Faces\About::class);
        $web = Livewire\Livewire::test(Web\About::class);
        $screen = screenOf($native);

        expect(nativeOffer($native))->toEqual(baseOffer($screen, $native->get('state')))
            ->and(nativeOffer($native)['actions'])->toBe(['update_now'])
            ->and(webOffer($web, $screen)['actions'])->toBe(['update_now']);
    });

    it('opens About and Language from the top bar', function () {
        TestDevice::bind();

        Native::test(Faces\Businesses::class)->call('openScreen', 'about')->assertNavigatedTo('/about');
    });
});

it('registers a native route for every shared screen, and leaves out the ones the app turns off', function () {
    config(['sylloo-app-native.screens.paths.businesses' => false]);

    expect(NativeRoutes::path('activate'))->toBe('/activate')
        ->and(NativeRoutes::path('home'))->toBe('/')
        ->and(NativeRoutes::path('businesses'))->toBeNull();

    TestDevice::bind(activated: false);
    NativeRoutes::register();

    Native::visit('/activate')->assertScreen(Faces\Activate::class);
    Native::visit('/about')->assertScreen(Faces\About::class);

    $routes = array_map(fn ($route) => $route['class'] ?? null, NativeRouter::registeredRoutes());

    foreach (NativeRoutes::FACES as $screen => $face) {
        // Businesses: the app left it out above; the phone SMS faces: only with features.sms_send (PhoneSmsTest).
        if ($screen !== 'businesses' && ! isset(NativeRoutes::OPTIONAL[$screen])) {
            expect($routes)->toContain($face);
        }
    }
});

it('says which shared screen an app screen needs first, as the base\'s sylloo.device middleware does', function () {
    TestDevice::bind(activated: false);
    expect(Faces\ReadyDevice::screen())->toBe('activate');

    TestDevice::bind(['blocked' => 'business_suspended']);
    expect(Faces\ReadyDevice::screen())->toBe('state');

    $device = TestDevice::bind();
    expect(Faces\ReadyDevice::screen())->toBe('sign_in');

    $device->enter($device->addMember());
    expect(Faces\ReadyDevice::screen())->toBeNull();

    app(Presence::class)->lock();
    expect(Faces\ReadyDevice::screen())->toBe('lock');
});

it('hands a screen over from mount() before NativePHP made its callback list, without the last frame failing', function () {
    TestDevice::bind(activated: false);

    // As the phone's router makes a screen: mount() runs before the runloop sets up $nativeCallbacks.
    $screen = new class extends NativeComponent
    {
        use Faces\NeedsReadyDevice;

        public bool $ready = true;

        public function mount(): void
        {
            $this->ready = $this->deviceReady();
        }

        public function render(): Element
        {
            return new Column;
        }
    };

    $callbacks = new ReflectionProperty(NativeComponent::class, 'nativeCallbacks');
    expect($callbacks->isInitialized($screen))->toBeFalse();

    $screen->mount();

    expect($screen->ready)->toBeFalse()
        ->and($callbacks->isInitialized($screen))->toBeTrue();
});

function bindScanner(): object
{
    $scanner = new class implements QrScanner
    {
        /** @var list<string> */
        public array $ids = [];

        public function start(string $id): bool
        {
            $this->ids[] = $id;

            return true;
        }
    };

    app()->instance(QrScanner::class, $scanner);

    return $scanner;
}
