<?php

namespace Rishadblack\SyllooAppNative\Data;

/**
 * The phone's answer to Calls.Recording: `found` with the app-private copy of the call's recording (its path, extension,
 * size and SHA-256), or why there is none (`none`, `ambiguous`, `too_long`, `no_call`, `unsupported`, `no_folder`,
 * `not_set_up`, `invalid`, `failed`).
 */
final class CallRecordingFile
{
    public const FOUND = 'found';

    public const STATUSES = ['found', 'none', 'ambiguous', 'too_long', 'no_call', 'unsupported', 'no_folder', 'not_set_up', 'invalid', 'failed'];

    /** Answers that will not change for this call: the app stops asking. */
    public const FINAL = ['too_long', 'no_call', 'unsupported', 'ambiguous'];

    public function __construct(
        public readonly string $status,
        public readonly ?string $path = null,
        public readonly ?string $extension = null,
        public readonly int $size = 0,
        public readonly ?string $sha256 = null,
    ) {}

    public function found(): bool
    {
        return $this->status === self::FOUND;
    }

    public function final(): bool
    {
        return in_array($this->status, self::FINAL, true);
    }

    /**
     * @param  array<string, mixed>  $answer
     * @param  list<string>  $extensions
     */
    public static function fromBridge(?array $answer, array $extensions): self
    {
        $status = $answer['status'] ?? null;

        if ($status !== self::FOUND) {
            return new self(is_string($status) && in_array($status, self::STATUSES, true) ? $status : 'failed');
        }

        $path = $answer['path'] ?? null;
        $extension = $answer['extension'] ?? null;
        $size = $answer['size'] ?? null;
        $sha = $answer['sha256'] ?? null;

        $good = is_string($path) && $path !== '' && is_string($extension) && in_array($extension, $extensions, true)
            && is_numeric($size) && (int) $size > 0 && is_string($sha) && preg_match('/^[0-9a-f]{64}$/', $sha) === 1
            && str_ends_with($path, '.'.$extension) && ! str_contains($path, '..');

        return $good ? new self(self::FOUND, $path, $extension, (int) $size, $sha) : new self('failed');
    }
}
