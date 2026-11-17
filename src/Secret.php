<?php

namespace Rishadblack\SyllooAppNative;

use Rishadblack\SyllooAppBase\Contracts\SecretStore;
use Rishadblack\SyllooAppNative\Support\Bridge;
use RuntimeException;

/**
 * A few short secrets in the Android Keystore (bridge functions Secret.Set / Get / Delete, plan §4.6): each kept
 * encrypted (AES-GCM) under a Keystore key that never leaves the phone, in the app's no-backup folder. The phone's
 * answer to the base's {@see SecretStore}.
 *
 * Names are 1–64 of `a-z 0-9 . _ -`; a secret is at most 4096 characters.
 */
class Secret implements SecretStore
{
    public const NAME_PATTERN = '/^[a-z0-9._-]{1,64}$/';

    public const MAX_LENGTH = 4096;

    /**
     * The secret kept under the name; null when none is kept (or off the phone).
     *
     * @throws RuntimeException when the Keystore refused (never read as "no secret": a caller could otherwise make a
     *                          new one and lose what the old one opened)
     */
    public function get(string $name): ?string
    {
        $this->checkName($name);

        $result = Bridge::result('Secret.Get', ['name' => $name]);

        if ($result === null) {
            return null;
        }

        if (! $result['ok']) {
            throw new RuntimeException('The phone could not read the secret "'.$name.'": '.($result['message'] ?? $result['code']));
        }

        $value = $result['data']['value'] ?? null;

        return is_string($value) ? $value : null;
    }

    /**
     * Keep a secret under the name; null removes it. False when the phone refused or there is no bridge.
     */
    public function put(string $name, ?string $secret): void
    {
        $secret === null ? $this->delete($name) : $this->set($name, $secret);
    }

    public function set(string $name, string $secret): bool
    {
        $this->checkName($name);

        if ($secret === '' || mb_strlen($secret) > self::MAX_LENGTH) {
            throw new RuntimeException('A secret is 1 to '.self::MAX_LENGTH.' characters.');
        }

        return Bridge::result('Secret.Set', ['name' => $name, 'value' => $secret])['ok'] ?? false;
    }

    public function delete(string $name): bool
    {
        $this->checkName($name);

        return Bridge::result('Secret.Delete', ['name' => $name])['ok'] ?? false;
    }

    protected function checkName(string $name): void
    {
        if (preg_match(self::NAME_PATTERN, $name) !== 1) {
            throw new RuntimeException('A secret name is 1 to 64 of a-z 0-9 . _ -');
        }
    }
}
