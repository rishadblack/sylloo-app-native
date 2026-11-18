<?php

use Native\Mobile\Testing\Native;
use Rishadblack\SyllooAppBase\Contracts\DeviceEntries;
use Rishadblack\SyllooAppBase\Pin\Presence;
use Rishadblack\SyllooAppBase\Testing\TestDevice;
use Rishadblack\SyllooAppNative\Contracts\FingerprintChoices;
use Rishadblack\SyllooAppNative\Events\LockAnswered;
use Rishadblack\SyllooAppNative\Faces;
use Rishadblack\SyllooAppNative\Faces\Fingerprint;
use Rishadblack\SyllooAppNative\Faces\NativePieces;

/*
 * The fingerprint shortcut (plan B3, §7.2): the PIN always, a fingerprint or face only as a shortcut on the lock
 * screen, for a person who turned it on after the phone's own prompt said yes.
 */

beforeEach(function () {
    $this->bridge = Native::fakeBridge()->respondTo('Lock.Status', ['status' => 'available'])->respondTo('Lock.Prompt', ['started' => true]);
    app(NativePieces::class)->flush();
    Fingerprint::addTo(app(NativePieces::class));

    $this->choices = new class implements FingerprintChoices
    {
        /** @var array<string, bool> */
        public array $on = [];

        public function isOn(string $entryId, string $memberKey): bool
        {
            return $this->on[$entryId.'|'.$memberKey] ?? false;
        }

        public function set(string $entryId, string $memberKey, bool $on): void
        {
            $this->on[$entryId.'|'.$memberKey] = $on;
        }
    };
    app()->instance(FingerprintChoices::class, $this->choices);

    $this->device = TestDevice::bind();
    $this->device->enter($this->member = $this->device->addMember());
});

it('turns the shortcut on only after the phone\'s own prompt said yes', function () {
    $face = Native::test(Faces\PinSettings::class)
        ->assertSee('Use fingerprint next time')
        ->call('piece', 'fingerprint_on');

    $this->bridge->assertCalled('Lock.Prompt', fn (array $params) => $params['id'] === Fingerprint::TURN_ON_ID && $params['cancel'] === 'Use PIN');
    expect(Fingerprint::isOn())->toBeFalse();

    $face->emitNative(LockAnswered::class, ['status' => 'cancelled', 'id' => Fingerprint::TURN_ON_ID]);
    expect(Fingerprint::isOn())->toBeFalse();

    $face->emitNative(LockAnswered::class, ['status' => 'ok', 'id' => Fingerprint::TURN_ON_ID])
        ->assertSee('Stop using fingerprint');
    expect(Fingerprint::isOn())->toBeTrue();
});

it('opens the lock with the fingerprint of a person who chose it, and only with a yes', function () {
    $this->choices->set(app(DeviceEntries::class)->current()->id, $this->member->memberKey(), true);

    Native::test(Faces\Lock::class)
        ->assertSee('Use fingerprint')
        ->call('piece', 'fingerprint_unlock')
        ->emitNative(LockAnswered::class, ['status' => 'cancelled', 'id' => Fingerprint::UNLOCK_ID])
        ->assertNoNavigation();
    expect(app(Presence::class)->isLocked())->toBeTrue();

    $this->bridge->assertCalled('Lock.Prompt', fn (array $params) => $params['id'] === Fingerprint::UNLOCK_ID);

    Native::test(Faces\Lock::class)
        ->emitNative(LockAnswered::class, ['status' => 'ok', 'id' => Fingerprint::UNLOCK_ID])
        ->assertReplacedWith('/');
    expect(app(Presence::class)->isLocked())->toBeFalse();
});

it('shows no shortcut to a person who did not choose it, or when the phone cannot do it, and a yes then opens nothing', function () {
    Native::test(Faces\Lock::class)
        ->assertDontSee('Use fingerprint')
        ->emitNative(LockAnswered::class, ['status' => 'ok', 'id' => Fingerprint::UNLOCK_ID])
        ->assertNoNavigation();
    expect(app(Presence::class)->isLocked())->toBeTrue();

    $this->choices->set(app(DeviceEntries::class)->current()->id, $this->member->memberKey(), true);
    $this->bridge->respondTo('Lock.Status', ['status' => 'none_enrolled']);

    Native::test(Faces\Lock::class)->assertDontSee('Use fingerprint');
    Native::test(Faces\PinSettings::class)->assertDontSee('Use fingerprint next time');
});
