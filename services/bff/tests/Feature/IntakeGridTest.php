<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

/** "How many employees?": the portal's pages and JSON routes for the live grid and the spreadsheet route. The engine is faked; the rules are the engine's. */
class IntakeGridTest extends TestCase
{
    private const SID = 'b1b2c3d4-0000-4000-8000-0000000000aa';

    private function gridPlan(int $n = 5): array
    {
        return ['mode' => 'GRID', 'employee_count' => $n, 'op_type' => 'ADD', 'policy_id' => 7, 'grid_max_employees' => 100, 'session_id' => self::SID, 'expires_at' => '2030-01-01T10:00:00Z',
            'schema' => ['operations' => ['ADD'], 'columns' => [['field' => 'employee_code', 'label' => 'Employee code', 'type' => 'string', 'required_for' => ['ADD']], ['field' => 'relation', 'label' => 'Relation', 'type' => 'string', 'required_for' => ['ADD']]], 'relations' => ['SELF', 'SPOUSE']],
            'grid' => ['initial_rows' => $n, 'row_cap' => $n * 8, 'validate' => 'live'], 'file' => null];
    }

    private function filePlan(int $n = 500): array
    {
        return ['mode' => 'FILE', 'employee_count' => $n, 'op_type' => 'ADD', 'policy_id' => 7, 'grid_max_employees' => 100, 'session_id' => null, 'schema' => null, 'grid' => null,
            'file' => ['template_rows' => $n, 'chunk_rows' => 500, 'estimated_chunks' => (int) ceil($n / 500), 'max_rows' => 1000000, 'formats' => ['xlsx', 'csv']]];
    }

    private function signedIn(array $extra = []): static
    {
        $this->fake($extra + [
            'identity.test/api/v1/auth/login/' => Http::response(['access' => $this->token(), 'refresh' => 'r-1', 'access_expires_in' => 600]),
            'identity.test/api/v1/auth/me/' => Http::response(['id' => 'u', 'email' => 'b@x.test', 'first_name' => 'B', 'last_name' => 'A', 'role' => 'BROKER_ADMIN', 'broker_id' => 1, 'employer_id' => null, 'is_active' => true]),
            'policy.test/*' => Http::response(['content' => [['id' => 7, 'employerId' => 10, 'policyNumber' => 'POL-7', 'policyType' => 'GMC', 'status' => 'APPROVED_AND_LIVE', 'startDate' => '2030-04-01', 'endDate' => '2031-03-31']], 'totalElements' => 1, 'number' => 0, 'size' => 100]),
        ]);
        $this->post('/login', ['email' => 'b@x.test', 'password' => 'pw'])->assertRedirect('/app');

        return $this;
    }

