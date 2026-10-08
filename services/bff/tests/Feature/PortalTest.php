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
