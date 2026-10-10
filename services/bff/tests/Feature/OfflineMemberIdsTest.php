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

    private const WAITING = ['policyId' => 50, 'policyNumber' => 'GMC/50', 'tpaId' => 3, 'policyStart' => '2030-04-01', 'policyEnd' => '2031-03-31', 'inception' => false, 'pendingIds' => 1,
        'pendingEcards' => 0, 'oldestHours' => 30.0, 'urgency' => 'CRITICAL', 'nextAfter' => null,
        'members' => [['id' => 1, 'employeeCode' => 'E1', 'relationCode' => 'SELF', 'name' => 'Asha Rao', 'coverStart' => '2030-04-01', 'endorsementRef' => 'abcd1234', 'tpaMemberId' => null,
            'ecardUrl' => null, 'endorsedAt' => '2030-04-01T00:00:00Z', 'hoursWaiting' => 30.0, 'urgency' => 'CRITICAL']]];

    private function portalAs(string $role, array $claims, string $home): static
    {
        $this->fake([
            'identity.test/api/v1/auth/login/' => Http::response(['access' => $this->token($role, $claims), 'refresh' => 'r-1', 'access_expires_in' => 600]),
            'identity.test/api/v1/auth/me/' => Http::response(['id' => 'u', 'email' => 'x@x.test', 'first_name' => 'X', 'last_name' => 'Y', 'role' => $role, 'broker_id' => 1, 'employer_id' => null, 'is_active' => true]),
            'integration.test/*/policies/50/tpa-pending*' => Http::response(self::WAITING),
            'integration.test/*/tpa-pending*' => Http::response(['policies' => [['policyId' => 50, 'policyNumber' => 'GMC/50', 'tpaId' => 3, 'employerId' => 10, 'pendingIds' => 1, 'pendingEcards' => 0,
                'oldestHours' => 30.0, 'urgency' => 'CRITICAL']], 'totalPendingIds' => 1, 'totalPendingEcards' => 0, 'oldestHours' => 30.0]),
            'integration.test/*offline-uploads*' => Http::response([]),
            'integration.test/*offline-sheet*' => fn (Request $r) => Http::response(array_merge(self::REPORT, ['applied' => collect($r->data())->firstWhere('name', 'mode')['contents'] === 'apply'])),
        ]);
        $this->post('/login', ['email' => 'x@x.test', 'password' => 'pw'])->assertRedirect($home);

        return $this;
    }

    public function test_a_broker_checks_then_saves_a_sheet_without_uploading_it_twice(): void
    {
        $this->portalAs('BROKER_ADMIN', [], '/app');
        $this->get('/app/member-ids')->assertOk()->assertSee('GMC/50')->assertSee('Longest wait');
        $this->get('/app/member-ids/50')->assertOk()->assertSee('Download .xlsx')->assertSee('Asha Rao');
        $file = UploadedFile::fake()->createWithContent('tpa.csv', "Employee code,TPA member id\nE1,T1\n");
        $page = $this->post('/app/member-ids/50/check', ['file' => $file])->assertOk()->assertSee('Checked, nothing saved yet')->assertSee('Save 1 line');
        preg_match('/name="token" value="([0-9a-f]{32})"/', $page->getContent(), $m);
        $this->assertNotEmpty($m, 'the check page carries a token for saving');
        Http::assertSent(fn (Request $r) => str_contains($r->url(), 'offline-sheet') && collect($r->data())->firstWhere('name', 'mode')['contents'] === 'preview');
        $this->post('/app/member-ids/50/apply', ['token' => $m[1]])->assertOk()->assertSee('Saved');
        Http::assertSent(fn (Request $r) => str_contains($r->url(), 'offline-sheet') && collect($r->data())->firstWhere('name', 'mode')['contents'] === 'apply'
            && str_contains((string) collect($r->data())->firstWhere('name', 'file')['contents'], 'E1,T1'));
        $this->post('/app/member-ids/50/apply', ['token' => $m[1]])->assertRedirect('/app/member-ids/50');           // the held file is gone once saved
    }

    public function test_a_tpa_user_signs_in_to_its_own_pages_only(): void
    {
        $this->portalAs('TPA_USER', ['tpa_id' => 3, 'broker_id' => null], '/app/member-ids');
        $this->get('/app/member-ids')->assertOk()->assertSee('Member ids and e-cards')->assertDontSee('Employers');
        $this->get('/app')->assertRedirect('/app/member-ids');
        $this->get('/app/member-ids/50')->assertOk();
        Http::assertSent(fn (Request $r) => str_contains($r->url(), '/api/v1/tpa/integration/'));
        $this->get('/app/policies')->assertRedirect('/app/member-ids');
    }
}
