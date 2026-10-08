<?php

namespace App\Bff;

/** Re-cases the keys of free-form maps below a given depth (the top level is data, e.g. relation codes, and is left alone). */
final class Keys
{
    public static function camel(array $m, int $skip = 1): array
    {
        return self::map($m, $skip, fn (string $k) => lcfirst(str_replace('_', '', ucwords($k, '_'))));
    }

    public static function snake(array $m, int $skip = 1): array
    {
        return self::map($m, $skip, fn (string $k) => strtolower(preg_replace('/(?<!^)[A-Z]/', '_$0', $k)));
    }

    private static function map(array $m, int $skip, callable $f): array
    {
        $out = [];
        foreach ($m as $k => $v) {
            $key = $skip <= 0 && is_string($k) ? $f($k) : $k;
            $out[$key] = is_array($v) ? self::map($v, $skip - 1, $f) : $v;
        }

        return $out;
    }
}
