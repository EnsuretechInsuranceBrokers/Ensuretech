<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

/** Pages after login need a server-side session that holds tokens; the gateway (broker | employer) comes from the signed-in role, never from the URL. */
class PortalAuth
{
    public function handle(Request $request, Closure $next)
    {
        $user = $request->session()->get('portal.user');
        if (! $request->session()->get('portal.access') || ! is_array($user)) {
            return redirect('/login');
        }
        $request->attributes->set('gw', str_starts_with((string) $user['role'], 'BROKER') ? 'broker' : 'employer');

        return $next($request);
    }
}
