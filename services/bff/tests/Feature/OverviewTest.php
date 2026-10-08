<?php

namespace Tests\Feature;

use Illuminate\Http\Client\ConnectionException;
use Tests\TestCase;

class OverviewTest extends TestCase
{
    private const POLICY = ['id' => 7, 'brokerId' => 1, 'employerId' => 10, 'policyNumber' => 'GMC/7', 'status' => 'APPROVED_AND_LIVE', 'startDate' => '2030-04-01', 'endDate' => '2031-03-31', 'inception' => false, 'proRataType' => 'DAILY'];
    private const CD = ['policyId' => 7, 'employerId' => 10, 'balance' => 100.0, 'reserved' => 0, 'available' => 100.0, 'thresholdAmount' => 10, 'belowThreshold' => false, 'accountId' => 5, 'accountType' => 'POLICY', 'name' => null];
    private const JOBS = ['count' => 1, 'page' => 1, 'page_size' => 5, 'results' => [['id' => 'j1', 'policy_id' => 7, 'employer_id' => 10, 'op_type' => 'ADD', 'state' => 'ENDORSED', 'is_terminal' => false, 'state_ts' => '2030-04-02T00:00:00']]];
    private const SYNCS = ['content' => [['id' => 's1', 'policyId' => 7, 'status' => 'SUCCEEDED', 'syncedCount' => 3, 'rejectedCount' => 0]], 'totalElements' => 1, 'number' => 0, 'size' => 3];

    private function all(array $over = []): array
    {
        return $over + ['policy.test/*' => $this->ok(self::POLICY), 'billing.test/*' => $this->ok(self::CD), 'read.test/*' => $this->ok(self::JOBS), 'integration.test/*' => $this->ok(self::SYNCS)];
    }

    private function ok(array $body, int $status = 200)
    {
        return \Illuminate\Support\Facades\Http::response($body, $status);
    }

    public function test_one_call_composes_the_policy_screen(): void
    {
        $this->fake($this->all());
        $r = $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertOk();
        $r->assertJsonPath('policy.policy_number', 'GMC/7')->assertJsonPath('cd_account.available', 100)->assertJsonPath('recent_endorsements.0.status', 'ENDORSED')
            ->assertJsonPath('tpa_syncs.0.synced', 3)->assertJsonPath('degraded', []);
        $this->assertArrayNotHasKey('accountId', $r->json('cd_account'));
        $this->assertArrayNotHasKey('broker_id', $r->json('policy'));
        \Illuminate\Support\Facades\Http::assertSentCount(4);
    }

    public function test_a_part_that_fails_degrades_the_page_instead_of_failing_it(): void
    {
        $this->fake($this->all(['billing.test/*' => $this->ok(['detail' => 'boom'], 500), 'integration.test/*' => fn () => throw new ConnectionException('timeout')]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertOk()->assertJsonPath('policy.id', 7)->assertJsonPath('cd_account', null)->assertJsonPath('tpa_syncs', [])
            ->assertJsonPath('degraded.0.part', 'cd_account')->assertJsonPath('degraded.0.reason', 'unavailable')->assertJsonPath('degraded.1.part', 'tpa_syncs');
    }

    public function test_a_refused_part_says_why(): void
    {
        $this->fake($this->all(['billing.test/*' => $this->ok([], 403)]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertOk()->assertJsonPath('degraded.0.reason', 'forbidden');
        $this->fake($this->all(['billing.test/*' => $this->ok([], 404)]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertOk()->assertJsonPath('degraded.0.reason', 'not_found');
    }

    public function test_without_the_policy_there_is_no_page(): void
    {
        $this->fake($this->all(['policy.test/*' => $this->ok(['detail' => 'Policy not found'], 404)]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertStatus(404);
        $this->fake($this->all(['policy.test/*' => $this->ok([], 403)]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertStatus(403);
        $this->fake($this->all(['policy.test/*' => $this->ok([], 500)]));
        $this->as()->getJson('/api/v1/broker/policies/7/overview')->assertStatus(502);
    }

    public function test_hr_gets_the_view_without_broker_only_parts(): void
    {
        $this->fake($this->all());
        $r = $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/employer/policies/7/overview')->assertOk();
        $this->assertArrayNotHasKey('tpa_syncs', $r->json());
        \Illuminate\Support\Facades\Http::assertNotSent(fn ($req) => str_contains($req->url(), 'integration.test'));
        \Illuminate\Support\Facades\Http::assertSent(fn ($req) => str_contains($req->url(), '/api/v1/employer/policies/7'));
    }
}
