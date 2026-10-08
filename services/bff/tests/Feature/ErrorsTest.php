<?php

namespace Tests\Feature;

use Illuminate\Http\Client\ConnectionException;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ErrorsTest extends TestCase
{
    public function test_a_service_error_never_reaches_the_client(): void
    {
        $this->fake(['policy.test/*' => Http::response('java.lang.NullPointerException at com.broker.policy.Secret.read(Secret.java:42) jdbc:postgresql://db.internal/x', 500)]);
        $r = $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(502)->assertJsonPath('title', 'The service could not complete the request');
        $this->assertStringNotContainsString('NullPointer', $r->getContent());
        $this->assertStringNotContainsString('internal', $r->getContent());
        $this->assertNotEmpty($r->json('correlation_id'));
    }

    public function test_a_timeout_is_a_504_without_the_host(): void
    {
        $this->fake(['policy.test/*' => fn () => throw new ConnectionException('cURL error 28: Connection timed out after 10001 ms for http://policy.test/api')]);
        $r = $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(504);
        $this->assertStringNotContainsString('policy.test', $r->getContent());
    }

    public function test_client_errors_pass_with_a_sanitised_body(): void
    {
        $this->fake(['policy.test/*' => Http::response(['status' => 404, 'detail' => 'Policy not found', 'stack' => 'at com.broker...', 'path' => '/internal/x'], 404)]);
        $r = $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(404)->assertJsonPath('detail', 'Policy not found');
        $this->assertArrayNotHasKey('stack', $r->json());
        $this->assertArrayNotHasKey('path', $r->json());

        $this->fake(['billing.test/*' => Http::response(['detail' => 'Validation failed', 'errors' => ['amount' => ['must be positive'], 'x' => ['a' => 'b']]], 400)]);
        $this->as()->postJson('/api/v1/broker/cd/accounts/1/credits', ['amount' => 1, 'reference' => 'r', 'type' => 'DEPOSIT'])->assertStatus(400)
            ->assertJsonPath('errors.amount.0', 'must be positive');
    }

    public function test_a_detail_that_looks_like_a_stack_trace_is_dropped(): void
    {
        $this->fake(['policy.test/*' => Http::response(['detail' => 'NullPointerException at com.broker.policy.Foo.bar(Foo.java:1)'], 409)]);
        $this->assertArrayNotHasKey('detail', $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(409)->json());
    }

    public function test_a_service_that_ignores_the_contract_is_a_502_not_a_leak(): void
    {
        $this->fake(['policy.test/*' => Http::response('<html>proxy error</html>', 200, ['Content-Type' => 'text/html'])]);
        $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(502);
        $this->fake(['policy.test/*' => Http::response([1, 2, 3])]);
        $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(502);
        $this->fake(['policy.test/*' => Http::response(['content' => 'not a list', 'totalElements' => 1, 'number' => 0, 'size' => 1])]);
        $this->as()->getJson('/api/v1/broker/policies')->assertStatus(502);
    }

    public function test_an_upstream_401_or_odd_status_is_not_echoed(): void
    {
        $this->fake(['policy.test/*' => Http::response(['detail' => 'jwt expired by policy-service'], 401)]);
        $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(401)->assertJsonPath('title', 'Authentication failed')->assertJsonMissingPath('detail');
        $this->fake(['policy.test/*' => Http::response([], 418)]);
        $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(502);
    }

    public function test_redirects_from_a_service_are_not_followed(): void
    {
        $this->fake(['policy.test/*' => Http::response('', 302, ['Location' => 'http://169.254.169.254/latest/meta-data'])]);
        $this->as()->getJson('/api/v1/broker/policies/1')->assertStatus(502);
        Http::assertSentCount(1);
    }
}
