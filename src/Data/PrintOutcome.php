<?php

namespace Rishadblack\SyllooAppNative\Data;

use Rishadblack\SyllooAppNative\Events\PrintAnswered;

/**
 * The plain outcome of a print job (never an exception). A call answers at once with `started` (the final outcome
 * comes as the event {@see PrintAnswered}) or a refusal; the event carries `printed` (receipt), `opened` (drawer) or a
 * refusal. `reason` says why, as a short code (`feature_off`, `no_bridge`, `target`, `paper`, `empty`, `too_big`,
 * `bluetooth`, `bluetooth_off`, `not_paired`, `not_reachable`, `not_connected`, `usb`, `render`, `io`, …).
 */
final readonly class PrintOutcome
{
    public const STARTED = 'started';

    public const PRINTED = 'printed';

    public const OPENED = 'opened';

    public const NO_PRINTER = 'no_printer';

    public const NOT_PERMITTED = 'not_permitted';

    public const FAILED = 'failed';

    public const NOT_SET_UP = 'not_set_up';

    public function __construct(
        public string $status,
        public ?string $reason = null,
        public ?string $id = null,
        public string $job = 'receipt',
    ) {}

    public static function fromEvent(PrintAnswered $event): self
    {
        return new self($event->status, $event->reason, $event->id, $event->job);
    }

    /**
     * The job is on its way (answer at once); the final outcome follows as PrintAnswered.
     */
    public function started(): bool
    {
        return $this->status === self::STARTED;
    }

    /**
     * The printer took it: the receipt printed or the drawer opened.
     */
    public function done(): bool
    {
        return in_array($this->status, [self::PRINTED, self::OPENED], true);
    }
}
