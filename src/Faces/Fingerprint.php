<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Contracts\DeviceEntries;
use Rishadblack\SyllooAppBase\Pin\Presence;
use Rishadblack\SyllooAppNative\Contracts\FingerprintChoices;
use Rishadblack\SyllooAppNative\Lock;

/**
 * The fingerprint (or face) shortcut of plan B3 and §7.2, as native pieces of the shared screens:
 *
 * - `lock.below_pad`: "Use fingerprint" on the lock screen, for a person who chose it, while the phone can do it. The
 *   prompt's yes does what the lock screen's right PIN does (the base's Presence unlock, then home); a no or a cancel
 *   leaves the PIN pad. The PIN stays the only way after a restart of the app and after the offline days (the PIN
 *   screen asks the office first), and 5 wrong PINs still sign out.
 * - `pin_settings.bottom`: the person turns the shortcut on (only after the phone's own prompt said yes) or off.
 *
 *     Fingerprint::addTo(app(NativePieces::class));   // with a bound FingerprintChoices
 */
final class Fingerprint
{
    public const UNLOCK_ID = 'sylloo-unlock';

    public const TURN_ON_ID = 'sylloo-fingerprint-on';

    public static function addTo(NativePieces $pieces): NativePieces
    {
        return $pieces
            ->add('lock.below_pad', fn (array $context) => self::shows() ? 'sylloo-app-native::pieces.fingerprint-unlock' : null)
            ->add('pin_settings.bottom', fn (array $context) => self::possible() ? 'sylloo-app-native::pieces.fingerprint-setting' : null)
            ->action('fingerprint_unlock', fn (ScreenFace $face) => self::prompt($face, self::UNLOCK_ID))
            ->action('fingerprint_on', fn (ScreenFace $face) => self::prompt($face, self::TURN_ON_ID))
            ->action('fingerprint_off', function (ScreenFace $face): void {
                self::choose(false);
                $face->toast((string) __('sylloo-app-native::pieces.fingerprint_turned_off'));
            });
    }

    /**
     * The phone can do it and a person is on this entry.
     */
    public static function possible(): bool
    {
        return app()->bound(FingerprintChoices::class) && self::who() !== null && app(Lock::class)->isAvailable();
    }

    /**
     * The person chose it and the phone can still do it.
     */
    public static function shows(): bool
    {
        $who = self::who();

        return $who !== null && app()->bound(FingerprintChoices::class)
            && app(FingerprintChoices::class)->isOn($who[0], $who[1]) && app(Lock::class)->isAvailable();
    }

    public static function isOn(): bool
    {
        $who = self::who();

        return $who !== null && app()->bound(FingerprintChoices::class) && app(FingerprintChoices::class)->isOn($who[0], $who[1]);
    }

    public static function prompt(ScreenFace $face, string $id): void
    {
        $started = app(Lock::class)->prompt(
            (string) __('sylloo-app-native::pieces.fingerprint_title'),
            (string) __('sylloo-app-native::pieces.fingerprint_subtitle'),
            (string) __('sylloo-app-native::pieces.fingerprint_use_pin'),
            $id,
        );

        if (! $started) {
            $face->toast((string) __('sylloo-app-native::pieces.fingerprint_unavailable'));
        }
    }

    /**
     * The prompt's answer (ScreenFace hands it over). Only `ok` counts; anything else leaves the PIN pad as it is.
     */
    public static function answered(ScreenFace $face, string $status, ?string $id): void
    {
        if ($status !== 'ok' || self::who() === null) {
            return;
        }

        if ($id === self::TURN_ON_ID) {
            self::choose(true);
            $face->toast((string) __('sylloo-app-native::pieces.fingerprint_turned_on'));

            return;
        }

        if ($id === self::UNLOCK_ID && self::isOn()) {
            app(Presence::class)->unlock();
            $face->goHome();
        }
    }

    protected static function choose(bool $on): void
    {
        $who = self::who();

        if ($who !== null && app()->bound(FingerprintChoices::class)) {
            app(FingerprintChoices::class)->set($who[0], $who[1], $on);
        }
    }

    /**
     * @return array{0: string, 1: string}|null the entry and the person on it now
     */
    protected static function who(): ?array
    {
        $entry = app(DeviceEntries::class)->current();
        $member = app(Presence::class)->memberKey($entry->id);

        return $member !== null ? [$entry->id, $member] : null;
    }
}
