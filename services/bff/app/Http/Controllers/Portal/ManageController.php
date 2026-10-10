<?php

namespace App\Http\Controllers\Portal;

use App\Bff\Contract;
use App\Frontend\Gateway;
use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;

/**
 * "Settings and tools": a card for every BFF API that has no purpose-built page (config/portal_modules.php). The form on each card is built from the API's own field rules in
 * the contract, and the button calls the same API through {@see Gateway} (so every rule the API enforces still applies). Nothing here can reach an endpoint that is not in
 * the contract, a gateway the person is not signed in to, or a path parameter that does not match the contract's pattern.
 */
class ManageController extends Controller
{
    public function __construct(private readonly Gateway $api, private readonly Contract $contract)
    {
    }

    private function gw(Request $r): string
    {
        return $r->attributes->get('gw');
    }

    /** Endpoints of this module that the signed-in person's gateway offers, with the registry's wording. @return array<string,array> */
    private function endpointsFor(Request $r, ?string $module = null): array
    {
        $gw = $this->gw($r);
        $reg = config('portal_modules.endpoints');
        $out = [];
        foreach ($this->contract->endpoints() as $name => $e) {
            if (! isset($reg[$name]) || ! in_array($gw, $e['gateways'], true) || ($module !== null && $reg[$name][0] !== $module)) {
                continue;
            }
            $out[$name] = $e + ['module' => $reg[$name][0], 'title' => $reg[$name][1], 'help' => $reg[$name][2]];
        }

        return $out;
    }

    public function index(Request $r)
    {
        $mods = [];
        foreach (config('portal_modules.modules') as $id => [$title, $blurb]) {
            $n = count($this->endpointsFor($r, $id));
            if ($n > 0) {
                $mods[] = ['id' => $id, 'title' => $title, 'blurb' => $blurb, 'count' => $n];
            }
        }
        $native = [];
        foreach (config('portal_modules.native') as $name => [$url, $label]) {
            if ($this->contract->endpoints()[$name] ?? false) {
                $native[$url][$label] = true;
            }
        }

        return view('portal.manage_index', ['user' => $r->session()->get('portal.user'), 'gw' => $this->gw($r), 'nonce' => $r->attributes->get('csp_nonce'), 'modules' => $mods, 'pages' => $native]);
    }

    public function module(Request $r, string $module)
    {
        abort_unless(isset(config('portal_modules.modules')[$module]), 404);
        $eps = $this->endpointsFor($r, $module);
        abort_if($eps === [], 404);
        $inputs = config('contract.inputs');
        $cards = [];
        foreach ($eps as $name => $e) {
            $method = $e['methods'][0];
            $spec = isset($e['input']) ? ($inputs[$e['input']] ?? []) : (is_array($e['body'] ?? null) ? $e['body'] : []);
            $fields = [];
            foreach ($spec as $f => $s) {
                if (! is_array($s) || str_starts_with((string) $f, '__')) {
                    continue;
                }
                $fields[] = ['name' => $f, 'label' => self::label($f), 'type' => $s['type'] ?? 'string', 'required' => (bool) ($s['required'] ?? false), 'enum' => $s['enum'] ?? null, 'max' => $s['max'] ?? null, 'min' => $s['min'] ?? null];
            }
            preg_match_all('/\{(\w+)\}/', $e['path'], $m);
            $params = [];
            foreach ($m[1] as $p) {
                $re = $e['where'][$p] ?? null;
                $params[] = ['name' => $p, 'label' => self::label($p), 'picker' => self::picker($e['path'], $p), 'options' => $re && preg_match('/^[\w|-]+$/', $re) && str_contains($re, '|') ? explode('|', $re) : null];
            }
            $cards[] = ['name' => $name, 'title' => $e['title'], 'help' => $e['help'], 'method' => $method, 'download' => ($e['kind'] ?? '') === 'download', 'multipart' => isset($e['multipart']),
                'destructive' => $method === 'DELETE' || (bool) preg_match('/deactivate|reject|unconfirm|clear|remove|delete|close|retire/', $name),
                'write' => $method !== 'GET', 'params' => $params, 'query' => array_map(fn ($q) => ['name' => $q, 'label' => self::label($q)], array_keys($e['query'] ?? [])),
                'fields' => $fields, 'load' => self::counterpart($name, $eps), 'extra' => isset($e['multipart']['fields']) ? array_map(fn ($f) => ['name' => $f, 'label' => self::label($f)], $e['multipart']['fields']) : []];
        }
        $employers = $this->gw($r) === 'broker' ? ($this->api->call($r, 'GET', '/broker/employers', ['page_size' => 100])['json']['items'] ?? []) : [];
        $policies = $this->api->call($r, 'GET', '/'.$this->gw($r).'/policies', ['page_size' => 100])['json']['items'] ?? [];
        [$title, $blurb] = config('portal_modules.modules')[$module];

        return view('portal.manage_module', ['user' => $r->session()->get('portal.user'), 'gw' => $this->gw($r), 'nonce' => $r->attributes->get('csp_nonce'), 'token' => csrf_token(),
            'module' => $module, 'title' => $title, 'blurb' => $blurb, 'cards' => $cards, 'employers' => $employers, 'policies' => $policies]);
    }

