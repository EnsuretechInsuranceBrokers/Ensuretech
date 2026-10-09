<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

/** Pages after login need a server-side session that holds tokens; the gateway (broker | employer | tpa) comes from the signed-in role, never from the URL. */
class PortalAuth
{
    public function handle(Request $request, Closure $next)
    {
        $user = $request->session()->get('portal.user');
        if (! $request->session()->get('portal.access') || ! is_array($user)) {
            return redirect('/login');
        }
        $role = (string) $user['role'];
        $request->attributes->set('gw', str_starts_with($role, 'BROKER') ? 'broker' : ($role === 'TPA_USER' ? 'tpa' : 'employer'));

        if ($role === 'TPA_USER' && ! $request->is('app/member-ids', 'app/member-ids/*')) {
            return redirect('/app/member-ids');                          // a TPA's people only ever see their own pages
        }

        return $next($request);
    }
}
