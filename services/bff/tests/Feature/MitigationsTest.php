<?php

namespace Tests\Feature;

use App\Bff\Mitigations;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class MitigationsTest extends TestCase
{
    private function install(array $entries): void
    {
        $path = tempnam(sys_get_temp_dir(), 'mit');
        file_put_contents($path, json_encode($entries));
        config(['bff.mitigations_path' => $path]);
        $this->app->forgetInstance(Mitigations::class);
        $this->app->forgetInstance(\App\Http\Controllers\GatewayController::class);
    }

    private function entry(array $over = []): array
    {
        return $over + ['id' => 'BACKEND_BUG_2030_001', 'endpoint' => 'policies.get', 'reason' => 'status arrives as ACTV', 'owner' => 'ops@ensuretech.test',
            'introduced_at' => '2030-01-01', 'expires_at' => date('Y-m-d', time() + 86400 * 30), 'removal_condition' => 'policy-service 1.4 deployed', 'fix_ref' => 'ENG-123',
            'operations' => [['op' => 'map', 'path' => 'status', 'values' => ['ACTV' => 'APPROVED_AND_LIVE']]]];
    }

    private function policy(string $status = 'ACTV'): void
    {
        $this->fake(['policy.test/*' => Http::response(['id' => 1, 'status' => $status, 'startDate' => '2030-04-01'])]);
    }

    public function test_an_active_mitigation_is_applied_and_announced(): void
    {
        $this->install([$this->entry()]);
        $this->policy();
        $this->as()->getJson('/api/v1/broker/policies/1')->assertOk()->assertJsonPath('status', 'APPROVED_AND_LIVE')->assertHeader('X-Mitigations', 'BACKEND_BUG_2030_001');
    }

    public function test_it_stops_applying_on_its_expiry_date_by_itself(): void
    {
        $this->install([$this->entry(['expires_at' => date('Y-m-d', time() - 86400 * 2)])]);
        $this->policy();
        $this->as()->getJson('/api/v1/broker/policies/1')->assertOk()->assertJsonPath('status', 'ACTV')->assertHeaderMissing('X-Mitigations');
    }

    public function test_an_undocumented_or_unsafe_entry_is_refused_not_applied(): void
    {
        $noOwner = $this->entry(['id' => 'NO_OWNER']);
        unset($noOwner['owner']);
        $this->install([$noOwner, $this->entry(['id' => 'ARITHMETIC', 'operations' => [['op' => 'multiply', 'path' => 'total_premium', 'by' => 0.1]]]),
            $this->entry(['id' => 'BAD_DATE', 'expires_at' => 'whenever']), $this->entry(['id' => 'DISABLED', 'enabled' => false])]);
        $this->policy();
        $this->as()->getJson('/api/v1/broker/policies/1')->assertOk()->assertJsonPath('status', 'ACTV')->assertHeaderMissing('X-Mitigations');
        config(['bff.ops_roles' => ['OPS']]);
        $report = $this->as('OPS')->getJson('/api/v1/ops/mitigations')->assertOk()->json();
        $this->assertEqualsCanonicalizing(['NO_OWNER', 'ARITHMETIC', 'BAD_DATE'], array_column($report['rejected'], 'id'));
        $this->assertSame('disabled', $report['mitigations'][0]['state']);
    }

    public function test_rename_drop_default_and_wildcards(): void
    {
        $this->install([$this->entry(['id' => 'LIST_FIX', 'endpoint' => 'employers.children', 'operations' => [
            ['op' => 'rename', 'path' => '*.display_name', 'to' => 'name'], ['op' => 'drop', 'path' => '*.pan'], ['op' => 'default', 'path' => '*.sector', 'value' => 'UNKNOWN']]])]);
        $this->fake(['org.test/*' => Http::response([['id' => 5, 'displayName' => 'Acme', 'pan' => 'ABCDE1234F', 'sector' => null]])]);
        $row = $this->as()->getJson('/api/v1/broker/employers/1/children')->assertOk()->json('0');
        $this->assertSame('Acme', $row['name']);
        $this->assertArrayNotHasKey('pan', $row);
        $this->assertArrayNotHasKey('display_name', $row);
        $this->assertSame('UNKNOWN', $row['sector']);
    }

    public function test_it_only_touches_its_own_endpoint(): void
    {
        $this->install([$this->entry(['endpoint' => 'policies.list'])]);
        $this->policy();
        $this->as()->getJson('/api/v1/broker/policies/1')->assertOk()->assertJsonPath('status', 'ACTV');
    }
}