    /** The call. JSON in {name, params, query, body} (or a multipart form with the same keys as JSON strings plus the file); JSON out {status, ok, data}. */
    public function call(Request $r): JsonResponse
    {
        $name = (string) $r->input('name');
        $eps = $this->endpointsFor($r);
        abort_unless(isset($eps[$name]), 404);
        $e = $eps[$name];
        abort_if(($e['kind'] ?? '') === 'download', 404);
        $dec = fn ($v) => is_string($v) ? (json_decode($v, true) ?: []) : (is_array($v) ? $v : []);
        $params = $dec($r->input('params')) ?: [];
        $path = $this->path($e, $params);
        $method = $e['methods'][0];
        $data = $method === 'GET' || $method === 'DELETE' ? $this->clean($dec($r->input('query'))) : $this->clean($dec($r->input('body')));
        $file = null;
        if (isset($e['multipart'])) {
            $r->validate(['file' => 'required|file|max:51200']);
            $file = $r->file('file');
            $data = $this->clean($dec($r->input('body')));
            $method = 'POST';
        }
        $res = $this->api->call($r, $method, $path, $data, $file);

        return response()->json(['status' => $res['status'], 'ok' => $res['status'] < 300, 'data' => $res['json']], 200);
    }

    /** Files: the same API, returned as the download it is. */
    public function download(Request $r, string $name)
    {
        $eps = $this->endpointsFor($r);
        abort_unless(isset($eps[$name]) && ($eps[$name]['kind'] ?? '') === 'download', 404);
        $e = $eps[$name];
        $path = $this->path($e, (array) $r->query('p', []));
        $q = $this->clean(array_intersect_key((array) $r->query('q', []), $e['query'] ?? []));

        return $this->api->call($r, 'GET', $path, $q)['response'];
    }

    /** '/{gateway}/employers/12' from the contract path and the person's parameters, each checked against the contract's own pattern. */
    private function path(array $e, array $params): string
    {
        $path = $e['path'];
        preg_match_all('/\{(\w+)\}/', $path, $m);
        foreach ($m[1] as $p) {
            $v = (string) ($params[$p] ?? '');
            abort_unless($v !== '' && preg_match('/^(?:'.($e['where'][$p] ?? '[A-Za-z0-9._-]+').')$/', $v) === 1, 422, "Fill in: ".self::label($p));
            $path = str_replace('{'.$p.'}', rawurlencode($v), $path);
        }

        return ($e['scope'] ?? 'gateway') === 'gateway' ? '/'.$this->gw(request()).'/'.$path : '/'.$path;
    }

    /** Empty form fields are left out, so "fill in only what should change" works. */
    private function clean(array $data): array
    {
        return array_filter($data, fn ($v) => $v !== '' && $v !== null);
    }

    private static function label(string $key): string
    {
        $map = ['id' => 'Number', 'policy_id' => 'Policy', 'employer_id' => 'Employer', 'tpa_id' => 'TPA', 'gstin' => 'GSTIN', 'pan' => 'PAN', 'hrms_type' => 'HR system', 'ref' => 'Employee reference', 'row_key' => 'Row'];

        return $map[$key] ?? ucfirst(str_replace('_', ' ', $key));
    }

    private static function picker(string $path, string $param): ?string
    {
        if ($param === 'policy_id' || ($param === 'id' && preg_match('#^(policies|integration/policies)/#', $path))) {
            return 'policy';
        }
        if ($param === 'id' && str_starts_with($path, 'employers/')) {
            return 'employer';
        }

        return null;
    }

    /** For a "set" card, the card that reads the same thing, so the form can start from what is there now. */
    private static function counterpart(string $name, array $eps): ?string
    {
        foreach ([preg_replace('/_put$/', '', $name), preg_replace('/\.(update|config_put)$/', '.get', $name), preg_replace('/\.(update|config_put)$/', '.configs', $name)] as $try) {
            if ($try !== $name && isset($eps[$try]) && $eps[$try]['methods'][0] === 'GET') {
                return $try;
            }
        }

        return null;
    }
}
