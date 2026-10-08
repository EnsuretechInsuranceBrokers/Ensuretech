<?php

namespace App\Bff;

use InvalidArgumentException;

/** The public API contract (config/contract.php) in a form the gateway can act on. Validated once, at boot: a broken contract must not start. */
final class Contract
{
    private array $schemas;

    private array $endpoints;

    public function __construct(?array $config = null)
    {
        $config ??= config('contract');
        $this->schemas = $config['schemas'];
        $this->endpoints = [];
        foreach ($config['endpoints'] as $name => $e) {
            $this->endpoints[$name] = $this->resolve($name, $e);
        }
        $this->compositions = $config['compositions'] ?? [];
    }

    public array $compositions;

    private function resolve(string $name, array $e): array
    {
        foreach (['methods', 'path', 'gateways', 'service', 'upstream'] as $f) {
            if (! isset($e[$f])) {
                throw new InvalidArgumentException("contract endpoint '$name' lacks '$f'");
            }
        }
        if (! array_key_exists($e['service'], config('bff.upstreams'))) {
            throw new InvalidArgumentException("contract endpoint '$name': unknown service '{$e['service']}'");
        }
        $resp = $e['response'] ?? null;
        $stream = $e['stream'] ?? null;
        if ($resp === null && $stream === null) {
            throw new InvalidArgumentException("contract endpoint '$name' has no response schema: an endpoint that does not say what it returns is not exposed");
        }
        if ($stream !== null) {
            $this->requireSchema($name, $stream);
            $e['schema'] = $stream;
            $e['kind'] = 'stream';
        } elseif (isset($resp['page'])) {
            $e['schema'] = $this->pageSchema($name, $resp['page'], $resp['item']);
            $e['kind'] = 'object';
        } elseif (isset($resp['list'])) {
            $this->requireSchema($name, $resp['list']);
            $e['schema'] = $resp['list'];
            $e['kind'] = 'list';
        } else {
            $this->requireSchema($name, $resp);
            $e['schema'] = $resp;
            $e['kind'] = 'object';
        }
        $e['name'] = $name;

        return $e;
    }

    private function requireSchema(string $endpoint, string $schema): void
    {
        if (! isset($this->schemas[$schema])) {
            throw new InvalidArgumentException("contract endpoint '$endpoint' uses unknown schema '$schema'");
        }
    }

    private function pageSchema(string $endpoint, string $page, string $item): string
    {
        $this->requireSchema($endpoint, $page);
        $this->requireSchema($endpoint, $item);
        $name = "$page<$item>";
        $this->schemas[$name] ??= array_map(function ($spec) use ($item) {
            if (is_array($spec) && ($spec['list'] ?? null) === '@item') {
                $spec['list'] = $item;
            }

            return $spec;
        }, $this->schemas[$page]);

        return $name;
    }

    public function endpoint(string $name): array
    {
        return $this->endpoints[$name] ?? throw new InvalidArgumentException("no such contract endpoint '$name'");
    }

    /** @return array<string, array> */
    public function endpoints(): array
    {
        return $this->endpoints;
    }

    public function shaper(): Shaper
    {
        return new Shaper($this->schemas);
    }

    public function schemas(): array
    {
        return $this->schemas;
    }
}
