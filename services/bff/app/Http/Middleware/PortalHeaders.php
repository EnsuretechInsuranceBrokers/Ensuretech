<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

/** The portal's own headers: a CSP that allows only our inline-with-nonce script and styles, no framing, no sniffing, never cached. */
class PortalHeaders
{
    public function handle(Request $request, Closure $next)
    {
        $nonce = base64_encode(Str::random(18));
        $request->attributes->set('csp_nonce', $nonce);
        $response = $next($request);
        $response->headers->set('Content-Security-Policy', "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
        $response->headers->set('X-Content-Type-Options', 'nosniff');
        $response->headers->set('X-Frame-Options', 'DENY');
        $response->headers->set('Referrer-Policy', 'same-origin');
        if (! $response->headers->has('Cache-Control') || ! str_contains((string) $response->headers->get('Cache-Control'), 'no-store')) {
            $response->headers->set('Cache-Control', 'no-store');
        }

        return $response;
    }
}
