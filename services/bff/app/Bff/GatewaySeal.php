<?php

namespace App\Bff;

use Psr\Http\Message\RequestInterface;

/**
 * Proof that a call to a core service came through this BFF. The core services accept nothing else (a super-credential SYSTEM token excepted).
 *
 * Every outgoing request carries a timestamp, a one-time nonce and
 *     HMAC-SHA256(secret, "v1\n" ts "\n" nonce "\n" METHOD "\n" path "\n" query "\n" sha256(Authorization header))
 * so a signature is good for one method, one path, one query and one user token, for a minute. The secret itself never travels; capturing a request
 * does not let anyone send a different one; replaying it is refused (timestamp window plus a nonce cache in each service).
 * The services implement the same function (GatewaySeal.java / config/gateway.py): change them together.
 */
final class GatewaySeal
{
    public const VERSION = 'v1';

    public static function canonical(string $ts, string $nonce, string $method, string $path, string $query, string $authorization): string
    {
        return self::VERSION."\n$ts\n$nonce\n".strtoupper($method)."\n$path\n$query\n".hash('sha256', $authorization);
    }

    public static function signature(string $secret, string $ts, string $nonce, string $method, string $path, string $query, string $authorization): string
    {
        return hash_hmac('sha256', self::canonical($ts, $nonce, $method, $path, $query, $authorization), $secret);
    }

    /** A Guzzle request middleware: signs whatever is about to be sent, so no call site can forget. */
    public static function middleware(): callable
    {
        return function (RequestInterface $r): RequestInterface {
            $secret = (string) config('bff.gateway_secret');
            $ts = (string) time();
            $nonce = bin2hex(random_bytes(12));
            $uri = $r->getUri();

            return $r->withHeader('X-Gateway-Timestamp', $ts)->withHeader('X-Gateway-Nonce', $nonce)
                ->withHeader('X-Gateway-Signature', self::signature($secret, $ts, $nonce, $r->getMethod(), $uri->getPath() === '' ? '/' : $uri->getPath(), $uri->getQuery(), $r->getHeaderLine('Authorization')));
        };
    }

    /** A Guzzle middleware that logs every call to a core service with its status and time, under the request's correlation id (path only: no query, no headers). */
    public static function logging(): callable
    {
        return function (callable $handler): callable {
            return function (RequestInterface $request, array $options) use ($handler) {
                $t0 = microtime(true);

                return $handler($request, $options)->then(function ($response) use ($request, $t0) {
                    $u = $request->getUri();
                    \Illuminate\Support\Facades\Log::info('upstream '.$request->getMethod().' '.$u->getHost().':'.$u->getPort().$u->getPath().' -> '.$response->getStatusCode(), ['ms' => (int) round((microtime(true) - $t0) * 1000)]);

                    return $response;
                });
            };
        };
    }
}
