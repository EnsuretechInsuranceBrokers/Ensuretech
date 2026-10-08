<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Str;

/** Every request gets an id that travels to every service and back to the caller. A client-supplied one is accepted only if it is plainly an id. */
class CorrelationId
{
    public function handle(Request $request, Closure $next)
    {
        $given = (string) $request->header('X-Correlation-Id');
        $id = preg_match('/^[A-Za-z0-9._-]{8,64}$/', $given) ? $given : (string) Str::uuid();
        $request->attributes->set('correlation_id', $id);
        Log::withContext(['correlation_id' => $id]);
        $response = $next($request);
        $response->headers->set('X-Correlation-Id', $id);

        return $response;
    }
}
