<?php

namespace App\Http\Controllers;

use App\Bff\Contract;
use App\Bff\Problem;
use App\Bff\Upstream;
use Illuminate\Http\Client\ConnectionException;
use Illuminate\Http\Client\Pool;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;

/**
 * API composition (README s.7: "API composition" is the BFF's job): one call for what a policy screen needs, fetched in parallel. The policy is the
 * primary part: if it is missing or refused, so is the whole answer. The other parts degrade independently and say so, instead of failing the page.
 */
final class OverviewController extends Controller
{
    public function __construct(private Contract $contract, private Upstream $upstream) {}

    public function policy(Request $request, string $id)
    {
        $gw = $request->route()->defaults['_gateway'];
        $shaper = $this->contract->shaper();
        $parts = [
            'policy' => ['policy', "/api/v1/$gw/policies/$id", [], 'policy'],
            'cd_account' => ['billing', "/api/v1/$gw/cd/accounts/$id", [], 'cd_account'],
            'recent_endorsements' => ['read', "/api/v1/$gw/read/jobs/", ['policy_id' => $id, 'page_size' => 5], 'read_job', true],
        ];
        if ($gw === 'broker') {
            $parts['tpa_syncs'] = ['integration', '/api/v1/broker/integration/sync-jobs', ['policyId' => $id, 'size' => 3], 'sync_job', true];
        }

        try {
            $responses = Http::pool(function (Pool $pool) use ($parts, $request) {
                foreach ($parts as $name => [$service, $path, $query]) {
                    $pool->as($name)->withHeaders($this->upstream->headers($request))->baseUrl(rtrim(config("bff.upstreams.$service"), '/'))
                        ->connectTimeout((float) config('bff.timeouts.connect'))->timeout((float) config('bff.timeouts.request'))->acceptJson()->get($path, $query);
                }
            });
        } catch (ConnectionException) {
            return Problem::response(504, 'The service did not answer in time');
        }

        $out = [];
        $degraded = [];
        foreach ($parts as $name => $spec) {
            $r = $responses[$name];
            if ($r instanceof \Throwable || ! method_exists($r, 'status')) {
                $reason = 'unavailable';
            } elseif ($r->successful()) {
                try {
                    $json = $r->json();
                    $out[$name] = isset($spec[4])
                        ? array_map(fn ($row) => $shaper->shape($row, $spec[3]), $json['content'] ?? $json['results'] ?? [])
                        : $shaper->shape($json, $spec[3]);

                    continue;
                } catch (\Throwable $e) {
                    Log::error('bff overview part does not match the contract', ['part' => $name, 'problem' => $e->getMessage()]);
                    $reason = 'unavailable';
                }
            } else {
                $reason = match ($r->status()) { 401 => 'unauthenticated', 403 => 'forbidden', 404 => 'not_found', default => 'unavailable' };
            }
            if ($name === 'policy') {
                return match ($reason) {
                    'not_found' => Problem::response(404, 'Not found'),
                    'forbidden' => Problem::response(403, 'Not allowed'),
                    'unauthenticated' => Problem::response(401, 'Authentication failed'),
                    default => Problem::response(502, 'The service could not complete the request', 'Try again shortly.'),
                };
            }
            $out[$name] = isset($spec[4]) ? [] : null;
            $degraded[] = ['part' => $name, 'reason' => $reason];
        }
        $out['degraded'] = $degraded;

        return response()->json($out);
    }
}
