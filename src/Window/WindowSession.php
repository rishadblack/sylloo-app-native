<?php

namespace Rishadblack\SyllooAppNative\Window;

use Rishadblack\SyllooAppBase\Window\WindowRules;

/**
 * One opening of the cloud window (plan §7.6, device-api §4, §6.5): made from the office's session-ticket answer, it
 * checks the one-use address with the base's WindowRules and gives the element `<native:sylloo-window>` its props:
 * the address, the business's hosts, the allowed paths, http only outside production, and the keep-alive. The same
 * rules run on the phone before every page (WindowFilter.kt, the Kotlin twin of WindowRules::mayShow(), tested against
 * the same cases).
 *
 * Staff: `POST /app/api/session-ticket` → `{url: https://<host>/app/session?ticket=…, rules: {…}}`, keep-alive
 * `/member/app/keep-alive`. Employee: `POST actions/app-window/ticket` → `/employee/app/session?ticket=…`, keep-alive
 * `/employee/app/keep-alive` (204 while the device and its sign-in live; 401 `device_disconnected` ends the window).
 */
final readonly class WindowSession
{
    public const STAFF_ENTRANCE = '/app/session';

    public const EMPLOYEE_ENTRANCE = '/employee/app/session';

    public const STAFF_KEEP_ALIVE = '/member/app/keep-alive';

    public const EMPLOYEE_KEEP_ALIVE = '/employee/app/keep-alive';

    /** The business's sign-in pages: never shown, a new ticket instead (the desktop app's `SIGN_IN_PATHS`). */
    public const SIGN_IN_PATHS = ['/member/login', '/login'];

    /** The page's own Sign out: the member is signed out of the app too (the desktop app's `SIGN_OUT_PATHS`). */
    public const SIGN_OUT_PATHS = ['/member/theme/logout', '/member/logout', '/logout'];

    /** The `ended` text the window sends when the member used the page's own Sign out (WindowHolder.SIGNED_OUT). */
    public const SIGNED_OUT = 'signed_out';

    /**
     * @param  list<string>  $hosts
     * @param  list<string>  $paths
     */
    public function __construct(
        public string $url,
        public array $hosts,
        public array $paths,
        public bool $employee,
        public bool $allowHttp,
        public ?string $keepAlivePath,
        public int $keepAliveSeconds,
        public ?int $idleMinutes = null,
        public bool $lockRequired = false,
        public ?string $minVersion = null,
    ) {}

    /**
     * The session of a ticket answer, or null when its address may not be opened (not https, not one of the
     * business's hosts, not the entrance path, a user in it).
     *
     * @param  array<string, mixed>  $answer
     * @param  array<array-key, string|null>  $hosts  the business's hosts (DeviceState::hosts(): live, practice)
     */
    public static function fromTicket(array $answer, array $hosts, bool $employee = false): ?self
    {
        $hosts = array_values(array_unique(array_map('strtolower', array_filter($hosts, fn ($host) => is_string($host) && $host !== ''))));
        $url = $answer['url'] ?? null;

        if ($hosts === [] || ! WindowRules::isTicketUrl($url, $hosts, $employee ? self::EMPLOYEE_ENTRANCE : self::STAFF_ENTRANCE)) {
            return null;
        }

        $rules = is_array($answer['rules'] ?? null) ? $answer['rules'] : [];

        return new self(
            url: $url,
            hosts: $hosts,
            paths: WindowRules::allowedPaths($answer, $employee),
            employee: $employee,
            allowHttp: ! app()->isProduction(),
            keepAlivePath: $employee ? self::EMPLOYEE_KEEP_ALIVE : self::STAFF_KEEP_ALIVE,
            keepAliveSeconds: WindowRules::keepAliveSeconds(),
            idleMinutes: is_numeric($rules['idle_minutes'] ?? null) ? max(1, (int) $rules['idle_minutes']) : null,
            lockRequired: (bool) ($rules['lock_required'] ?? false),
            minVersion: is_string($rules['min_version'] ?? null) ? $rules['min_version'] : null,
        );
    }

    /**
     * Whether the window may show the address (the PHP side of the same rule the phone checks).
     */
    public function mayShow(string $url): bool
    {
        return WindowRules::mayShow($url, $this->hosts, $this->paths);
    }

    /**
     * What the window does with an address (the twin of WindowFilter.decide() in Kotlin): `show`; `leave` (the normal
     * browser: another site, a page of the business the window may not show, `mailto:`/`tel:`); `ended` (the
     * business's sign-in page, or a redirect to a page the window may not show: its session ended); `sign_out` (the
     * page's own Sign out: the member is signed out of the app too); `block` (`javascript:`, `file:`, …).
     */
    public function decide(string $url, bool $isRedirect = false): string
    {
        $scheme = strtolower((string) parse_url($url, PHP_URL_SCHEME));
        $host = strtolower((string) parse_url($url, PHP_URL_HOST));

        if (in_array($scheme, ['https', 'http'], true) && in_array($host, $this->hosts, true) && parse_url($url, PHP_URL_USER) === null) {
            $path = '/'.ltrim((string) parse_url($url, PHP_URL_PATH), '/');

            if (self::under($path, self::SIGN_OUT_PATHS)) {
                return 'sign_out';
            }

            if (self::under($path, self::SIGN_IN_PATHS)) {
                return 'ended';
            }
        }

        if ($this->mayShow($url)) {
            return 'show';
        }

        if (in_array($scheme, ['mailto', 'tel', 'sms', 'geo'], true)) {
            return 'leave';
        }

        if ($scheme !== 'https' && $scheme !== 'http') {
            return 'block';
        }

        return $isRedirect && in_array($host, $this->hosts, true) ? 'ended' : 'leave';
    }

    /**
     * @param  list<string>  $prefixes
     */
    protected static function under(string $path, array $prefixes): bool
    {
        foreach ($prefixes as $prefix) {
            $prefix = '/'.trim($prefix, '/');

            if (rtrim($path, '/') === $prefix || str_starts_with($path, $prefix.'/')) {
                return true;
            }
        }

        return false;
    }

    /**
     * The element's attributes (`<native:sylloo-window :src :hosts …>`).
     *
     * @return array{src: string, hosts: list<string>, paths: list<string>, allow-http: bool, keep-alive-path: string|null, keep-alive-seconds: int}
     */
    public function props(): array
    {
        return [
            'src' => $this->url,
            'hosts' => $this->hosts,
            'paths' => $this->paths,
            'allow-http' => $this->allowHttp,
            'keep-alive-path' => $this->keepAlivePath,
            'keep-alive-seconds' => $this->keepAliveSeconds,
        ];
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        return [
            'url' => $this->url,
            'hosts' => $this->hosts,
            'paths' => $this->paths,
            'employee' => $this->employee,
            'allow_http' => $this->allowHttp,
            'keep_alive_path' => $this->keepAlivePath,
            'keep_alive_seconds' => $this->keepAliveSeconds,
            'idle_minutes' => $this->idleMinutes,
            'lock_required' => $this->lockRequired,
            'min_version' => $this->minVersion,
        ];
    }

    /**
     * @param  array<string, mixed>  $data
     */
    public static function fromArray(array $data): ?self
    {
        if (! is_string($data['url'] ?? null) || ! is_array($data['hosts'] ?? null) || ! is_array($data['paths'] ?? null)) {
            return null;
        }

        return new self(
            url: $data['url'],
            hosts: array_values(array_filter($data['hosts'], 'is_string')),
            paths: array_values(array_filter($data['paths'], 'is_string')),
            employee: (bool) ($data['employee'] ?? false),
            allowHttp: (bool) ($data['allow_http'] ?? false),
            keepAlivePath: is_string($data['keep_alive_path'] ?? null) ? $data['keep_alive_path'] : null,
            keepAliveSeconds: max(30, (int) ($data['keep_alive_seconds'] ?? 240)),
            idleMinutes: isset($data['idle_minutes']) ? (int) $data['idle_minutes'] : null,
            lockRequired: (bool) ($data['lock_required'] ?? false),
            minVersion: is_string($data['min_version'] ?? null) ? $data['min_version'] : null,
        );
    }
}
