<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppNative\Data\PrinterTarget;
use Rishadblack\SyllooAppNative\Data\PrintOutcome;
use Rishadblack\SyllooAppNative\Events\PrintAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Receipt printing on Android (bridge functions Print.Receipt, Print.Printers, Print.Drawer; the Android till plan
 * §7.1, §7.3). Off unless the app turns on `sylloo-app-native.features.print` (then every call answers `not_set_up`
 * without asking the phone, and the build has no Bluetooth permission).
 *
 * The receipt page is the same self-contained HTML as on the PC (images as `data:` addresses; no scripts run, nothing
 * is fetched). The phone draws it off the screen at the paper's width, makes it a 1-bit picture and sends it as ESC/POS
 * raster, so Bangla and the layout print as drawn. Every call answers with a plain {@see PrintOutcome}: `started`
 * (the final outcome comes as {@see PrintAnswered}: `printed` / `opened`, `no_printer`, `not_permitted`, `failed`) or
 * a refusal at once. Nothing throws.
 */
class Printer
{
    /** Paper width in mm → printer dots (203 dpi). */
    public const PAPERS = [58 => 384, 80 => 576];

    public const MAX_HTML_BYTES = 2 * 1024 * 1024;

    public const MAX_PNG_BYTES = 4 * 1024 * 1024;

    public const MAX_COPIES = 5;

    public function enabled(): bool
    {
        return (bool) config('sylloo-app-native.features.print', false);
    }

    /**
     * Print a receipt page (self-contained HTML) on a 58 or 80 mm printer.
     *
     * @param  bool  $openDrawer  kick the cash drawer after the receipt (same connection)
     */
    public function receipt(string $html, int $paperMm, PrinterTarget|string $target, ?string $id = null, int $copies = 1, bool $cut = true, bool $openDrawer = false): PrintOutcome
    {
        if (! $this->enabled()) {
            return new PrintOutcome(PrintOutcome::NOT_SET_UP, 'feature_off', $id);
        }

        if (trim($html) === '') {
            return new PrintOutcome(PrintOutcome::FAILED, 'empty', $id);
        }

        if (strlen($html) > self::MAX_HTML_BYTES) {
            return new PrintOutcome(PrintOutcome::FAILED, 'too_big', $id);
        }

        return $this->send(['html' => $html], $paperMm, $target, $id, $copies, $cut, $openDrawer);
    }

    /**
     * Print a ready picture (PNG bytes, not base64), scaled to the paper's width.
     */
    public function picture(string $png, int $paperMm, PrinterTarget|string $target, ?string $id = null, int $copies = 1, bool $cut = true, bool $openDrawer = false): PrintOutcome
    {
        if (! $this->enabled()) {
            return new PrintOutcome(PrintOutcome::NOT_SET_UP, 'feature_off', $id);
        }

        if (! str_starts_with($png, "\x89PNG\r\n\x1a\n")) {
            return new PrintOutcome(PrintOutcome::FAILED, 'empty', $id);
        }

        if (strlen($png) > self::MAX_PNG_BYTES) {
            return new PrintOutcome(PrintOutcome::FAILED, 'too_big', $id);
        }

        return $this->send(['png' => base64_encode($png)], $paperMm, $target, $id, $copies, $cut, $openDrawer);
    }

    /**
     * Open the cash drawer plugged into the receipt printer (`ESC p`, the PC's bytes).
     */
    public function drawer(PrinterTarget|string $target, ?string $id = null): PrintOutcome
    {
        $refused = $this->refusal($target, $id, 'drawer');

        if ($refused !== null) {
            return $refused;
        }

        return $this->call('Print.Drawer', array_filter([
            'target' => (string) $this->target($target),
            'id' => $id,
        ], fn ($value) => $value !== null), $id, 'drawer');
    }

    /**
     * The printers the phone can reach now: its paired Bluetooth devices and attached USB printers. `status` listed |
     * not_permitted (Bluetooth not allowed yet; USB ones still listed) | asking (with `$ask`: Android's question is
     * shown; the answer comes as PrintAnswered with job `permission`, then list again) | not_set_up | failed.
     *
     * @return array{status: string, printers: list<array{kind: string, name: string, target: string}>}
     */
    public function printers(bool $ask = false, ?string $id = null): array
    {
        if (! $this->enabled()) {
            return ['status' => PrintOutcome::NOT_SET_UP, 'printers' => []];
        }

        $answer = Bridge::call('Print.Printers', array_filter(['ask' => $ask ?: null, 'id' => $id], fn ($value) => $value !== null));

        if ($answer === null) {
            return ['status' => PrintOutcome::FAILED, 'printers' => []];
        }

        $printers = [];

        foreach (is_array($answer['printers'] ?? null) ? $answer['printers'] : [] as $printer) {
            $target = is_array($printer) ? PrinterTarget::parse(is_string($printer['target'] ?? null) ? $printer['target'] : null) : null;

            if ($target !== null) {
                $printers[] = [
                    'kind' => $target->kind,
                    'name' => is_string($printer['name'] ?? null) && $printer['name'] !== '' ? $printer['name'] : $target->address,
                    'target' => $target->toString(),
                ];
            }
        }

        return ['status' => is_string($answer['status'] ?? null) ? $answer['status'] : 'listed', 'printers' => $printers];
    }

    /**
     * @param  array<string, string>  $content
     */
    protected function send(array $content, int $paperMm, PrinterTarget|string $target, ?string $id, int $copies, bool $cut, bool $openDrawer): PrintOutcome
    {
        $refused = $this->refusal($target, $id, 'receipt');

        if ($refused !== null) {
            return $refused;
        }

        if (! isset(self::PAPERS[$paperMm])) {
            return new PrintOutcome(PrintOutcome::FAILED, 'paper', $id);
        }

        return $this->call('Print.Receipt', array_filter([
            ...$content,
            'paper_mm' => $paperMm,
            'target' => (string) $this->target($target),
            'copies' => max(1, min(self::MAX_COPIES, $copies)),
            'cut' => $cut,
            'open_drawer' => $openDrawer,
            'id' => $id,
        ], fn ($value) => $value !== null), $id, 'receipt');
    }

    protected function refusal(PrinterTarget|string $target, ?string $id, string $job): ?PrintOutcome
    {
        if (! $this->enabled()) {
            return new PrintOutcome(PrintOutcome::NOT_SET_UP, 'feature_off', $id, $job);
        }

        if ($this->target($target) === null) {
            return new PrintOutcome(PrintOutcome::NO_PRINTER, 'target', $id, $job);
        }

        return null;
    }

    protected function target(PrinterTarget|string $target): ?PrinterTarget
    {
        return $target instanceof PrinterTarget ? $target : PrinterTarget::parse($target);
    }

    /**
     * @param  array<string, mixed>  $parameters
     */
    protected function call(string $function, array $parameters, ?string $id, string $job): PrintOutcome
    {
        if (! Bridge::available()) {
            return new PrintOutcome(PrintOutcome::FAILED, 'no_bridge', $id, $job);
        }

        $answer = Bridge::result($function, $parameters);

        if ($answer === null || ! $answer['ok']) {
            return new PrintOutcome(PrintOutcome::FAILED, $answer === null ? 'no_answer' : strtolower((string) $answer['code']), $id, $job);
        }

        $status = is_string($answer['data']['status'] ?? null) ? $answer['data']['status'] : PrintOutcome::FAILED;
        $reason = is_string($answer['data']['reason'] ?? null) ? $answer['data']['reason'] : null;

        return new PrintOutcome($status, $reason, $id, $job);
    }
}
