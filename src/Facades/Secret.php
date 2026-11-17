<?php

namespace Rishadblack\SyllooAppNative\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static string|null get(string $name)
 * @method static void put(string $name, ?string $secret)
 * @method static bool set(string $name, string $secret)
 * @method static bool delete(string $name)
 *
 * @see \Rishadblack\SyllooAppNative\Secret
 */
class Secret extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Rishadblack\SyllooAppNative\Secret::class;
    }
}
