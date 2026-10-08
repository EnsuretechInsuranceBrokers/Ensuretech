<?php

namespace Tests\Feature;

use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class PortalTest extends TestCase
{
    private function signedIn(): static
    {
        $this->fake([
            'identity.test/api/v1/auth/login/' => Http::response(['access' => $this->token(), 'refresh' => 'r-1', 'access_expires_in' => 600]),
            'identity.test/api/v1/auth/me/' => Http::response(['id' => 'u', 'email' => 'b@x.test', 'first_name' => 'B', 'last_name' => 'A', 'role' => 'BROKER_ADMIN', 'broker_id' => 1, 'employer_id' => null, 'is_active' => true]),
            'read.test/*' => Http::response(['jobs' => ['total' => 3, 'open' => 1, 'by_status' => []], 'cd' => ['accounts' => 2, 'low' => 1], 'tpa_syncs' => ['failed' => 0, 'completed' => 4], 'count' => 0, 'pending_days' => 7, 'flags' => []]),
            'engine.test/*' => Http::response(['count' => 0, 'results' => [], 'page' => 1, 'page_size' => 5]),
            'policy.test/*' => Http::response(['content' => [], 'totalElements' => 0, 'number' => 0, 'size' => 5]),
        ]);
        $this->post('/login', ['email' => 'b@x.test', 'password' => 'pw'])->assertRedirect('/app');

        return $this;
    }

    public function test_pages_need_a_session_and_the_login_page_is_public(): void
    {
        $this->get('/app')->assertRedirect('/login');
        $this->get('/app/policies')->assertRedirect('/login');
        $this->get('/login')->assertOk()->assertSee('Sign in');
    }

    public function test_signing_in_stores_tokens_server_side_and_never_in_the_page_or_cookie(): void
    {
        $this->signedIn();
        $page = $this->get('/app')->assertOk()->assertSee('Dashboard')->assertSee('Endorsement jobs');
        $this->assertStringNotContainsString($this->app['session.store']->get('portal.access'), $page->getContent());
        $this->assertStringNotContainsString('r-1', $page->getContent());
        $this->assertNotNull($this->app['session.store']->get('portal.access'));
    }

    public function test_every_call_to_a_core_service_carries_the_secret_and_the_session_key(): void
    {
        $this->signedIn()->get('/app')->assertOk();
        Http::assertSent(function (Request $r) {
            $sid = $r->header('X-Session-Id')[0] ?? '';

            return str_contains($r->url(), 'read.test') && ($r->header('X-Gateway-Secret')[0] ?? '') === 'test-gateway-secret'
                && hash_equals(hash_hmac('sha256', $sid, 'test-gateway-secret'), $r->header('X-Session-Key')[0] ?? '');
        });
    }

    private const POLICY = ['id' => 7, 'employerId' => 3, 'policyNumber' => 'POL-7', 'insurerId' => 1, 'tpaId' => null, 'policyType' => 'GMC', 'policySubType' => null, 'status' => 'NOT_APPROVED',
        'startDate' => '2030-04-01', 'endDate' => '2031-03-31', 'renewalDate' => null, 'maxMembers' => 100, 'inception' => false, 'proRataType' => 'DAILY'];

    public function test_every_page_renders_for_a_broker(): void
    {
        $this->fake([
            'identity.test/api/v1/auth/login/' => Http::response(['access' => $this->token(), 'refresh' => 'r-1', 'access_expires_in' => 600]),
            'identity.test/api/v1/auth/me/' => Http::response(['id' => 'u', 'email' => 'b@x.test', 'first_name' => 'B', 'last_name' => 'A', 'role' => 'BROKER_ADMIN', 'broker_id' => 1, 'employer_id' => null, 'is_active' => true]),
            'read.test/*' => Http::response(['jobs' => ['total' => 3, 'open' => 1, 'by_status' => []], 'cd' => ['accounts' => 2, 'low' => 1], 'tpa_syncs' => ['failed' => 2, 'completed' => 4], 'count' => 1, 'pending_days' => 7, 'flags' => []]),
            'engine.test/api/v1/broker/endorsements/' => Http::response(['count' => 1, 'results' => [['id' => 'a1b2c3d4-0000-4000-8000-000000000001', 'policy_id' => 7, 'op_type' => 'ADD', 'status' => 'CD_INSUFFICIENT', 'source_filename' => 'f.csv',
                'phase' => 'CD', 'category' => 'BLOCKED', 'is_terminal' => false, 'total_rows' => 4, 'failed_rows' => 0, 'premium_delta' => '10.00']], 'page' => 1, 'page_size' => 5]),
            'engine.test/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/errors/*' => Http::response(['count' => 1, 'page' => 1, 'page_size' => 100, 'summary' => [],
                'results' => [['line' => 3, 'employee_code' => 'E1', 'field' => 'dob', 'rule' => 'INVALID_DOB', 'severity' => 'ERROR', 'message' => 'unreadable date', 'value' => 'x']]]),
            'engine.test/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/audit/*' => Http::response([['from_state' => 'REQUEST_RAISED', 'to_state' => 'FILE_STREAMED', 'phase' => 'P', 'category' => 'C', 'actor' => 'SYSTEM', 'created_at' => '2030-01-01T10:00:00.5Z', 'ts' => '2030-01-01T10:00:00.5Z']]),
            'engine.test/api/v1/broker/endorsements/a1b2c3d4-0000-4000-8000-000000000001/*' => Http::response(['id' => 'a1b2c3d4-0000-4000-8000-000000000001', 'policy_id' => 7, 'op_type' => 'ADD', 'status' => 'CD_INSUFFICIENT',
                'source_filename' => 'f.csv', 'phase' => 'CD', 'category' => 'BLOCKED', 'is_terminal' => false, 'effective_date' => '2030-06-01', 'total_rows' => 4, 'failed_rows' => 0, 'cd_status' => 'INSUFFICIENT',
                'total_premium' => '1', 'tax_amount' => '0', 'premium_delta' => '1']),
            'billing.test/api/v1/broker/cd/accounts/7' => Http::response(['policyId' => 7, 'employerId' => 3, 'balance' => 100, 'reserved' => 0, 'available' => 100, 'thresholdAmount' => 10, 'belowThreshold' => false]),
            'billing.test/*' => Http::response([['policyId' => 7, 'employerId' => 3, 'balance' => 100, 'reserved' => 0, 'available' => 100, 'thresholdAmount' => 10, 'belowThreshold' => false]]),
            'org.test/api/v1/broker/announcements' => Http::response([['id' => 1, 'kind' => 'BANNER', 'title' => 'Hi', 'content' => 'There', 'link' => 'https://x.test', 'imageUrl' => null, 'position' => 'TOP', 'sequence' => 0,
                'allEmployers' => false, 'employerIds' => [3], 'startsOn' => '2030-01-01', 'ttlDays' => 5, 'endsOn' => '2030-01-05', 'active' => true, 'live' => true]]),
            'org.test/*' => Http::response(['content' => [], 'totalElements' => 0, 'number' => 0, 'size' => 25]),
            'policy.test/api/v1/broker/policies/7/enrollment-window/employees' => Http::response([['employeeRef' => 'E1', 'enabled' => true, 'startDate' => '2030-05-01', 'endDate' => '2030-05-20', 'closeDate' => null]]),
            'policy.test/api/v1/broker/policies/7/enrollment-window/audit*' => Http::response([['id' => 1, 'scope' => 'POLICY_LEVEL', 'employees' => [], 'oldValue' => null, 'newValue' => [], 'modifiedBy' => 'abcdef1234567890', 'at' => '2030-01-01T10:00:00.25Z']]),
            'policy.test/api/v1/broker/policies/7/enrollment-window/evaluate' => Http::response(['status' => 'OPEN', 'startDate' => '2030-03-01', 'endDate' => '2030-04-30', 'windowSet' => true, 'midtermWindowSet' => true,
                'endtermWindowSet' => false, 'customWindowSet' => false]),
            'policy.test/api/v1/broker/policies/7/enrollment-window' => Http::response(['enabled' => true, 'startDate' => '2030-03-01', 'endDate' => '2030-04-30', 'midtermDays' => 30, 'allowAfterExpiry' => false, 'daysAfterExpiry' => 0]),
            'policy.test/api/v1/broker/policies/7/readiness' => Http::response(['valid' => false, 'issues' => [['code' => 'NO_ACTIVE_RATE_CARD', 'message' => 'No active rate card']]]),
            'policy.test/api/v1/broker/policies/7' => Http::response(self::POLICY),
            'policy.test/*' => Http::response(['content' => [self::POLICY], 'totalElements' => 1, 'number' => 0, 'size' => 25]),
        ]);
        $this->post('/login', ['email' => 'b@x.test', 'password' => 'pw'])->assertRedirect('/app');
        foreach (['/app' => 'Recent endorsements', '/app/employers' => 'Add an employer', '/app/policies' => 'POL-7', '/app/policies/7' => 'Enrollment window', '/app/announcements' => 'New announcement',
                  '/app/endorsements' => 'Upload a file', '/app/endorsements/a1b2c3d4-0000-4000-8000-000000000001' => 'Problems found', '/app/cd' => 'Record money received', '/app/assistant' => 'Ask'] as $path => $needle) {
            $this->get($path)->assertOk()->assertSee($needle, false);
        }
        $this->get('/app')->assertSee('Hi')->assertSee('/portal.css', false);
        $this->get('/app/policies/7')->assertSee('No active rate card')->assertSee('E1');
        $this->post('/app/policies/7/window/check', ['employee_ref' => 'E1', 'date_of_joining' => '2030-04-02'])->assertRedirect('/app/policies/7')->assertSessionHas('window_result');
        $this->post('/app/policies/7/window', ['enabled' => '1', 'start_date' => '2030-03-01', 'end_date' => '2030-04-30', 'midterm_days' => 20])->assertRedirect('/app/policies/7');
        Http::assertSent(fn (Request $q) => $q->method() === 'PUT' && str_ends_with($q->url(), '/enrollment-window') && $q['enabled'] === true && $q['midtermDays'] === 20 && $q['allowAfterExpiry'] === false);
    }

    public function test_a_failed_sign_in_gives_a_generic_message(): void
    {
        $this->fake(['identity.test/*' => Http::response(['detail' => 'No active account found with the given credentials'], 401)]);
        $this->post('/login', ['email' => 'b@x.test', 'password' => 'bad'])->assertRedirect()->assertSessionHas('err', 'Sign-in failed.');
    }

    public function test_every_form_carries_a_csrf_token(): void
    {
        $this->assertStringContainsString('name="_token"', $this->get('/login')->getContent());
    }

    public function test_the_portal_sends_its_own_security_headers(): void
    {
        $r = $this->get('/login');
        $this->assertStringContainsString("default-src 'none'", $r->headers->get('Content-Security-Policy'));
        $this->assertSame('DENY', $r->headers->get('X-Frame-Options'));
    }
}
