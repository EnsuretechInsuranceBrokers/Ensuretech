<?php

namespace App\Bff;

use Firebase\JWT\JWT;
use Firebase\JWT\Key;
use Throwable;

/** RS256 only (the algorithm is pinned, never read from the token), issuer and expiry checked, refresh tokens refused. */
final class JwtVerifier
{
    private ?string $pem = null;

    private function key(): string
    {
        if ($this->pem !== null) {
            return $this->pem;
        }
        $path = config('bff.jwt.public_key_path');
        $pem = $path && is_readable($path) ? file_get_contents($path) : config('bff.jwt.public_key');
        if (! is_string($pem) || trim($pem) === '') {
            throw new UnauthenticatedException('the gateway has no token verification key configured');
        }

        return $this->pem = str_replace('\n', "\n", $pem);
    }

    /** @throws UnauthenticatedException with a reason that is safe to log (never shown to the caller) */
    public function verify(string $token): Principal
    {
        try {
            JWT::$leeway = (int) config('bff.jwt.leeway');
            $claims = (array) JWT::decode($token, new Key($this->key(), 'RS256'));
        } catch (UnauthenticatedException $e) {
            throw $e;
        } catch (Throwable $e) {
            throw new UnauthenticatedException(class_basename($e).': '.$e->getMessage());
        }
        if (($claims['iss'] ?? null) !== config('bff.jwt.issuer')) {
            throw new UnauthenticatedException('wrong issuer');
        }
        if (($claims['token_type'] ?? null) !== 'access') {
            throw new UnauthenticatedException('not an access token');
        }
        if (! is_string($claims['role'] ?? null) || $claims['role'] === '') {
            throw new UnauthenticatedException('no role claim');
        }

        return new Principal(
            (string) ($claims['user_id'] ?? $claims['sub'] ?? ''),
            $claims['role'],
            self::int($claims['broker_id'] ?? null),
            self::int($claims['employer_id'] ?? null),
        );
    }

    private static function int(mixed $v): ?int
    {
        return is_int($v) || (is_string($v) && ctype_digit($v)) ? (int) $v : null;
    }
}
