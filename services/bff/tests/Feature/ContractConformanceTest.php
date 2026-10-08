<?php

namespace Tests\Feature;

use App\Bff\Contract;
use Illuminate\Http\Client\Request;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

/**
 * Drives EVERY endpoint in the contract: a fake service answers with a payload built from the endpoint's own schema plus a field the contract does
 * not know, and the test checks that (a) the call is routable and authenticated, (b) the public answer has exactly the declared fields and not the
 * extra one, and (c) a request built from the endpoint's input schema reaches the service with only upstream-named keys.
 */
class ContractConformanceTest extends TestCase
{
    private Contract $contract;

    protected function setUp(): void
    {
        parent::setUp();
        config(['bff.upstreams.sheets' => 'http://sheets.test', 'bff.ops_roles' => [], 'bff.rate_limits' => array_map(fn () => 100000, config('bff.rate_limits'))]);
        $this->app->forgetInstance(Contract::class);
        $this->contract = app(Contract::class);
    }

    private function upstreamPayload(string $schema, array $schemas): array
    {
        if (isset($schemas[$schema]['__passthrough'])) {
            return ['spec' => 1];
        }
        $out = [];
        foreach ($schemas[$schema] as $from => $spec) {
            $spec = is_string($spec) ? ['as' => $spec] : $spec;
            $out[$from] = match (true) {
                isset($spec['schema']) => $this->upstreamPayload($spec['schema'], $schemas),
                isset($spec['list']) => [$this->upstreamPayload($spec['list'], $schemas)],
                isset($spec['raw']) => ['a' => 1],
                isset($spec['add']) => 0,
                default => 'v',
            };
        }

        return $out + ['zz_internal' => 'LEAK'];
    }

    private function publicKeys(string $schema, array $schemas): array
    {
        return array_map(fn ($s) => is_string($s) ? $s : $s['as'], $schemas[$schema]);
    }

    private function sampleValue(array $f): mixed
    {
        $pattern = $f['pattern'] ?? '';
        return match ($f['type']) {
            'string' => isset($f['enum']) ? $f['enum'][0] : (str_contains($pattern, '@') ? 'a@b.co' : (str_contains($pattern, 'http') ? 'https://x.example' : (str_contains($pattern, 'A-Z][A-Z0-9_]') ? 'ABC_DEF' : str_pad('abcd', (int) ($f['min'] ?? 4), 'x')))),
            'int' => max(1, (int) ($f['min'] ?? 1)),
            'number' => (float) max(1, (int) ($f['min'] ?? 1)),
            'bool' => true,
            'date' => '2030-04-01',
            'datetime' => '2030-04-01T00:00:00Z',
            'map' => ['a' => 1],
            'list' => [str_starts_with($f['of'], 'input:') ? null : $this->sampleValue(['type' => $f['of']])],
            'object' => null,
        };
    }

    /** A minimal valid body for an input schema (required fields only). */
    private function sampleBody(string $input): array
    {
        $inputs = (fn () => $this->inputs)->call($this->contract);
        $body = [];
        foreach ($inputs[$input] as $name => $f) {
            if (empty($f['required'])) {
                continue;
            }
            $v = $this->sampleValue($f);
            if ($f['type'] === 'list' && str_starts_with($f['of'], 'input:')) {
                $v = [$this->sampleBody(substr($f['of'], 6))];
            }
            $body[$name] = $v;
        }

        return $body;
    }

    private function url(array $e, string $gw): string
    {
        $path = preg_replace_callback('/\{(\w+)\}/', function ($m) use ($e) {
            $re = $e['where'][$m[1]] ?? '[0-9]+';

            return match (true) {
                str_contains($re, '0-9a-fA-F]{8}') => 'a1b2c3d4-0000-4000-8000-000000000001',
                $re === '[0-9a-f]{32}' => str_repeat('a', 32),
                str_contains($re, 'endorsements|') => 'endorsements',
                str_contains($re, 'A-Za-z0-9_.-]{1,80}') || str_contains($re, 'A-Za-z0-9_.:-') => 'k1',
                default => '7',
            };
        }, $e['path']);
        $prefix = $e['scope'] === 'gateway' ? "v1/$gw/" : 'v1/';

        return '/api/'.$prefix.$path;
    }

