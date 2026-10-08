<?php

namespace App\Bff;

use Illuminate\Http\Client\PendingRequest;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Http;

/** Builds calls to the services behind the BFF. What is forwarded is decided here, once, from an allowlist: never "whatever the client sent". */
final class Upstream
{
    /** The only caller-supplied headers that travel on. Cookies, Host, X-Forwarded-*, anything custom: all dropped. */
    private const PASS = ['Accept-Language'];

    /** @return array<string,string> */
    public function headers(Request $in, array $extraHeaders = [], bool $forwardToken = true): array
    {
        $headers = ['X-Correlation-Id' => (string) $in->attributes->get('correlation_id'), 'X-Forwarded-For' => (string) $in->ip()];
        if ($forwardToken && ($token = $in->bearerToken())) {
            $headers['Authorization'] = 'Bearer '.$token;          // the services verify it again; the BFF is not their authority
        }
        $headers += self::gatewayHeaders($in);
        foreach (array_merge(self::PASS, $extraHeaders) as $h) {
            if ($v = $in->header($h)) {
                $headers[$h] = is_string($v) ? substr($v, 0, 200) : $v;
            }
        }

        return $headers;
    }

    /**
     * Proof that a call came through this BFF: the shared secret from .env plus a session key, HMAC(secret, session id). The services recompute the
     * key from the secret and refuse anything that does not match, so a request that bypasses the BFF is refused even with a valid user token.
     * @return array<string,string>
     */
    public static function gatewayHeaders(?Request $in = null): array
    {
        $secret = (string) config('bff.gateway_secret');
        $sid = (string) ($in?->attributes->get('session_id') ?: ($in?->bearerToken() ? substr(hash('sha256', $in->bearerToken()), 0, 32) : 'anonymous'));

        return ['X-Gateway-Secret' => $secret, 'X-Session-Id' => $sid, 'X-Session-Key' => hash_hmac('sha256', $sid, $secret)];
    }

    public function client(Request $in, string $service, ?float $timeout = null, array $extraHeaders = [], bool $forwardToken = true): PendingRequest
    {
        return Http::baseUrl(rtrim(config("bff.upstreams.$service"), '/'))
            ->connectTimeout((float) config('bff.timeouts.connect'))
            ->timeout($timeout ?? (float) config('bff.timeouts.request'))
            ->acceptJson()->withHeaders($this->headers($in, $extraHeaders, $forwardToken))->withoutRedirecting();
    }

    /** '/api/v1/{gw}/x/{id}' + params => a concrete, URL-encoded path. */
    public static function path(string $template, string $gateway, array $params): string
    {
        $path = str_replace('{gw}', $gateway, $template);
        foreach ($params as $k => $v) {
            $path = str_replace('{'.$k.'}', rawurlencode((string) $v), $path);
        }

        return $path;
    }

    /** Public query name => upstream name (or [name, add]). Unlisted parameters never reach the service. */
    public static function query(Request $in, array $map): array
    {
        $out = [];
        foreach ($map as $public => $to) {
            $v = $in->query($public);
            if ($v === null || is_array($v)) {
                continue;
            }
            $v = (string) $v;
            if (strlen($v) > 200) {
                continue;
            }
            if (is_array($to)) {
                if (! preg_match('/^-?\d+$/', $v)) {
                    continue;
                }
                $v = (string) max(0, (int) $v + $to[1]);
                $to = $to[0];
            }
            $out[$to] = $v;
        }

        return $out;
    }
}
