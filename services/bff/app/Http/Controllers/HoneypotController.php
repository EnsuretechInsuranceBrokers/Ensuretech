<?php

namespace App\Http\Controllers;

use App\Bff\Problem;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Log;

/**
 * Endpoints that exist only to be found. No legitimate client has any reason to call them, so any hit is a probe: it is logged to the security
 * channel (the silent alert) and answered exactly like any other unknown path, so the caller learns nothing.
 */
final class HoneypotController extends Controller
{
    public function __invoke(Request $request)
    {
        Log::channel('security')->critical('honeypot touched', [
            'path' => $request->path(), 'method' => $request->method(), 'ip' => $request->ip(), 'user_agent' => substr((string) $request->userAgent(), 0, 200),
            'has_token' => $request->bearerToken() !== null, 'correlation_id' => $request->attributes->get('correlation_id'),
        ]);

        return Problem::response(404, 'Not found');
    }
}
