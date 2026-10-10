<?php

namespace App\Http\Controllers;

use App\Bff\Mitigations;
use Illuminate\Support\Facades\Http;

/** Production-operations view (README s.10): what the BFF is currently doing to responses, and whether the services behind it answer. Read-only. */
final class OpsController extends Controller
{
    public function mitigations(Mitigations $m)
    {
        return response()->json($m->report());
    }

    public function upstreams()
    {
        $out = [];
        foreach (config('bff.upstreams') as $name => $url) {
            $t = microtime(true);
            try {
                $r = Http::timeout(2)->connectTimeout(1)->withoutRedirecting()->get(rtrim($url, '/').'/');
                $out[$name] = ['reachable' => true, 'http_status' => $r->status(), 'ms' => (int) ((microtime(true) - $t) * 1000)];
            } catch (\Throwable) {
                $out[$name] = ['reachable' => false, 'http_status' => null, 'ms' => (int) ((microtime(true) - $t) * 1000)];
            }
        }

        return response()->json($out);
    }
}
