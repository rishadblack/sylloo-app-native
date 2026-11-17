<?php

namespace Rishadblack\SyllooAppNative\Faces;

/**
 * How the native faces draw the base's look words: its icons (Bootstrap Icons names, `bi bi-x` or `x`) as Material
 * icon names (Android), its button looks (`primary`, `outline-primary`, `link`, …) as the native button variants, and
 * its colours (`success`, `warning`, `danger`, `info`, `secondary`) as one colour each. Only the drawing lives here;
 * which icon, look and colour a screen part has is the base's choice.
 */
final class NativeLook
{
    /** Bootstrap Icons names (the base's) → Material icon names. */
    public const ICONS = [
        'arrow-counterclockwise' => 'replay',
        'arrow-repeat' => 'refresh',
        'arrow-right' => 'arrow_forward',
        'arrow-right-circle' => 'arrow_circle_right',
        'backspace' => 'backspace',
        'box-arrow-right' => 'logout',
        'box-arrow-up-right' => 'open_in_new',
        'browser-chrome' => 'open_in_browser',
        'calendar-x' => 'event_busy',
        'check-circle' => 'check_circle',
        'check-lg' => 'check',
        'check2-circle' => 'check_circle',
        'cloud-arrow-down' => 'cloud_download',
        'cone-striped' => 'construction',
        'display' => 'smartphone',
        'exclamation-octagon' => 'report',
        'exclamation-triangle' => 'warning',
        'hourglass-split' => 'hourglass_empty',
        'info-circle' => 'info',
        'lock' => 'lock',
        'pause-circle' => 'pause_circle',
        'plus-lg' => 'add',
        'power' => 'power_settings_new',
        'qr-code-scan' => 'qr_code_scanner',
        'shield-lock' => 'lock',
        'slash-circle' => 'block',
        'translate' => 'translate',
        'wifi' => 'wifi',
        'wifi-off' => 'wifi_off',
    ];

    /** The colour of each base colour word (Tailwind palette names, light and dark readable). */
    public const COLOURS = [
        'primary' => 'teal-600',
        'success' => 'green-600',
        'warning' => 'amber-600',
        'danger' => 'red-600',
        'info' => 'sky-600',
        'secondary' => 'slate-500',
    ];

    /**
     * The Material name of a base icon (`bi bi-check-circle`, `check-circle`), or null for none.
     */
    public static function icon(?string $icon): ?string
    {
        if ($icon === null || trim($icon) === '') {
            return null;
        }

        $name = preg_replace('/^bi bi-/', '', trim($icon));

        return self::ICONS[$name] ?? (preg_match('/^[a-z0-9_]+$/', (string) $name) === 1 ? $name : null);
    }

    /**
     * The native button variant of a base button look.
     */
    public static function button(string $variant): string
    {
        return match (true) {
            $variant === 'primary' => 'primary',
            $variant === 'success' => 'success',
            in_array($variant, ['danger', 'outline-danger'], true) => 'destructive',
            $variant === 'link' => 'ghost',
            default => 'secondary',
        };
    }

    /**
     * The text colour class of a base colour word.
     */
    public static function text(?string $variant): string
    {
        return 'text-'.(self::COLOURS[$variant ?? 'secondary'] ?? self::COLOURS['secondary']);
    }

    /**
     * The soft background class of a base colour word (notices, lines).
     */
    public static function soft(?string $variant): string
    {
        return 'bg-'.(self::COLOURS[$variant ?? 'secondary'] ?? self::COLOURS['secondary']).'/15';
    }
}
