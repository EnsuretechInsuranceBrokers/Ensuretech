<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class OfflineMemberIdsTest extends TestCase
{
    private const REPORT = ['uploadId' => null, 'applied' => false, 'policyId' => 50, 'tpaId' => 3, 'source' => 'BROKER_FOR_TPA', 'filename' => 'a.csv', 'sha256' => 'x', 'rowsTotal' => 1,
        'assigned' => 1, 'reassigned' => 0, 'updated' => 0, 'ecardsAdded' => 0, 'unchanged' => 0, 'rejected' => 0, 'longestWaitHours' => 30.5, 'warnings' => [], 'tookMs' => 4,
        'lines' => [['line' => 2, 'employeeCode' => 'E1', 'relation' => 'Self', 'tpaMemberId' => 'T1', 'outcome' => 'ASSIGNED', 'rosterId' => 1, 'internalNote' => 'LEAK']]];

    public function test_a_broker_uploads_the_sheet_for_the_tpa_with_only_the_declared_fields(): void
    {
        $this->fake(['integration.test/*' => Http::response(self::REPORT)]);
        $file = UploadedFile::fake()->createWithContent('tpa.csv', "Employee code,TPA member id\nE1,T1\n");
        $this->as()->post('/api/v1/broker/integration/policies/50/offline-sheet', ['file' => $file, 'mode' => 'apply', 'ignore_cover_date' => 'true', 'broker_id' => 99],
            ['Accept' => 'application/json'])->assertOk()->assertJsonPath('longest_wait_hours', 30.5)->assertJsonPath('lines.0.member_id', 1)->assertJsonMissingPath('lines.0.internalNote');
        Http::assertSent(function (Request $r) {
            $names = array_column($r->data(), 'name');

            return str_starts_with($r->url(), 'http://integration.test/api/v1/broker/integration/policies/50/offline-sheet') && $names === ['mode', 'ignoreCoverDate', 'file'];
        });
    }

    public function test_a_tpa_user_has_its_own_gateway_and_cannot_reach_the_brokers(): void
    {
        $this->fake(['integration.test/*' => Http::response(['policies' => [], 'totalPendingIds' => 0, 'totalPendingEcards' => 0, 'oldestHours' => 0])]);
        $tpa = fn () => $this->as('TPA_USER', ['tpa_id' => 3, 'broker_id' => null]);
        $tpa()->getJson('/api/v1/tpa/integration/tpa-pending')->assertOk()->assertJsonPath('total_pending_ids', 0);
        Http::assertSent(fn (Request $r) => str_starts_with($r->url(), 'http://integration.test/api/v1/tpa/integration/tpa-pending'));
        $tpa()->getJson('/api/v1/broker/integration/tpa-pending')->assertStatus(403);
        $this->as('TPA_USER', ['broker_id' => 1])->getJson('/api/v1/tpa/integration/tpa-pending')->assertStatus(403);       // no tpa_id in the token
        $this->as()->getJson('/api/v1/tpa/integration/tpa-pending')->assertStatus(403);                                   // a broker on the TPA door
        $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/tpa/integration/tpa-pending')->assertStatus(403);
    }

    public function test_the_template_downloads_as_a_spreadsheet(): void
    {
        $xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
        $this->fake(['integration.test/*' => Http::response('PK-fake', 200, ['Content-Type' => $xlsx, 'Content-Disposition' => 'attachment; filename="t.xlsx"'])]);
        $r = $this->as()->get('/api/v1/broker/integration/policies/50/offline-template');
        $r->assertOk();
        $this->assertStringContainsString('spreadsheetml', (string) $r->headers->get('Content-Type'));
    }
}
