<?php

namespace App\Http\Middleware;

use App\Bff\Problem;
use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\RateLimiter;

/**
 * Named rate limit (see AppServiceProvider). Not Laravel's `throttle`: that one is sorted ahead of authentication by the framework's middleware
 * priority list, which would key per-tenant and per-user limits by IP because the caller is not known yet.
 */
class Limit
{
    public function handle(Request $request, Closure $next, string $name)
    {
        $limit = (RateLimiter::limiter($name))($request);
        $key = $name.':'.$limit->key;
        if (RateLimiter::tooManyAttempts($key, $limit->maxAttempts)) {
            $wait = RateLimiter::availableIn($key);

            return Problem::response(429, 'Too many requests', 'Slow down and retry shortly.')->withHeaders(['Retry-After' => $wait, 'X-RateLimit-Limit' => $limit->maxAttempts, 'X-RateLimit-Remaining' => 0]);
        }
        RateLimiter::hit($key, $limit->decaySeconds);
        $response = $next($request);
        $response->headers->set('X-RateLimit-Limit', (string) $limit->maxAttempts);

        return $response;
    }
}
