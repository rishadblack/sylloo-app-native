<?php

use Illuminate\Support\Facades\Blade;
use Native\Mobile\Edge\CallbackRegistry;
use Rishadblack\SyllooAppBase\Window\WindowRules;
use Rishadblack\SyllooAppNative\Elements\CloudWindow;
use Rishadblack\SyllooAppNative\Window\WindowSession;

$cases = json_decode((string) file_get_contents(dirname(__DIR__).'/Fixtures/window-cases.json'), true)['cases'];
$byName = array_combine(array_column($cases, 'name'), array_map(fn ($case) => [$case], $cases));

function asEnvironment(bool $allowHttp): void
{
    app()->detectEnvironment(fn () => $allowHttp ? 'testing' : 'production');
}

describe('the shared address cases (the same file the Kotlin filter is tested with)', function () use ($byName) {
    it('WindowRules::mayShow agrees', function (array $case) {
        asEnvironment($case['allow_http']);

        expect(WindowRules::mayShow($case['url'], $case['hosts'], $case['paths']))->toBe($case['show']);
    })->with($byName);

    it('WindowSession::decide agrees, without and after a redirect', function (array $case) {
        asEnvironment($case['allow_http']);
        $session = new WindowSession('https://demo.sylloo.app/app/session?ticket=x', $case['hosts'], $case['paths'], false, $case['allow_http'], null, 240);

        expect($session->decide($case['url']))->toBe($case['decide'])
            ->and($session->decide($case['url'], true))->toBe($case['decide_redirect']);
    })->with($byName);
});

describe('WindowSession', function () {
    it('opens a staff ticket on a business host with the office rules and the keep-alive', function () {
        $session = WindowSession::fromTicket([
            'url' => 'https://demo.sylloo.app/app/session?ticket=abc',
            'rules' => ['allowed_paths' => ['/member', '/livewire'], 'idle_minutes' => 30, 'lock_required' => true, 'min_version' => '2.0.0'],
        ], ['live' => 'demo.sylloo.app', 'practice' => 'practice.sylloo.app']);

        expect($session->props())->toBe([
            'src' => 'https://demo.sylloo.app/app/session?ticket=abc',
            'hosts' => ['demo.sylloo.app', 'practice.sylloo.app'],
            'paths' => ['/member', '/livewire'],
            'allow-http' => true,
            'keep-alive-path' => '/member/app/keep-alive',
            'keep-alive-seconds' => 240,
        ])->and($session->idleMinutes)->toBe(30)
            ->and($session->lockRequired)->toBeTrue()
            ->and(WindowSession::fromArray($session->toArray()))->toEqual($session);
    });

    it('opens an employee ticket with the employee paths and the employee keep-alive', function () {
        $session = WindowSession::fromTicket(['url' => 'https://demo.sylloo.app/employee/app/session?ticket=abc'], ['live' => 'demo.sylloo.app'], employee: true);

        expect($session->paths)->toBe(WindowRules::EMPLOYEE_PATHS)
            ->and($session->keepAlivePath)->toBe('/employee/app/keep-alive');
    });

    it('refuses a ticket address it may not open', function (string $url, bool $employee) {
        expect(WindowSession::fromTicket(['url' => $url], ['live' => 'demo.sylloo.app'], $employee))->toBeNull();
    })->with([
        'another host' => ['https://example.com/app/session?ticket=x', false],
        'another path' => ['https://demo.sylloo.app/member?ticket=x', false],
        'staff path in the employee window' => ['https://demo.sylloo.app/app/session?ticket=x', true],
        'a user in it' => ['https://a@demo.sylloo.app/app/session?ticket=x', false],
    ]);

    it('refuses http in production', function () {
        app()->detectEnvironment(fn () => 'production');

        expect(WindowSession::fromTicket(['url' => 'http://demo.sylloo.app/app/session?ticket=x'], ['live' => 'demo.sylloo.app']))->toBeNull();
    });
});

describe('the window element', function () {
    it('sends its rules and callbacks to the renderer', function () {
        $element = CloudWindow::make();
        $element->applyAttributes([
            'src' => 'https://demo.sylloo.app/app/session?ticket=x',
            'hosts' => ['demo.sylloo.app', null],
            'paths' => ['/member'],
            'allow-http' => 'false',
            'keep-alive-path' => '/member/app/keep-alive',
            'keep-alive-seconds' => 5,
            '_leave' => 'leave',
            '_ended' => 'ended',
        ]);

        $registry = new CallbackRegistry;
        $props = (fn () => $this->resolveProps($registry))->call($element);

        expect($props)->toMatchArray([
            'src' => 'https://demo.sylloo.app/app/session?ticket=x',
            'hosts' => ['demo.sylloo.app'],
            'paths' => ['/member'],
            'allow_http' => false,
            'keep_alive_path' => '/member/app/keep-alive',
            'keep_alive_seconds' => 30,
        ])->and($props)->toHaveKeys(['on_leave', 'on_ended'])
            ->and($props)->not->toHaveKey('on_failed');
    });

    it('is the Blade tag <native:sylloo-window> with its events', function () {
        $html = Blade::render('<native:sylloo-window src="https://demo.sylloo.app/app/session?ticket=x" @leave="leave" />');

        expect(CloudWindow::elementEvents())->toBe(['leave', 'ended', 'navigated', 'failed'])
            ->and($html)->toBeString();
    });
});
