<?php

namespace Tests\Feature;

use App\Bff\Contract;
use Illuminate\Http\Client\Request;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

/** Every BFF API has a screen. Adding an endpoint to the contract without saying which page or card serves it fails here. */
class PortalCoverageTest extends TestCase
{
    private function everyEndpoint(): array
    {
        config(['bff.features.qa_tools' => true]);

        return (new Contract)->endpoints();
    }

    public function test_every_contract_endpoint_is_served_by_a_page_or_a_card(): void
    {
        $native = config('portal_modules.native');
        $cards = config('portal_modules.endpoints');
        $missing = [];
        foreach (array_keys($this->everyEndpoint()) as $name) {
            if (! isset($native[$name]) && ! isset($cards[$name])) {
                $missing[] = $name;
            }
        }
        $this->assertSame([], $missing, 'these API endpoints have no screen; add them to config/portal_modules.php: '.implode(', ', $missing));
    }

    public function test_the_registry_names_no_endpoint_that_does_not_exist_and_no_endpoint_twice(): void
    {
        $all = $this->everyEndpoint();
        $native = config('portal_modules.native');
        $cards = config('portal_modules.endpoints');
        $this->assertSame([], array_values(array_diff(array_merge(array_keys($native), array_keys($cards)), array_keys($all))), 'the registry lists endpoints the contract does not have');
        $this->assertSame([], array_values(array_intersect(array_keys($native), array_keys($cards))), 'an endpoint is both native and a card');
        foreach ($cards as $name => [$module, $title, $help]) {
            $this->assertArrayHasKey($module, config('portal_modules.modules'), "$name is in an unknown module");
            $this->assertNotSame('', trim($title.$help), "$name has no plain-language wording");
        }
    }

    public function test_native_pages_exist_as_routes(): void
    {
        $urls = [];
        foreach (config('portal_modules.native') as [$url]) {
            $urls[$url] = true;
        }
        $routes = collect(app('router')->getRoutes()->getRoutes())->map(fn ($r) => '/'.ltrim($r->uri(), '/'))->all();
        foreach (array_keys($urls) as $u) {
            $this->assertTrue(in_array($u, $routes, true) || $u === '/app', "no page at $u");
        }
    }

    private function signedIn(array $extra = [], string $role = 'BROKER_ADMIN'): static
    {
        $this->fake($extra + [
            'identity.test/api/v1/auth/login/' => Http::response(['access' => $this->token($role), 'refresh' => 'r-1', 'access_expires_in' => 600]),
            'identity.test/api/v1/auth/me/' => Http::response(['id' => 'u', 'email' => 'b@x.test', 'first_name' => 'B', 'last_name' => 'A', 'role' => $role, 'broker_id' => 1, 'employer_id' => $role === 'BROKER_ADMIN' ? null : 10, 'is_active' => true]),
            'org.test/*' => Http::response(['content' => [['id' => 10, 'brokerId' => 1, 'legalName' => 'Acme Ltd', 'displayName' => 'Acme']], 'totalElements' => 1, 'number' => 0, 'size' => 100]),
            'policy.test/*' => Http::response(['content' => [['id' => 7, 'employerId' => 10, 'policyNumber' => 'POL-7', 'policyType' => 'GMC', 'status' => 'APPROVED_AND_LIVE']], 'totalElements' => 1, 'number' => 0, 'size' => 100]),
        ]);
        $this->post('/login', ['email' => 'b@x.test', 'password' => 'pw'])->assertRedirect('/app');

        return $this;
    }

    public function test_the_tools_area_lists_its_modules_and_every_card_has_plain_wording(): void
    {
        $this->signedIn()->get('/app/manage')->assertOk()->assertSee('Settings and tools')->assertSee('Policy setup')->assertSee('Deposits')->assertSee('Where the main jobs live');
        $this->get('/app/manage/policies')->assertOk()->assertSee('Set the rounding rule')->assertSee('Work out a premium');
        $this->get('/app/manage/nonsense')->assertNotFound();
    }

    public function test_a_card_calls_the_same_api_with_the_persons_values(): void
    {
        $this->signedIn(['policy.test/api/v1/broker/policies/7/rounding' => Http::response(['type' => 'ROUND', 'unit' => 10])]);
        $r = $this->postJson('/app/manage/call', ['name' => 'policies.rounding_put', 'params' => json_encode(['id' => '7']), 'body' => json_encode(['type' => 'ROUND', 'unit' => 10])])->assertOk();
        $this->assertTrue($r->json('ok'));
        Http::assertSent(fn (Request $q) => $q->method() === 'PUT' && str_contains($q->url(), '/policies/7/rounding') && $q['type'] === 'ROUND' && $q['unit'] === 10);
    }

    public function test_the_api_rules_still_apply_through_a_card(): void
    {
        $this->signedIn();
        $r = $this->postJson('/app/manage/call', ['name' => 'policies.rounding_put', 'params' => json_encode(['id' => '7']), 'body' => json_encode(['type' => 'SIDEWAYS'])])->assertOk();
        $this->assertFalse($r->json('ok'));
        $this->assertSame(422, $r->json('status'));
    }

    public function test_a_card_cannot_reach_what_it_should_not(): void
    {
        $this->signedIn();
        $this->postJson('/app/manage/call', ['name' => 'policies.list'])->assertNotFound();                                         // has its own page
        $this->postJson('/app/manage/call', ['name' => 'no.such.thing'])->assertNotFound();
        $this->postJson('/app/manage/call', ['name' => 'policies.rounding_put', 'params' => json_encode(['id' => '7/../../x']), 'body' => '{}'])->assertStatus(422);   // path parameter outside the contract's pattern
        $this->postJson('/app/manage/call', ['name' => 'policies.rounding_put', 'params' => json_encode(['id' => 'abc']), 'body' => '{}'])->assertStatus(422);
        Http::assertNotSent(fn (Request $q) => str_contains($q->url(), 'rounding') || str_contains($q->url(), '..'));
    }

    public function test_an_employer_user_gets_only_what_the_employer_api_offers(): void
    {
        $this->signedIn([], 'EMPLOYER_ADMIN')->get('/app/manage')->assertOk()->assertDontSee('Policy setup');
        $this->postJson('/app/manage/call', ['name' => 'policies.rounding_put', 'params' => json_encode(['id' => '7']), 'body' => '{}'])->assertNotFound();
    }

    public function test_the_upload_forms_have_an_employer_dropdown_that_narrows_the_policies_and_accept_excel(): void
    {
        $this->signedIn()->get('/app/endorsements')->assertOk()->assertSee('id="employer-pick"', false)->assertSee('data-narrows="policy-pick"', false)->assertSee('All employers')
            ->assertSee('data-employer="10"', false)->assertSee('POL-7 · Acme')->assertSee('CSV or Excel file')->assertSee('.xlsx');
        $this->get('/app/endorse')->assertOk()->assertSee('id="employer-pick"', false);
    }
}
