<?php

namespace Rishadblack\SyllooAppNative\Data;

/**
 * One read of the call list (Calls.Recent): `ok` with the work calls, the last row id read (the next read starts after
 * it) and whether more wait; or `not_set_up`, `no_permission`, `failed` with nothing.
 */
final class CallsRead
{
    /**
     * @param  list<PhoneCall>  $calls
     */
    public function __construct(
        public readonly string $status,
        public readonly array $calls = [],
        public readonly ?int $lastId = null,
        public readonly bool $more = false,
    ) {}

    public function ok(): bool
    {
        return $this->status === 'ok';
    }
}
