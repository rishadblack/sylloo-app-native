<?php

namespace Rishadblack\SyllooAppNative;

use InvalidArgumentException;
use Rishadblack\SyllooAppNative\Events\UpdateAnswered;
use Rishadblack\SyllooAppNative\Support\Bridge;

/**
 * Installs a new APK (bridge function Update.Install, plan §4.6, A13): downloads it from the address of the signed
 * update feed (GitHub's release hosts only), checks its SHA-256 and hands it to Android's installer, which refuses an APK
 * not signed with the installed app's key. Reading and checking the feed is the PHP side's (the base's update frame).
 * The answer arrives as {@see UpdateAnswered}.
 */
class Update
{
    public const HOSTS = ['github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com'];

    public function install(string $url, string $sha256, ?string $id = null): bool
    {
        $sha256 = strtolower(trim($sha256));
        $parts = parse_url($url);

        if (! is_array($parts) || ($parts['scheme'] ?? '') !== 'https' || isset($parts['user'])
            || ! in_array(strtolower((string) ($parts['host'] ?? '')), self::HOSTS, true)) {
            throw new InvalidArgumentException('An update comes only from the release hosts over https.');
        }

        if (preg_match('/^[0-9a-f]{64}$/', $sha256) !== 1) {
            throw new InvalidArgumentException('An update needs its SHA-256.');
        }

        return (bool) (Bridge::call('Update.Install', array_filter([
            'url' => $url,
            'sha256' => $sha256,
            'id' => $id,
        ], fn ($value) => $value !== null))['started'] ?? false);
    }
}
