<?php

namespace App\Bff;

use InvalidArgumentException;

/**
 * Validates and renames a request body against the contract's input schemas (config/contract.php 'inputs').
 * Public field names are snake_case; each field says its upstream name, type and limits. Unknown fields are dropped (never forwarded), required
 * fields and types are enforced here so a malformed request is refused before any service sees it. Domain validation (is this policy approvable,
 * is the amount affordable) stays with the services.
 *
 * field spec: ['to' => upstreamName, 'type' => string|int|number|bool|date|datetime|map|list|object, 'required' => bool, 'nullable' => bool,
 *              'max' => n (string length / list size), 'min' => n (int, number), 'enum' => [...], 'pattern' => regex,
 *              'of' => scalar type | 'input:NAME' (for list), 'input' => NAME (for object)]
 */
final class Binder
{
    private const MAX_DEPTH = 8;

    private const MAX_NODES = 20000;

    /** @param array<string, array> $inputs */
    public function __construct(private array $inputs) {}

    /** @return array{0: array, 1: array<string, list<string>>} [bound body, errors] */
    public function bind(array $data, string $input): array
    {
        $errors = [];
        $out = $this->object($data, $input, '', $errors);

        return [$out, $errors];
    }

    private function object(array $data, string $input, string $path, array &$errors): array
    {
        $def = $this->inputs[$input] ?? throw new InvalidArgumentException("unknown input '$input'");
        if ($data !== [] && array_is_list($data)) {
            $errors[$path ?: '_'][] = 'must be an object';

            return [];
        }
        $out = [];
        foreach ($def as $name => $f) {
            $at = $path === '' ? $name : "$path.$name";
            if (! array_key_exists($name, $data) || ($data[$name] === null && empty($f['nullable']))) {
                if (! empty($f['required'])) {
                    $errors[$at][] = 'is required';
                }

                continue;
            }
            if ($data[$name] === null) {
                $out[$f['to'] ?? $name] = null;

                continue;
            }
            $value = $this->value($data[$name], $f, $at, $errors);
            if ($value !== self::INVALID) {
                $out[$f['to'] ?? $name] = $value;
            }
        }

        return $out;
    }

    private const INVALID = "\0invalid";

    private function value(mixed $v, array $f, string $at, array &$errors): mixed
    {
        $f = array_filter($f, fn ($x) => $x !== null);
        $fail = function (string $msg) use (&$errors, $at) {
            $errors[$at][] = $msg;

            return self::INVALID;
        };
        switch ($f['type']) {
            case 'string':
                if (! is_string($v)) {
                    return $fail('must be text');
                }
                if (preg_match('/[\x00-\x08\x0B\x0C\x0E-\x1F]/', $v)) {
                    return $fail('contains control characters');
                }
                if (isset($f['max']) && mb_strlen($v) > $f['max']) {
                    return $fail("must be at most {$f['max']} characters");
                }
                if (isset($f['min']) && mb_strlen($v) < $f['min']) {
                    return $fail("must be at least {$f['min']} characters");
                }
                if (isset($f['enum']) && ! in_array($v, $f['enum'], true)) {
                    return $fail('must be one of '.implode(', ', $f['enum']));
                }
                if (isset($f['pattern']) && ! preg_match($f['pattern'], $v)) {
                    return $fail('has an invalid format');
                }

                return $v;
            case 'int':
                if (is_string($v) && preg_match('/^-?\d{1,15}$/', $v)) {
                    $v = (int) $v;
                }
                if (! is_int($v)) {
                    return $fail('must be a whole number');
                }

                return $this->range($v, $f, $fail);
            case 'number':
                if (is_string($v) && is_numeric($v)) {
                    $v = $v + 0;
                }
                if (! (is_int($v) || is_float($v)) || ! is_finite((float) $v)) {
                    return $fail('must be a number');
                }

                return $this->range($v, $f, $fail);
            case 'bool':
                return is_bool($v) ? $v : $fail('must be true or false');
            case 'date':
                $d = is_string($v) ? \DateTimeImmutable::createFromFormat('!Y-m-d', $v) : false;

                return $d && $d->format('Y-m-d') === $v ? $v : $fail('must be a date as YYYY-MM-DD');
            case 'datetime':
                return is_string($v) && strlen($v) <= 40 && preg_match('/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/', $v) ? $v : $fail('must be an ISO-8601 date-time');
            case 'object':
                return is_array($v) ? $this->nested($this->object($v, $f['input'], $at, $errors)) : $fail('must be an object');
            case 'list':
                if (! is_array($v) || ($v !== [] && ! array_is_list($v))) {
                    return $fail('must be a list');
                }
                if (isset($f['max']) && count($v) > $f['max']) {
                    return $fail("must have at most {$f['max']} items");
                }
                $items = [];
                foreach ($v as $i => $item) {
                    $of = $f['of'];
                    $r = str_starts_with($of, 'input:')
                        ? (is_array($item) ? $this->object($item, substr($of, 6), "$at.$i", $errors) : $fail("item $i must be an object"))
                        : $this->value($item, ['type' => $of, 'max' => $f['item_max'] ?? null, 'enum' => $f['enum'] ?? null, 'pattern' => $f['pattern'] ?? null], "$at.$i", $errors);
                    if ($r !== self::INVALID) {
                        $items[] = $r;
                    }
                }

                return $items;
            case 'map':        // free-form configuration (a rate table): bounded in depth and size, keys restricted, values plain
                if (! is_array($v)) {
                    return $fail('must be an object');
                }
                $nodes = 0;
                $bad = $this->checkMap($v, 1, $nodes);

                return $bad === null ? (($f['keys'] ?? null) === 'camel' ? Keys::camel($v) : $v) : $fail($bad);
        }

        return $fail('unsupported type');
    }

    private function nested(array $v): array
    {
        return $v;
    }

    private function range(int|float $v, array $f, callable $fail): mixed
    {
        if (isset($f['min']) && $v < $f['min']) {
            return $fail("must be at least {$f['min']}");
        }
        if (isset($f['max']) && $v > $f['max']) {
            return $fail("must be at most {$f['max']}");
        }

        return $v;
    }

    private function checkMap(array $m, int $depth, int &$nodes): ?string
    {
        if ($depth > self::MAX_DEPTH) {
            return 'is nested too deeply';
        }
        foreach ($m as $k => $v) {
            if (++$nodes > self::MAX_NODES) {
                return 'is too large';
            }
            if (is_string($k) && (strlen($k) > 80 || preg_match('/[\x00-\x1F]/', $k))) {
                return 'has an invalid key';
            }
            if (is_array($v)) {
                if ($e = $this->checkMap($v, $depth + 1, $nodes)) {
                    return $e;
                }
            } elseif (! (is_scalar($v) || $v === null) || (is_string($v) && strlen($v) > 500)) {
                return 'has an invalid value';
            }
        }

        return null;
    }
}