    private function openGrid(int $n = 5): void
    {
        $this->signedIn(['engine.test/api/v1/broker/endorsements/intake/plan/' => Http::response($this->gridPlan($n), 201)]);
        $this->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'ADD', 'employee_count' => $n, 'effective_date' => '2030-07-01'])->assertRedirect('/app/endorse/grid/'.self::SID);
    }

    public function test_the_form_offers_live_policies_and_says_where_the_line_is(): void
    {
        $this->signedIn()->get('/app/endorse')->assertOk()->assertSee('How many employees')->assertSee('POL-7')->assertSee('Up to 100')->assertSee('data-limit="100"', false);
        $this->get('/app/endorsements')->assertOk()->assertSee('Not sure how?');
    }

    public function test_a_small_number_opens_a_grid_session_sized_for_the_count(): void
    {
        $this->openGrid(5);
        $page = $this->get('/app/endorse/grid/'.self::SID)->assertOk()->assertSee('Enter 5 employees')->assertSee('portal-grid.js', false)
            ->assertSee('data-cap="40"', false)->assertSee('"relations":["SELF","SPOUSE"]', false);
        $this->assertStringContainsString('nonce="', $page->getContent());                 // the script is nonce'd; the page's CSP allows nothing else
        $this->assertStringNotContainsString($this->app['session.store']->get('portal.access'), $page->getContent());
        Http::assertSent(fn (Request $r) => str_contains($r->url(), '/intake/plan/') && $r['employee_count'] === 5 && $r['policy_id'] === 7 && $r['effective_date'] === '2030-07-01');
    }

    public function test_a_large_number_gets_the_spreadsheet_page_and_a_download(): void
    {
        $this->signedIn(['engine.test/api/v1/broker/endorsements/intake/plan/' => Http::response($this->filePlan(2500))]);
        $this->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'ADD', 'employee_count' => 2500])->assertRedirect('/app/endorse/file?policy_id=7&op_type=ADD&employee_count=2500');
        $this->get('/app/endorse/file?policy_id=7&op_type=ADD&employee_count=2500')->assertOk()->assertSee('2,500 employees')->assertSee('about 5 for this size')
            ->assertSee('/app/endorse/template?policy_id=7&amp;op_type=ADD&amp;employee_count=2500', false)->assertSee('action="/app/endorsements/upload"', false);
    }

    public function test_the_file_page_will_not_serve_a_size_that_belongs_in_the_grid(): void
    {
        $this->signedIn(['engine.test/api/v1/broker/endorsements/intake/plan/' => Http::response($this->gridPlan(5), 201)]);
        $this->get('/app/endorse/file?policy_id=7&op_type=ADD&employee_count=5')->assertRedirect('/app/endorse');
    }

    public function test_the_template_comes_back_as_a_download(): void
    {
        $xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
        $this->signedIn(['engine.test/api/v1/broker/endorsements/intake/template/*' => Http::response('PK-bytes', 200, ['Content-Type' => $xlsx, 'Content-Disposition' => 'attachment; filename="endorsement-add-POL-7.xlsx"'])]);
        $r = $this->get('/app/endorse/template?policy_id=7&op_type=ADD&employee_count=500&effective_date=2030-07-01')->assertOk();
        $this->assertStringContainsString('spreadsheetml', (string) $r->headers->get('Content-Type'));
        $this->assertStringContainsString('attachment', (string) $r->headers->get('Content-Disposition'));
        Http::assertSent(fn (Request $q) => str_contains($q->url(), '/intake/template/') && str_contains($q->url(), 'employee_count=500') && str_contains($q->url(), 'effective_date=2030-07-01'));
    }

    public function test_bad_numbers_are_refused_before_the_engine_is_asked(): void
    {
        $this->signedIn();
        $this->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'ADD', 'employee_count' => 0])->assertSessionHasErrors('employee_count');
        $this->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'ADD', 'employee_count' => 'lots'])->assertSessionHasErrors('employee_count');
        $this->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'MERGE', 'employee_count' => 5])->assertSessionHasErrors('op_type');
        Http::assertNotSent(fn (Request $r) => str_contains($r->url(), 'engine.test'));
    }

    public function test_an_engine_refusal_comes_back_as_a_message(): void
    {
        $this->signedIn(['engine.test/*' => Http::response(['detail' => 'policy is NOT_APPROVED; endorsements need an APPROVED_AND_LIVE policy'], 422)]);
        $this->from('/app/endorse')->post('/app/endorse/plan', ['policy_id' => 7, 'op_type' => 'ADD', 'employee_count' => 5])->assertRedirect('/app/endorse')->assertSessionHas('err');
    }

    public function test_every_pause_in_typing_sends_the_whole_grid_and_returns_the_verdict(): void
    {
        $this->openGrid(2);
        $verdict = ['rows' => [['index' => 0, 'row_key' => 'r1', 'blank' => false, 'valid' => true, 'issues' => []], ['index' => 1, 'row_key' => 'r2', 'blank' => true, 'valid' => false, 'issues' => []]],
            'summary' => ['rows_typed' => 1, 'rows_with_errors' => 0, 'employees_entered' => 1, 'expected_employees' => 2], 'checks' => ['family_rules' => true]];
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/validate/' => Http::response($verdict)]);
        $rows = [['row_key' => 'r1', 'employee_code' => '1001', 'relation' => 'SELF', 'dob' => '17/05/1985'], ['row_key' => 'r2']];
        $this->postJson('/app/endorse/grid/'.self::SID.'/validate', ['op_type' => 'ADD', 'rows' => $rows])->assertOk()->assertJsonPath('summary.rows_typed', 1)->assertJsonPath('rows.0.valid', true);
        Http::assertSent(function (Request $r) {
            return str_contains($r->url(), '/grid/validate/') && count($r['rows']) === 2 && $r['rows'][0]['dob'] === '17/05/1985' && $r['op_type'] === 'ADD'
                && ! $r->hasHeader('X-Gateway-Secret') && ! empty($r->header('X-Gateway-Signature')[0]);
        });
    }

    public function test_a_grid_that_was_never_opened_here_is_refused(): void
    {
        $this->signedIn();
        $this->postJson('/app/endorse/grid/'.self::SID.'/validate', ['op_type' => 'ADD', 'rows' => []])->assertNotFound();
        $this->postJson('/app/endorse/grid/'.self::SID.'/submit', ['op_type' => 'ADD', 'rows' => []])->assertNotFound();
        $this->get('/app/endorse/grid/'.self::SID)->assertRedirect('/app/endorse');
        $this->get('/app/endorse/grid/not-a-uuid')->assertNotFound();
    }

    public function test_submit_saves_then_submits_and_goes_to_the_job(): void
    {
        $this->openGrid(1);
        $job = 'c1b2c3d4-0000-4000-8000-0000000000bb';
        $ok = ['rows' => [['index' => 0, 'blank' => false, 'valid' => true, 'issues' => []]], 'summary' => ['rows_typed' => 1], 'checks' => [], 'saved_rows' => 1];
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/' => Http::response($ok),
            'engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/submit/' => Http::response(['session_id' => self::SID, 'jobs' => [['job_id' => $job, 'op_type' => 'ADD', 'rows' => 1]]], 202)]);
        $this->postJson('/app/endorse/grid/'.self::SID.'/submit', ['op_type' => 'ADD', 'rows' => [['row_key' => 'r1', 'employee_code' => '1']]])->assertOk()->assertJsonPath('redirect', "/app/endorsements/$job");
        $this->get('/app/endorse/grid/'.self::SID)->assertRedirect('/app/endorse');                  // a submitted grid is closed
    }

    public function test_a_grid_with_errors_is_saved_but_never_submitted(): void
    {
        $this->openGrid(1);
        $bad = ['rows' => [['index' => 0, 'blank' => false, 'valid' => false, 'issues' => [['field' => 'dob', 'rule' => 'INVALID_DOB', 'severity' => 'ERROR', 'message' => 'unreadable date']]]], 'summary' => ['rows_with_errors' => 1], 'checks' => [], 'saved_rows' => 1];
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/' => Http::response($bad)]);
        $this->postJson('/app/endorse/grid/'.self::SID.'/submit', ['op_type' => 'ADD', 'rows' => [['row_key' => 'r1']]])->assertStatus(422)->assertJsonPath('result.summary.rows_with_errors', 1);
        Http::assertNotSent(fn (Request $r) => str_contains($r->url(), '/submit/'));
    }

    public function test_typing_sends_only_the_row_that_changed(): void
    {
        $this->openGrid(3);
        $answer = ['row' => ['row_key' => 'r2', 'blank' => false, 'valid' => false, 'issues' => [['field' => 'dob', 'rule' => 'INVALID_DOB', 'severity' => 'ERROR', 'message' => 'x']]],
            'changed' => [['row_key' => 'r1', 'blank' => false, 'valid' => true, 'issues' => []]], 'summary' => ['rows_typed' => 2], 'checks' => ['family_rules' => true], 'v' => 4];
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/rows/r2/' => Http::response($answer)]);
        $this->putJson('/app/endorse/grid/'.self::SID.'/row/r2', ['op_type' => 'ADD', 'row' => ['employee_code' => '1001', 'dob' => '31/02/1983'], 'position' => 1, 'grid_rows' => 3, 'v' => 4])
            ->assertOk()->assertJsonPath('row.row_key', 'r2')->assertJsonPath('changed.0.row_key', 'r1')->assertJsonPath('v', 4);
        Http::assertSent(function (Request $r) {
            return $r->method() === 'PUT' && str_contains($r->url(), '/grid/rows/r2/') && $r['row'] === ['employee_code' => '1001', 'dob' => '31/02/1983'] && $r['position'] === 1
                && ! isset($r['rows']) && strlen($r->body()) < 300;                         // one row, a few hundred bytes at most, whatever the size of the grid
        });
    }

    public function test_deleting_a_row_goes_to_the_one_row_route(): void
    {
        $this->openGrid(3);
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/rows/r2/*' => Http::response(['row' => ['row_key' => 'r2', 'blank' => true, 'issues' => [], 'valid' => false], 'changed' => [], 'summary' => ['rows_typed' => 1], 'checks' => ['family_rules' => false]])]);
        $this->deleteJson('/app/endorse/grid/'.self::SID.'/row/r2?op_type=ADD&grid_rows=3')->assertOk()->assertJsonPath('row.blank', true);
        Http::assertSent(fn (Request $r) => $r->method() === 'DELETE' && str_contains($r->url(), 'op_type=ADD') && str_contains($r->url(), 'grid_rows=3'));
    }

    public function test_the_row_and_sync_routes_refuse_what_was_never_opened_here_or_has_a_bad_key(): void
    {
        $this->signedIn();
        $this->putJson('/app/endorse/grid/'.self::SID.'/row/r1', ['op_type' => 'ADD', 'row' => ['a' => 'b']])->assertNotFound();
        $this->postJson('/app/endorse/grid/'.self::SID.'/sync', ['op_type' => 'ADD', 'rows' => []])->assertNotFound();
        $this->openGrid(1);
        $this->putJson('/app/endorse/grid/'.self::SID.'/row/'.rawurlencode('../x'), ['op_type' => 'ADD', 'row' => ['a' => 'b']])->assertNotFound();
        $this->putJson('/app/endorse/grid/'.self::SID.'/row/'.str_repeat('a', 65), ['op_type' => 'ADD', 'row' => ['a' => 'b']])->assertNotFound();
    }

    public function test_a_paste_goes_once_as_a_whole_grid(): void
    {
        $this->openGrid(2);
        Http::fake(['engine.test/api/v1/broker/endorsements/gui/sessions/'.self::SID.'/grid/' => Http::response(['rows' => [['index' => 0, 'row_key' => 'r1', 'blank' => false, 'valid' => true, 'issues' => []]], 'summary' => ['rows_typed' => 1], 'checks' => [], 'saved_rows' => 1])]);
        $this->postJson('/app/endorse/grid/'.self::SID.'/sync', ['op_type' => 'ADD', 'rows' => [['row_key' => 'r1', 'employee_code' => '1']]])->assertOk()->assertJsonPath('saved_rows', 1);
        Http::assertSent(fn (Request $r) => $r->method() === 'PUT' && str_ends_with($r->url(), '/grid/') && count($r['rows']) === 1);
    }
}
