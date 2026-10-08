<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ShapingAndForwardingTest extends TestCase
{
    private const POLICY = ['id' => 123, 'brokerId' => 1, 'employerId' => 10, 'policyNumber' => 'GMC/1', 'insurerId' => 3, 'tpaId' => 4, 'policyType' => 'GMC',
        'policySubType' => null, 'premiumTypeId' => 2, 'suminsuredSubtypeId' => 1, 'raterTypeId' => 1, 'status' => 'APPROVED_AND_LIVE', 'startDate' => '2030-04-01',
        'endDate' => '2031-03-31', 'renewalDate' => null, 'maxMembers' => null, 'inception' => false, 'proRataType' => 'DAILY',
        'internal_tpa_code' => 'SECRET-123', 'internal_risk_score' => 0.73];

    public function test_the_public_policy_is_the_contract_and_nothing_else(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        $r = $this->as()->getJson('/api/v1/broker/policies/123')->assertOk();
        $r->assertJsonPath('id', 123)->assertJsonPath('policy_number', 'GMC/1')->assertJsonPath('is_inception', false)->assertJsonPath('pro_rata_type', 'DAILY');
        $body = $r->json();
        foreach (['brokerId', 'broker_id', 'internal_tpa_code', 'internal_risk_score', 'premiumTypeId', 'raterTypeId', 'suminsuredSubtypeId'] as $leak) {
            $this->assertArrayNotHasKey($leak, $body);
        }
        $this->assertArrayHasKey('renewal_date', $body);                    // a missing/null field keeps a stable shape
        $this->assertNull($body['renewal_date']);
    }

    public function test_every_upstream_call_carries_the_gateway_secret_and_a_session_key_derived_from_it(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        $this->as()->getJson('/api/v1/broker/policies/123')->assertOk();
        Http::assertSent(function (Request $r) {
            $sid = $r->header('X-Session-Id')[0] ?? '';

            return ($r->header('X-Gateway-Secret')[0] ?? '') === 'test-gateway-secret' && $sid !== ''
                && hash_equals(hash_hmac('sha256', $sid, 'test-gateway-secret'), $r->header('X-Session-Key')[0] ?? '');
        });
    }

    public function test_spring_pages_become_the_public_page_shape_and_page_numbers_are_1_based(): void
    {
        $this->fake(['policy.test/*' => Http::response(['content' => [self::POLICY], 'totalElements' => 41, 'totalPages' => 1, 'number' => 2, 'size' => 20, 'pageable' => ['x' => 1]])]);
        $this->as()->getJson('/api/v1/broker/policies?page=3&page_size=20&employer_id=10&evil=1&sort=password')->assertOk()
            ->assertJsonPath('total', 41)->assertJsonPath('page', 3)->assertJsonPath('page_size', 20)->assertJsonPath('items.0.id', 123)
            ->assertJsonMissingPath('pageable')->assertJsonMissingPath('content');
        Http::assertSent(function (Request $r) {
            parse_str((string) parse_url($r->url(), PHP_URL_QUERY), $q);

            return str_starts_with($r->url(), 'http://policy.test/api/v1/broker/policies?') && $q === ['page' => '2', 'size' => '20', 'employerId' => '10'];     // 0-based upstream, no extras
        });
    }

    public function test_django_pages_and_job_fields_are_normalised(): void
    {
        $job = ['id' => 'a1b2c3d4-0000-4000-8000-000000000001', 'policy_id' => 100, 'employer_id' => 10, 'broker_id' => 1, 'source_type' => 'CSV_UPLOAD', 'op_type' => 'ADD',
            'status' => 'ENDORSED', 'status_code' => 520, 'phase' => 'EXECUTION', 'category' => 'SUCCESS', 'is_terminal' => false, 'correlation_id' => 'internal-corr',
            'source_filename' => 'f.csv', 'effective_date' => '2030-04-01', 'total_rows' => 3, 'failed_rows' => 0, 'chunk_count' => 1, 'chunks_completed' => 1,
            'total_premium' => '3000.00', 'tax_amount' => '540.00', 'premium_delta' => '3000.00', 'cd_status' => 'SUFFICIENT', 'error_summary' => null,
            'created_at' => '2030-04-01T00:00:00Z', 'started_at' => null, 'completed_at' => null];
        $this->fake(['engine.test/*' => Http::response(['count' => 1, 'page' => 1, 'page_size' => 50, 'results' => [$job]])]);
        $r = $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/employer/endorsements?status=ENDORSED&policy_id=100&bogus=x')->assertOk();
        $r->assertJsonPath('total', 1)->assertJsonPath('items.0.status', 'ENDORSED');
        foreach (['broker_id', 'status_code', 'correlation_id'] as $leak) {
            $this->assertArrayNotHasKey($leak, $r->json('items.0'));
        }
        Http::assertSent(fn (Request $q) => str_starts_with($q->url(), 'http://engine.test/api/v1/employer/endorsements/?') && ! str_contains($q->url(), 'bogus'));
    }

    public function test_only_allowlisted_headers_travel_and_the_token_is_passed_on(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        $token = $this->token();
        $this->withToken($token)->withHeaders(['Cookie' => 'session=steal', 'X-Forwarded-For' => '6.6.6.6', 'X-Admin' => 'true', 'Accept-Language' => 'en-IN',
            'X-Correlation-Id' => 'client-supplied-1'])->getJson('/api/v1/broker/policies/123')->assertOk()->assertHeader('X-Correlation-Id', 'client-supplied-1');
        Http::assertSent(function (Request $r) use ($token) {
            return $r->header('Authorization') === ['Bearer '.$token] && $r->header('Cookie') === [] && $r->header('X-Admin') === []
                && $r->header('X-Correlation-Id') === ['client-supplied-1'] && $r->header('Accept-Language') === ['en-IN'] && $r->header('X-Forwarded-For') !== ['6.6.6.6'];
        });
    }

    public function test_a_silly_correlation_id_is_replaced(): void
    {
        $this->fake(['policy.test/*' => Http::response(self::POLICY)]);
        $r = $this->as()->withHeaders(['X-Correlation-Id' => "x\r\nSet-Cookie: a=b"])->getJson('/api/v1/broker/policies/123');
        $this->assertMatchesRegularExpression('/^[0-9a-f-]{36}$/', $r->headers->get('X-Correlation-Id'));
    }

    public function test_path_parameters_are_validated_before_anything_is_called(): void
    {
        $this->fake();
        $this->as()->getJson('/api/v1/broker/policies/12abc')->assertStatus(404);
        $this->as()->getJson('/api/v1/broker/policies/1%2F..%2Fadmin')->assertStatus(404);
        $this->as()->getJson('/api/v1/broker/endorsements/not-a-uuid')->assertStatus(404);
        Http::assertNothingSent();
    }

    public function test_request_bodies_are_reduced_to_the_contract(): void
    {
        $this->fake(['billing.test/*' => Http::response(['id' => 9, 'entryType' => 'CREDIT', 'amount' => 10.5, 'balanceAfter' => 10.5, 'referenceType' => 'DEPOSIT',
            'referenceId' => 'UTR-1', 'narration' => null, 'createdBy' => 'u', 'createdAt' => '2030-01-01T00:00:00Z'], 201)]);
        $this->as()->postJson('/api/v1/broker/cd/accounts/7/credits', ['amount' => 10.5, 'reference' => 'UTR-1', 'type' => 'DEPOSIT', 'broker_id' => 99, 'balance' => 1e9])
            ->assertStatus(201)->assertJsonPath('reference', 'UTR-1')->assertJsonPath('balance_after', 10.5);
        Http::assertSent(fn (Request $r) => $r->data() === ['amount' => 10.5, 'reference' => 'UTR-1', 'type' => 'DEPOSIT']);
        $this->as()->postJson('/api/v1/broker/cd/accounts/7/credits', ['amount' => ['$gt' => 0], 'reference' => 'x', 'type' => 'DEPOSIT'])
            ->assertStatus(422)->assertJsonPath('errors.amount.0', 'must be a plain value');
        $this->call('POST', '/api/v1/broker/cd/accounts/7/credits', [], [], [], ['CONTENT_TYPE' => 'text/plain', 'HTTP_AUTHORIZATION' => 'Bearer '.$this->token()], 'amount=1')->assertStatus(415);
        $this->call('POST', '/api/v1/broker/cd/accounts/7/credits', [], [], [], ['CONTENT_TYPE' => 'application/json', 'HTTP_AUTHORIZATION' => 'Bearer '.$this->token()], str_repeat('a', 300 * 1024))->assertStatus(413);
    }

    public function test_unknown_paths_and_methods_do_not_exist(): void
    {
        $this->fake();
        $this->as()->getJson('/api/v1/broker/employers/1/audit')->assertStatus(404);          // not in the contract
        $this->as()->putJson('/api/v1/broker/employers/1')->assertStatus(405);
        $this->as()->getJson('/api/v1/broker/anything-else')->assertStatus(404);
        Http::assertNothingSent();
    }
}
