<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Tests\TestCase;

class CorrelationIdTest extends TestCase
{
    private const POLICY = ['id' => 7, 'employerId' => 3, 'policyNumber' => 'POL-7', 'insurerId' => 1, 'tpaId' => null, 'policyType' => 'GMC', 'policySubType' => null, 'status' => 'NOT_APPROVED',
        'startDate' => '2030-04-01', 'endDate' => '2031-03-31', 'renewalDate' => null, 'maxMembers' => 100, 'inception' => false, 'proRataType' => 'DAILY'];

    public function test_a_plain_id_from_the_caller_reaches_the_service_the_logs_and_the_response(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        $lines = [];
        Log::listen(function ($e) use (&$lines) { $lines[] = [$e->message, $e->context]; });
        $this->withHeaders(['X-Correlation-Id' => 'qa-trace-12345'])->as()->getJson('/api/v1/broker/policies/7')->assertOk()->assertHeader('X-Correlation-Id', 'qa-trace-12345');
        Http::assertSent(fn (Request $r) => $r->header('X-Correlation-Id') === ['qa-trace-12345'] && str_contains($r->url(), 'policy.test'));
        $access = collect($lines)->first(fn ($l) => str_starts_with($l[0], 'GET /api/v1/broker/policies/7 -> 200'));
        $upstream = collect($lines)->first(fn ($l) => str_starts_with($l[0], 'upstream GET policy.test'));
        $this->assertNotNull($access, 'the request is logged');
        $this->assertNotNull($upstream, 'the call to the service is logged');
        $this->assertSame('qa-trace-12345', $access[1]['correlation_id']);
        $this->assertSame('qa-trace-12345', $upstream[1]['correlation_id']);
        $this->assertSame('BROKER_ADMIN', $access[1]['role']);
        foreach ($lines as [$message]) {
            $this->assertStringNotContainsString('Bearer', $message);                      // no token, no query string, ever
        }
    }

    public function test_an_id_that_is_not_plainly_an_id_is_replaced_not_forwarded(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        foreach (['short', str_repeat('x', 65), "line1\nFORGED", 'has space in it'] as $evil) {
            $r = $this->withHeaders(['X-Correlation-Id' => $evil])->as()->getJson('/api/v1/broker/policies/7')->assertOk();
            $id = $r->headers->get('X-Correlation-Id');
            $this->assertNotSame($evil, $id);
            $this->assertMatchesRegularExpression('/^[0-9a-f-]{36}$/', $id);
        }
        Http::assertNotSent(fn (Request $r) => str_contains(implode('', $r->header('X-Correlation-Id')), 'FORGED'));
    }
}
