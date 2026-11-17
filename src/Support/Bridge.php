<?php

namespace Rishadblack\SyllooAppNative\Support;

/**
 * One call to a Kotlin job over NativePHP's bridge (`nativephp_call`). Off the phone (no bridge) every call answers
 * null, so the app's PHP runs unchanged on a computer and in tests (where `Native::fakeBridge()` answers instead).
 *
 * The bridge answers a job's data as it is, or wrapped as `{"status": "success", "data": {…}}`; a refusal is
 * `{"status": "error", "code": "…", "message": "…"}`.
 */
final class Bridge
{
    /**
     * Whether the bridge is there (on the phone, or faked in tests).
     */
    public static function available(): bool
    {
        return function_exists('nativephp_call');
    }

    /**
     * Call a job. The job's data, or null when there is no bridge, no answer or a refusal.
     *
     * @param  array<string, mixed>  $parameters
     * @return array<string, mixed>|null
     */
    public static function call(string $function, array $parameters = []): ?array
    {
        $answer = self::answer($function, $parameters);

        return $answer === null || self::isError($answer) ? null : self::data($answer);
    }

    /**
     * Call a job and keep a refusal: `['ok' => bool, 'data' => […], 'code' => ?string, 'message' => ?string]`; null
     * when there is no bridge or no answer.
     *
     * @param  array<string, mixed>  $parameters
     * @return array{ok: bool, data: array<string, mixed>, code: string|null, message: string|null}|null
     */
    public static function result(string $function, array $parameters = []): ?array
    {
        $answer = self::answer($function, $parameters);

        if ($answer === null) {
            return null;
        }

        if (self::isError($answer)) {
            return [
                'ok' => false,
                'data' => is_array($answer['data'] ?? null) ? $answer['data'] : [],
                'code' => is_string($answer['code'] ?? null) ? $answer['code'] : 'error',
                'message' => is_string($answer['message'] ?? null) ? $answer['message'] : null,
            ];
        }

        return ['ok' => true, 'data' => self::data($answer), 'code' => null, 'message' => null];
    }

    /**
     * @param  array<string, mixed>  $parameters
     * @return array<string, mixed>|null
     */
    protected static function answer(string $function, array $parameters): ?array
    {
        if (! self::available()) {
            return null;
        }

        $json = json_encode($parameters === [] ? new \stdClass : $parameters, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
        $result = nativephp_call($function, $json === false ? '{}' : $json);

        if (! is_string($result) || $result === '') {
            return null;
        }

        $decoded = json_decode($result, true);

        return is_array($decoded) ? $decoded : null;
    }

    /**
     * @param  array<string, mixed>  $answer
     */
    protected static function isError(array $answer): bool
    {
        return ($answer['status'] ?? null) === 'error';
    }

    /**
     * @param  array<string, mixed>  $answer
     * @return array<string, mixed>
     */
    protected static function data(array $answer): array
    {
        return ($answer['status'] ?? null) === 'success' && is_array($answer['data'] ?? null) ? $answer['data'] : $answer;
    }
}