    public function test_every_endpoint_routes_shapes_and_binds_as_the_contract_says(): void
    {
        $schemas = $this->contract->schemas();
        $checked = 0;
        foreach ($this->contract->endpoints() as $name => $e) {
            if (in_array($e['kind'], ['stream'], true) || isset($e['multipart'])) {
                continue;                                             // covered in UploadAndStreamTest
            }
            foreach ($e['scope'] === 'gateway' ? $e['gateways'] : ['broker'] as $gw) {
                $role = $gw === 'employer' ? 'EMPLOYER_ADMIN' : 'BROKER_ADMIN';
                $status = in_array('POST', $e['methods']) && ! $e['kind'] === 'download' ? 201 : 200;
                if ($e['kind'] === 'download') {
                    $this->fake(['*' => Http::response('a,b', 200, ['Content-Type' => $e['download']['types'][0], 'Content-Disposition' => 'attachment; filename="x.csv"'])]);
                } elseif ($e['kind'] === 'empty') {
                    $this->fake(['*' => Http::response('', 204)]);
                } else {
                    $schema = $e['schema'];
                    $payload = $this->upstreamPayload($schema, $schemas);
                    $this->fake(['*' => Http::response($e['kind'] === 'list' ? [$payload] : $payload, 200)]);
                }
                $call = fn () => $this->call(
                    $e['methods'][0], $this->url($e, $gw), [], [], [],
                    ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'] + ($e['scope'] === 'public' ? [] : ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token($role)]),
                    isset($e['input']) ? json_encode($this->sampleBody($e['input'])) : null,
                );
                $r = $call();
                $this->assertContains($r->getStatusCode(), [200, 201, 204], "$name ($gw) answered {$r->getStatusCode()}: ".substr($r->getContent(), 0, 300));
                $checked++;

                if ($e['kind'] === 'object' && isset($schemas[$e['schema']]['__passthrough'])) {
                    $this->assertSame(['spec' => 1], $r->json());
                } elseif ($e['kind'] === 'object') {
                    $body = $r->json();
                    $this->assertEqualsCanonicalizing(array_keys($this->publicKeys($e['schema'], $schemas) ? array_flip($this->publicKeys($e['schema'], $schemas)) : []), array_keys($body), "$name: public keys");
                    $this->assertStringNotContainsString('LEAK', $r->getContent(), "$name leaked an undeclared field");
                } elseif ($e['kind'] === 'list') {
                    $this->assertStringNotContainsString('LEAK', $r->getContent(), "$name leaked an undeclared field");
                    $this->assertEqualsCanonicalizing(array_values($this->publicKeys($e['schema'], $schemas)), array_keys($r->json('0')), "$name: public keys");
                } elseif ($e['kind'] === 'download') {
                    $this->assertSame('attachment; filename="x.csv"', $r->headers->get('Content-Disposition'));
                }

                if (isset($e['input'])) {
                    $inputs = (fn () => $this->inputs)->call($this->contract);
                    $allowed = array_map(fn ($f) => $f['to'] ?? null, $inputs[$e['input']]);
                    $names = array_keys($inputs[$e['input']]);
                    $allowed = array_map(fn ($n) => $inputs[$e['input']][$n]['to'] ?? $n, $names);
                    Http::assertSent(function (Request $req) use ($allowed) {
                        return array_diff(array_keys($req->data()), $allowed) === [];
                    });
                }
            }
        }
        $this->assertGreaterThan(100, $checked, 'the conformance test should have exercised the whole contract');
    }

    public function test_every_input_rejects_an_empty_body_when_it_has_required_fields(): void
    {
        $inputs = (fn () => $this->inputs)->call($this->contract);
        $this->fake(['*' => Http::response([], 200)]);
        foreach ($this->contract->endpoints() as $name => $e) {
            if (! isset($e['input']) || ! collect($inputs[$e['input']])->contains(fn ($f) => ! empty($f['required']))) {
                continue;
            }
            $gw = $e['scope'] === 'gateway' ? $e['gateways'][0] : 'broker';
            $r = $this->call($e['methods'][0], $this->url($e, $gw), [], [], [], ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json']
                + ($e['scope'] === 'public' ? [] : ['HTTP_AUTHORIZATION' => 'Bearer '.$this->token($gw === 'employer' ? 'EMPLOYER_ADMIN' : 'BROKER_ADMIN')]), '{}');
            $this->assertSame(422, $r->getStatusCode(), "$name accepted an empty body");
        }
        Http::assertNothingSent();
    }

    public function test_qa_tools_do_not_exist_unless_switched_on(): void
    {
        config(['bff.features.qa_tools' => false]);
        $off = new Contract;
        $this->assertArrayNotHasKey('sheets.generate', $off->endpoints());
        $this->assertArrayHasKey('sheets.generate', (new Contract)->endpoints() + $this->contract->endpoints());
        $this->assertStringContainsString("env('BFF_QA_TOOLS', false)", file_get_contents(base_path('config/bff.php')), 'the default must be off');
    }
}
