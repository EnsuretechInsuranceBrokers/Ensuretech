<?php

namespace App\Bff;

use InvalidArgumentException;

/** The public API contract (config/contract.php) in a form the gateway can act on. Validated once, at boot: a broken contract must not start. */
final class Contract
{
    private array $schemas;

    private array $endpoints;

    private array $inputs;

    public function __construct(?array $config = null)
    {
        $config ??= config('contract');
        $this->schemas = $config['schemas'];
        $this->inputs = $config['inputs'] ?? [];
        $this->endpoints = [];
        foreach ($config['endpoints'] as $name => $e) {
            $resolved = $this->resolve($name, $e);                       // always validated, whether or not the feature is on
            if (isset($e['feature']) && ! config("bff.features.{$e['feature']}")) {
                continue;
            }
            $this->endpoints[$name] = $resolved;
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
        $e['scope'] ??= 'gateway';
        if (! in_array($e['scope'], ['gateway', 'auth', 'public'], true)) {
            throw new InvalidArgumentException("contract endpoint '$name': unknown scope '{$e['scope']}'");
        }
        if (isset($e['input']) && ! isset($this->inputs[$e['input']])) {
            throw new InvalidArgumentException("contract endpoint '$name' uses unknown input '{$e['input']}'");
        }
        if (isset($e['input'], $e['body'])) {
            throw new InvalidArgumentException("contract endpoint '$name' has both 'input' and 'body'");
        }
        $resp = $e['response'] ?? null;
        $stream = $e['stream'] ?? null;
        if (isset($e['download'])) {
            $e['kind'] = 'download';
            $e['schema'] = '';
            $e['name'] = $name;

            return $e;
        }
        if ($resp === 'none') {                                  // the endpoint answers 204 and says nothing else
            $e['kind'] = 'empty';
            $e['schema'] = '';
            $e['name'] = $name;

            return $e;
        }
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

    public function binder(): Binder
    {
        return new Binder($this->inputs);
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
