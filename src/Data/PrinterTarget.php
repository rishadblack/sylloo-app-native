<?php

namespace Rishadblack\SyllooAppNative\Data;

use Stringable;

/**
 * Where a receipt printer is (the Kotlin twin is `PrinterTarget` in PrintBytes.kt; both read the same text):
 *
 * - `bt:AA:BB:CC:DD:EE:FF`: a paired Bluetooth printer (classic SPP);
 * - `usb:0416:5011`: a USB printer by vendor and product id (hex), stable when it is plugged in again;
 * - `net:192.168.1.50:9100`: a LAN or Wi-Fi printer (port 9100 when left out).
 *
 * Keep the text as the till's printer setting; `parse()` reads it back (null for anything else).
 */
final readonly class PrinterTarget implements Stringable
{
    public const BLUETOOTH = 'bluetooth';

    public const USB = 'usb';

    public const NETWORK = 'network';

    public const DEFAULT_PORT = 9100;

    private function __construct(
        public string $kind,
        public string $address,
        public ?int $vendorId = null,
        public ?int $productId = null,
        public ?string $host = null,
        public ?int $port = null,
    ) {}

    public static function bluetooth(string $mac): ?self
    {
        $mac = strtoupper(trim($mac));

        return preg_match('/^[0-9A-F]{2}(:[0-9A-F]{2}){5}$/', $mac) === 1 ? new self(self::BLUETOOTH, $mac) : null;
    }

    public static function usb(int $vendorId, int $productId): ?self
    {
        if ($vendorId < 0 || $vendorId > 0xFFFF || $productId < 0 || $productId > 0xFFFF) {
            return null;
        }

        return new self(self::USB, sprintf('%04x:%04x', $vendorId, $productId), $vendorId, $productId);
    }

    public static function network(string $host, int $port = self::DEFAULT_PORT): ?self
    {
        $host = strtolower(trim($host));
        $name = '/^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$/';

        if (preg_match($name, $host) !== 1 || $port < 1 || $port > 65535) {
            return null;
        }

        return new self(self::NETWORK, $host.':'.$port, host: $host, port: $port);
    }

    public static function parse(?string $text): ?self
    {
        $text = trim((string) $text);

        if (! str_contains($text, ':')) {
            return null;
        }

        [$kind, $rest] = explode(':', $text, 2);

        return match (strtolower($kind)) {
            'bt' => self::bluetooth($rest),
            'usb' => preg_match('/^([0-9a-f]{4}):([0-9a-f]{4})$/', strtolower($rest), $m) === 1
                ? self::usb((int) hexdec($m[1]), (int) hexdec($m[2]))
                : null,
            'net' => self::parseNetwork($rest),
            default => null,
        };
    }

    public function toString(): string
    {
        return match ($this->kind) {
            self::BLUETOOTH => 'bt:'.$this->address,
            self::USB => 'usb:'.$this->address,
            default => 'net:'.$this->address,
        };
    }

    public function __toString(): string
    {
        return $this->toString();
    }

    private static function parseNetwork(string $rest): ?self
    {
        if (! str_contains($rest, ':')) {
            return self::network($rest);
        }

        $port = substr($rest, (int) strrpos($rest, ':') + 1);

        return preg_match('/^[0-9]{1,5}$/', $port) === 1
            ? self::network(substr($rest, 0, (int) strrpos($rest, ':')), (int) $port)
            : null;
    }
}
