<?php

namespace Tests\Feature;

use Firebase\JWT\JWT;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class AuthTest extends TestCase
{
    private function fakeEmployers(): void
    {
        $this->fake(['org.test/*' => Http::response(['content' => [], 'totalElements' => 0, 'totalPages' => 0, 'number' => 0, 'size' => 50])]);
    }

    public function test_no_token_is_401_and_nothing_is_called(): void
    {
        $this->getJson('/api/v1/broker/employers')->assertStatus(401)->assertHeader('WWW-Authenticate', 'Bearer')
            ->assertJsonPath('title', 'Authentication required')->assertHeader('Content-Type', 'application/problem+json');
        Http::assertNothingSent();
    }

    public function test_a_valid_broker_token_reaches_the_service(): void
    {
        $this->fakeEmployers();
        $this->as()->getJson('/api/v1/broker/employers')->assertOk()->assertJsonPath('items', []);
    }

    public function test_bad_tokens_are_all_the_same_401(): void
    {
        $other = openssl_pkey_new(['private_key_bits' => 2048]);
        openssl_pkey_export($other, $otherPrivate);
        $cases = [
            'garbage' => 'not.a.jwt',
            'signed with someone elses key' => $this->token('BROKER_ADMIN', [], $otherPrivate),
            'expired' => $this->token('BROKER_ADMIN', ['exp' => time() - 3600, 'iat' => time() - 7200]),
            'refresh token' => $this->token('BROKER_ADMIN', ['token_type' => 'refresh']),
            'wrong issuer' => $this->token('BROKER_ADMIN', ['iss' => 'someone-else']),
            'no role' => $this->token('BROKER_ADMIN', ['role' => '']),
            'unsigned (alg none)' => rtrim(strtr(base64_encode('{"alg":"none","typ":"JWT"}'), '+/', '-_'), '=').'.'
                .rtrim(strtr(base64_encode(json_encode(['iss' => 'ensuretech-identity', 'token_type' => 'access', 'role' => 'BROKER_ADMIN', 'broker_id' => 1, 'exp' => time() + 600])), '+/', '-_'), '=').'.',
            'HS256 signed with the public key' => JWT::encode(['iss' => 'ensuretech-identity', 'token_type' => 'access', 'role' => 'BROKER_ADMIN', 'broker_id' => 1, 'exp' => time() + 600], self::$keys['public'], 'HS256'),
        ];
        foreach ($cases as $why => $token) {
            $this->withToken($token)->getJson('/api/v1/broker/employers')->assertStatus(401);
            $this->assertNotNull($token, $why);
        }
        Http::assertNothingSent();
    }

    public function test_callers_stay_on_their_own_gateway(): void
    {
        $this->fake();
        $this->as('EMPLOYER_ADMIN')->getJson('/api/v1/broker/employers')->assertStatus(403);
        $this->as('BROKER_ADMIN')->getJson('/api/v1/employer/employer')->assertStatus(403);
        $this->as('SYSTEM')->getJson('/api/v1/broker/employers')->assertStatus(403);       // service tokens do not use the public API
        $this->as('TPA_USER')->getJson('/api/v1/broker/policies')->assertStatus(403);
        $this->as('BROKER_ADMIN', ['broker_id' => null])->getJson('/api/v1/broker/employers')->assertStatus(403);         // no tenant
        $this->as('EMPLOYER_ADMIN', ['employer_id' => null])->getJson('/api/v1/employer/employer')->assertStatus(403);
        Http::assertNothingSent();
    }

    public function test_ops_endpoints_are_closed_unless_the_role_is_configured(): void
    {
        $this->as('BROKER_ADMIN')->getJson('/api/v1/ops/mitigations')->assertStatus(403);
        config(['bff.ops_roles' => ['BFF_OPERATOR']]);
        $this->as('BFF_OPERATOR')->getJson('/api/v1/ops/mitigations')->assertOk()->assertJsonStructure(['mitigations', 'rejected']);
        $this->as('BROKER_ADMIN')->getJson('/api/v1/ops/mitigations')->assertStatus(403);       // configuring a role does not open it to anyone else
        $this->as('BFF_OPERATOR')->getJson('/api/v1/broker/employers')->assertStatus(403);      // and an ops role is not a business gateway user
    }
}
