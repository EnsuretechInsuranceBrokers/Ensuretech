<?php

namespace App\Http\Middleware;

use App\Bff\JwtVerifier;
use App\Bff\Problem;
use App\Bff\UnauthenticatedException;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;

/** Verifies the bearer token and attaches the Principal. The reason for a refusal goes to the log, never to the caller. */
class Authenticate
{
    public function __construct(private JwtVerifier $jwt) {}

    public function handle(Request $request, Closure $next)
    {
        $token = $request->bearerToken();
        if (! $token || strlen($token) > 8192) {
            return Problem::response(401, 'Authentication required')->header('WWW-Authenticate', 'Bearer');
        }
        try {
            $request->attributes->set('principal', $this->jwt->verify($token));
        } catch (UnauthenticatedException $e) {
            Log::info('bff token refused', ['reason' => $e->getMessage(), 'ip' => $request->ip()]);

            return Problem::response(401, 'Authentication required')->header('WWW-Authenticate', 'Bearer');
        }

        return $next($request);
    }
}
