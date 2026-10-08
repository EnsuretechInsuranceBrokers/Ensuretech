<?php

namespace Tests\Feature;

use App\Bff\Contract;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class EdgeTest extends TestCase
{
    public function test_honeypots_look_like_any_unknown_path_and_raise_a_silent_alert(): void
    {
        $this->fake();
        Log::shouldReceive('channel')->with('security')->once()->andReturnSelf();
        Log::shouldReceive('critical')->once()->withArgs(fn ($m, $ctx) => $m === 'honeypot touched' && $ctx['path'] === 'api/v1/admin/users/export' && $ctx['has_token'] === true);
        Log::shouldReceive('withContext')->zeroOrMoreTimes();
        $r = $this->as()->getJson('/api/v1/admin/users/export')->assertStatus(404);
        $this->assertSame('Not found', $r->json('title'));
    }

    public function test_every_response_carries_the_security_headers_and_a_correlation_id(): void
    {
        $this->fake();
        $r = $this->getJson('/api/v1/broker/employers');
        $r->assertHeader('X-Content-Type-Options', 'nosniff')->assertHeader('Referrer-Policy', 'no-referrer')->assertHeaderMissing('X-Powered-By');
        $this->assertStringContainsString('no-store', $r->headers->get('Cache-Control'));
        $this->assertNotEmpty($r->headers->get('X-Correlation-Id'));
    }

    public function test_anonymous_callers_are_rate_limited_per_ip_before_any_token_work(): void
    {
        $this->fake();
        config(['bff.rate_limits.anonymous_per_minute' => 3]);
        $this->app['cache']->flush();
        for ($i = 0; $i < 3; $i++) {
            $this->getJson('/api/v1/broker/employers')->assertStatus(401);
        }
        $this->getJson('/api/v1/broker/employers')->assertStatus(429);
    }

    public function test_one_tenant_cannot_use_up_another_tenants_budget(): void
    {
        $this->fake(['org.test/*' => \Illuminate\Support\Facades\Http::response(['content' => [], 'totalElements' => 0, 'number' => 0, 'size' => 1])]);
        config(['bff.rate_limits.tenant_per_minute' => 2]);
        $this->app['cache']->flush();
        $a = fn () => $this->as('BROKER_ADMIN', ['broker_id' => 1])->getJson('/api/v1/broker/employers');
        $b = fn () => $this->as('BROKER_ADMIN', ['broker_id' => 2, 'user_id' => 77])->getJson('/api/v1/broker/employers');
        $a()->assertOk();
        $a()->assertOk();
        $a()->assertStatus(429);
        $b()->assertOk();
    }

    public function test_the_contract_is_complete_and_consistent(): void
    {
        $contract = app(Contract::class);
        $this->assertNotEmpty($contract->endpoints());
        foreach ($contract->endpoints() as $name => $e) {
            if (! in_array($e['kind'], ['empty', 'download'], true)) {
                $this->assertNotEmpty($e['schema'], "$name has no response schema");
            }
            $this->assertContains($e['service'], array_keys(config('bff.upstreams')), "$name: unknown service");
            foreach ($e['gateways'] as $gw) {
                $this->assertContains($gw, ['broker', 'employer']);
            }
            preg_match_all('/\{(\w+)\}/', $e['path'], $in);
            foreach ($in[1] as $param) {
                $this->assertArrayHasKey($param, $e['where'] ?? [], "$name: path parameter '$param' has no constraint, so it could carry '../'");
            }
            if (in_array('POST', $e['methods']) && ! isset($e['multipart'])) {
                $this->assertArrayHasKey('body', $e + ['body' => []], $name);
            }
        }
    }

    public function test_a_contract_endpoint_without_a_schema_refuses_to_boot(): void
    {
        $this->expectException(\InvalidArgumentException::class);
        new Contract(['schemas' => [], 'endpoints' => ['x' => ['methods' => ['GET'], 'path' => 'x', 'gateways' => ['broker'], 'service' => 'policy', 'upstream' => '/x']]]);
    }

    public function test_every_public_schema_field_is_snake_case(): void
    {
        foreach (app(Contract::class)->schemas() as $name => $schema) {
            foreach ($schema as $key => $spec) {
                if ($key === '__passthrough') {
                    continue;
                }
                $as = is_array($spec) ? $spec['as'] : $spec;
                $this->assertMatchesRegularExpression('/^[a-z][a-z0-9_]*$/', $as, "schema $name exposes '$as'");
            }
        }
    }

    public function test_cross_origin_access_is_denied_unless_the_origin_is_configured(): void
    {
        $this->fake();
        $this->getJson('/api/v1/broker/employers', ['Origin' => 'https://evil.example'])->assertHeaderMissing('Access-Control-Allow-Origin');
        config(['cors.allowed_origins' => ['https://portal.ensuretech.test']]);
        $this->call('OPTIONS', '/api/v1/broker/employers', [], [], [], ['HTTP_ORIGIN' => 'https://portal.ensuretech.test', 'HTTP_ACCESS_CONTROL_REQUEST_METHOD' => 'GET'])
            ->assertHeader('Access-Control-Allow-Origin', 'https://portal.ensuretech.test');
        // with a single configured origin the library always answers with THAT origin, which a browser on evil.example will not accept
        $evil = $this->call('OPTIONS', '/api/v1/broker/employers', [], [], [], ['HTTP_ORIGIN' => 'https://evil.example', 'HTTP_ACCESS_CONTROL_REQUEST_METHOD' => 'GET']);
        $this->assertNotSame('https://evil.example', $evil->headers->get('Access-Control-Allow-Origin'));
        $this->assertNotSame('*', $evil->headers->get('Access-Control-Allow-Origin'));
    }
}
