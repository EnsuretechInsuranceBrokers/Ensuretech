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
    public function headers(Request $in, array $extraHeaders = []): array
    {
        $headers = ['X-Correlation-Id' => (string) $in->attributes->get('correlation_id'), 'X-Forwarded-For' => (string) $in->ip()];
        if ($token = $in->bearerToken()) {
            $headers['Authorization'] = 'Bearer '.$token;          // the services verify it again; the BFF is not their authority
        }
        foreach (array_merge(self::PASS, $extraHeaders) as $h) {
            if ($v = $in->header($h)) {
                $headers[$h] = is_string($v) ? substr($v, 0, 200) : $v;
            }
        }

        return $headers;
    }

    public function client(Request $in, string $service, ?float $timeout = null, array $extraHeaders = []): PendingRequest
    {
        return Http::baseUrl(rtrim(config("bff.upstreams.$service"), '/'))
            ->connectTimeout((float) config('bff.timeouts.connect'))
            ->timeout($timeout ?? (float) config('bff.timeouts.request'))
            ->acceptJson()->withHeaders($this->headers($in, $extraHeaders))->withoutRedirecting();
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
