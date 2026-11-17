<?php

namespace Rishadblack\SyllooAppNative\Faces;

use Rishadblack\SyllooAppBase\Device\Activation;
use Rishadblack\SyllooAppBase\Support\AppInfo;
use Rishadblack\SyllooAppNative\Contracts\QrScanner;

/**
 * "Scan QR code" on Activate (plan §7.2): a native piece at `activate.below_code` and its `scan_qr` action. It opens
 * the bound {@see QrScanner} (the package's Qr: its own camera screen); the text comes back as the QrAnswered event
 * with the id {@see self::ID}, which ScreenFace hands to {@see self::answered()}. The text is read by the base (an
 * activation link `<scheme>://activate?code=…`, or the 16 digits) and only fills the code field; the person still
 * presses Activate. Without a bound scanner it says to type the code.
 *
 *     ScanQr::addTo(app(NativePieces::class));   // in the app's service provider
 */
final class ScanQr
{
    public const PLACE = 'activate.below_code';

    public const VIEW = 'sylloo-app-native::pieces.scan-qr';

    public const ACTION = 'scan_qr';

    /** The id of the scan Activate asks for (the QrAnswered event carries it back). */
    public const ID = 'activate-code';

    public static function addTo(NativePieces $pieces): NativePieces
    {
        return $pieces->add(self::PLACE, self::VIEW)->action(self::ACTION, fn (ScreenFace $face) => self::run($face));
    }

    public static function run(ScreenFace $face): void
    {
        if (! app()->bound(QrScanner::class) || ! app(QrScanner::class)->start(self::ID)) {
            $face->toast((string) __('sylloo-app-native::pieces.scan_qr_later'));
        }
    }

    /**
     * The scanner's answer: a code fills the field; anything else that was scanned says it is not a code; a cancel or a
     * refused camera does nothing (the person types the code).
     */
    public static function answered(ScreenFace $face, string $status, ?string $text): void
    {
        if ($status !== 'scanned' || $text === null || $text === '') {
            if ($status === 'denied' || $status === 'failed') {
                $face->toast((string) __('sylloo-app-native::pieces.scan_qr_later'));
            }

            return;
        }

        $code = Activation::codeFromLink($text, AppInfo::linkScheme()) ?? Activation::digitsOf($text);

        if (! Activation::isCode($code)) {
            $face->toast((string) __('sylloo-app-native::pieces.scan_qr_not_a_code'));

            return;
        }

        $face->fill('code', Activation::formatted($code));
    }
}
