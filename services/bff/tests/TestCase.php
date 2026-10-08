<?php

namespace Tests;

use Firebase\JWT\JWT;
use Illuminate\Foundation\Testing\TestCase as BaseTestCase;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\RateLimiter;

abstract class TestCase extends BaseTestCase
{
    protected static ?array $keys = null;

    protected function setUp(): void
    {
        parent::setUp();
        self::$keys ??= self::makeKeys();
        config([
            'bff.jwt.public_key' => self::$keys['public'], 'bff.jwt.public_key_path' => null, 'bff.jwt.issuer' => 'ensuretech-identity',
            'bff.mitigations_path' => null,
            'bff.upstreams' => ['identity' => 'http://identity.test', 'organization' => 'http://org.test', 'policy' => 'http://policy.test',
                'endorsement' => 'http://engine.test', 'billing' => 'http://billing.test', 'integration' => 'http://integration.test', 'read' => 'http://read.test'],
        ]);
        $this->app->forgetInstance(\App\Bff\Mitigations::class);
        $this->app->forgetInstance(\App\Bff\JwtVerifier::class);
        Http::preventStrayRequests();
        foreach (['bff-anonymous', 'bff-tenant', 'bff-user', 'bff-heavy'] as $_) {
            RateLimiter::clear($_);
        }
    }

    /** Replaces (not adds to) the HTTP stubs: with Http::fake the FIRST matching stub wins, which silently ignores later ones in the same test. */
    protected function fake(array|callable $stubs = []): void
    {
        Http::swap(new \Illuminate\Http\Client\Factory);
        Http::preventStrayRequests();
        Http::fake($stubs);
    }

    private static function makeKeys(): array
    {
        $res = openssl_pkey_new(['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA]);
        openssl_pkey_export($res, $private);

        return ['private' => $private, 'public' => openssl_pkey_get_details($res)['key']];
    }

    /** A token as identity-service would issue it (override any claim; null removes it). */
    protected function token(string $role = 'BROKER_ADMIN', array $claims = [], ?string $privateKey = null): string
    {
        $base = ['iss' => 'ensuretech-identity', 'token_type' => 'access', 'sub' => '42', 'user_id' => 42, 'role' => $role,
            'broker_id' => 1, 'employer_id' => str_starts_with($role, 'EMPLOYER') ? 10 : null, 'iat' => time(), 'exp' => time() + 600];

        return JWT::encode(array_filter($claims + $base, fn ($v) => $v !== null), $privateKey ?? self::$keys['private'], 'RS256');
    }

    protected function as(string $role = 'BROKER_ADMIN', array $claims = []): static
    {
        return $this->withToken($this->token($role, $claims));
    }
}
