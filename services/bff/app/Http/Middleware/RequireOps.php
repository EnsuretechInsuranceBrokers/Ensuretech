<?php

namespace App\Http\Middleware;

use App\Bff\Problem;
use Closure;
use Illuminate\Http\Request;

class RequireOps
{
    public function handle(Request $request, Closure $next)
    {
        $p = $request->attributes->get('principal');

        return $p !== null && $p->isOps() ? $next($request) : Problem::response(403, 'Not allowed');
    }
}
