<?php

namespace App\Http\Middleware;

use App\Bff\Problem;
use Closure;
use Illuminate\Http\Request;

/**
 * Keeps callers on their own gateway: broker staff on /broker, HR on /employer, a TPA's own users on /tpa, and nobody else (SYSTEM) on any. A caller without the
 * tenant ids their gateway needs is refused here too; whether they may see a particular record stays the services' decision.
 */
class RequireGateway
{
    public function handle(Request $request, Closure $next, string $gateway)
    {
        $p = $request->attributes->get('principal');
        if ($p === null || ($gateway !== 'any' && $p->gateway() !== $gateway) || $p->gateway() === null) {
            return Problem::response(403, 'Not allowed');
        }
        if ($p->gateway() === 'tpa') {
            // a TPA user belongs to a TPA (and, when a broker onboarded them, to that broker); the integration service narrows it to their policies
            return $p->tpaId === null ? Problem::response(403, 'Not allowed') : $next($request);
        }
        if ($p->brokerId === null || ($p->gateway() === 'employer' && $p->employerId === null)) {
            return Problem::response(403, 'Not allowed');
        }

        return $next($request);
    }
}
