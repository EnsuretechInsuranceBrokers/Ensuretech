<?php

namespace App\Bff;

/**
 * Turns an upstream payload into the public representation. A schema is an allowlist: {upstream_key: spec}. Anything not named is dropped, so a
 * field a service adds tomorrow (an internal id, a debug flag) cannot reach clients until someone puts it in the contract.
 *
 * spec: 'public_name'
 *    or ['as' => 'public_name', 'map' => ['OLD' => 'NEW'], 'schema' => 'other' | 'list' => 'other']
 * A key missing upstream is emitted as null, so the public shape is stable.
 */
final class Shaper
{
    public function __construct(private array $schemas) {}

    public function shape(mixed $data, string $schema, bool $list = false): mixed
    {
        if (! is_array($data)) {
            throw new ShapeException('expected a JSON '.($list ? 'array' : 'object').' from the service');
        }
        if ($list) {
            if (! array_is_list($data)) {
                throw new ShapeException('expected a JSON array from the service');
            }

            return array_map(fn ($row) => $this->object($row, $schema), $data);
        }

        return $this->object($data, $schema);
    }

    private function object(mixed $row, string $schema): array
    {
        if (! is_array($row) || ($row !== [] && array_is_list($row))) {
            throw new ShapeException("expected an object for schema '$schema'");
        }
        $def = $this->schemas[$schema] ?? throw new ShapeException("unknown schema '$schema'");
        if (isset($def['__passthrough'])) {
            return $row;                                   // only for tools that are off in production (see contract/sheets.php)
        }
        $out = [];
        foreach ($def as $from => $spec) {
            $spec = is_string($spec) ? ['as' => $spec] : $spec;
            $value = $row[$from] ?? null;
            if ($value !== null && isset($spec['schema'])) {
                $value = $this->object($value, $spec['schema']);
            } elseif ($value !== null && isset($spec['list'])) {
                if (! is_array($value) || ($value !== [] && ! array_is_list($value))) {
                    throw new ShapeException("expected an array for '$from'");
                }
                $value = array_map(fn ($r) => $this->object($r, $spec['list']), $value);
            } elseif (isset($spec['add']) && (is_int($value) || is_float($value))) {
                $value += $spec['add'];
            } elseif (isset($spec['map']) && (is_string($value) || is_int($value)) && array_key_exists($value, $spec['map'])) {
                $value = $spec['map'][$value];
            } elseif (is_array($value) && ! isset($spec['raw'])) {
                $value = null;          // an undeclared nested structure is not part of the contract
            }
            if (! empty($spec['obj']) && $value === []) {
                $value = new \stdClass();                  // an empty JSON object must stay an object, not become []
            }
            if (($spec['keys'] ?? null) === 'snake' && is_array($value)) {
                $value = Keys::snake($value);
            }
            $out[$spec['as']] = $value;
        }

        return $out;
    }
}
