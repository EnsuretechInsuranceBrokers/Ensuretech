<?php

namespace App\Http\Controllers\Portal;

use App\Frontend\Gateway;
use App\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Http\Response as HttpResponse;

/** Server-rendered pages. Data comes only from the BFF's public API via {@see Gateway}; nothing here talks to a core service. */
class PortalController extends Controller
{
    public function __construct(private readonly Gateway $api)
    {
    }

    private function gw(Request $r): string
    {
        return $r->attributes->get('gw');
    }

    /** @return array{0:mixed,1:int} */
    private function get(Request $r, string $path, array $q = []): array
    {
        $res = $this->api->call($r, 'GET', '/'.$this->gw($r).$path, $q);

        return [$res['json'], $res['status']];
    }

    private function view(Request $r, string $name, array $data = [])
    {
        return view("portal.$name", $data + ['user' => $r->session()->get('portal.user'), 'gw' => $r->attributes->get('gw'), 'nonce' => $r->attributes->get('csp_nonce')]);
    }

    /** A problem+json body as one flash message. */
    private static function problem(?array $json, int $status): string
    {
        $m = $json['detail'] ?? $json['title'] ?? "Request failed ($status)";
        foreach ($json['issues'] ?? [] as $i) {
            $m .= ' · '.$i['message'];
        }
        foreach ($json['errors'] ?? [] as $field => $msgs) {
            if ($field !== 'issues') {
                $m .= " · $field ".implode(', ', (array) $msgs);
            }
        }

        return $m;
    }

    private function back(Request $r, string $to, array $res, string $ok)
    {
        return redirect($to)->with($res['status'] < 300 ? 'ok' : 'err', $res['status'] < 300 ? $ok : self::problem($res['json'], $res['status']));
    }

    // ── session ──────────────────────────────────────────────────────────────────────────────────────────────
    public function loginForm(Request $r)
    {
        return view('portal.login', ['nonce' => $r->attributes->get('csp_nonce')]);
    }

    public function login(Request $r)
    {
        $data = $r->validate(['email' => 'required|string|max:254', 'password' => 'required|string|max:200']);
        $res = $this->api->call($r, 'POST', '/auth/login', $data);
        if ($res['status'] !== 200 || ! isset($res['json']['access_token'])) {
            return back()->withInput($r->only('email'))->with('err', $res['status'] === 429 ? 'Too many attempts. Wait a moment.' : 'Sign-in failed.');
        }
        $r->session()->regenerate();                                  // a new session id on every sign-in: no fixation
        $r->session()->put('portal.access', $res['json']['access_token']);
        $r->session()->put('portal.refresh', $res['json']['refresh_token']);
        $me = $this->api->call($r, 'GET', '/auth/me');
        $role = (string) ($me['json']['role'] ?? '');
        if (! str_starts_with($role, 'BROKER') && ! str_starts_with($role, 'EMPLOYER')) {
            $r->session()->flush();

            return redirect('/login')->with('err', 'This portal is for broker and employer users.');
        }
        $r->session()->put('portal.user', ['email' => $me['json']['email'], 'name' => trim(($me['json']['first_name'] ?? '').' '.($me['json']['last_name'] ?? '')), 'role' => $role]);

        return redirect('/app');
    }

    public function logout(Request $r)
    {
        $refresh = $r->session()->get('portal.refresh');
        if ($refresh) {
            $this->api->call($r, 'POST', '/auth/logout', ['refresh_token' => $refresh]);
        }
        $r->session()->invalidate();
        $r->session()->regenerateToken();

        return redirect('/login');
    }

    // ── pages ────────────────────────────────────────────────────────────────────────────────────────────────
    public function dashboard(Request $r)
    {
        [$dash] = $this->get($r, '/dashboard');
        [$flags] = $this->get($r, '/dashboard/red-flags');
        [$jobs] = $this->get($r, '/endorsements', ['page_size' => 5]);
        [$pols] = $this->get($r, '/policies', ['page_size' => 5]);

        return $this->view($r, 'dashboard', ['dash' => $dash, 'flags' => $flags, 'jobs' => $jobs['items'] ?? [], 'policies' => $pols['items'] ?? []]);
    }

    public function employers(Request $r)
    {
        [$list, $s] = $this->get($r, '/employers', ['page' => (int) $r->query('page', 1), 'page_size' => 25]);

        return $this->view($r, 'employers', ['page' => $list, 'denied' => $s === 404 || $s === 403]);
    }

    public function employerCreate(Request $r)
    {
        $d = $r->validate(['code' => 'required|string|max:40', 'legal_name' => 'required|string|max:200', 'display_name' => 'required|string|max:120']);

        return $this->back($r, '/app/employers', $this->api->call($r, 'POST', '/broker/employers', $d), 'Employer created.');
    }

    public function policies(Request $r)
    {
        [$list] = $this->get($r, '/policies', ['page' => (int) $r->query('page', 1), 'page_size' => 25]);

        return $this->view($r, 'policies', ['page' => $list]);
    }

    public function policy(Request $r, int $id)
    {
        [$p, $s] = $this->get($r, "/policies/$id");
        abort_if($s !== 200, 404);
        $ready = $this->gw($r) === 'broker' ? $this->get($r, "/policies/$id/readiness")[0] : null;
        [$cd] = $this->get($r, "/cd/accounts/$id");

        return $this->view($r, 'policy', ['p' => $p, 'ready' => $ready, 'cd' => $cd]);
    }

    public function policyApprove(Request $r, int $id)
    {
        return $this->back($r, "/app/policies/$id", $this->api->call($r, 'POST', "/broker/policies/$id/approve"), 'Policy approved and live.');
    }

    public function endorsements(Request $r)
    {
        [$list] = $this->get($r, '/endorsements', ['page' => (int) $r->query('page', 1), 'page_size' => 25]);
        [$pols] = $this->get($r, '/policies', ['page_size' => 100]);

        return $this->view($r, 'endorsements', ['page' => $list, 'policies' => array_values(array_filter($pols['items'] ?? [], fn ($p) => $p['status'] === 'APPROVED_AND_LIVE'))]);
    }

    public function upload(Request $r)
    {
        $r->validate(['file' => 'required|file|max:51200', 'policy_id' => 'required|integer|min:1', 'op_type' => 'required|in:ADD,REMOVE,UPDATE', 'effective_date' => 'nullable|date']);
        $res = $this->api->call($r, 'POST', '/'.$this->gw($r).'/endorsements/csv/upload', array_filter($r->only('policy_id', 'op_type', 'effective_date')), $r->file('file'));
        if ($res['status'] >= 300) {
            return back()->with('err', self::problem($res['json'], $res['status']));
        }

        return redirect('/app/endorsements/'.$res['json']['id'])->with('ok', 'File received. Checking it now.');
    }

    public function job(Request $r, string $id)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id), 404);
        [$job, $s] = $this->get($r, "/endorsements/$id");
        abort_if($s !== 200, 404);
        [$errors] = $this->get($r, "/endorsements/$id/errors", ['page_size' => 100]);
        [$audit] = $this->get($r, "/endorsements/$id/audit");

        return $this->view($r, 'job', ['job' => $job, 'rowErrors' => $errors['items'] ?? [], 'audit' => is_array($audit) ? $audit : []]);
    }

    public function jobAction(Request $r, string $id, string $action)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id) && in_array($action, ['proceed', 'cancel', 'retry'], true), 404);

        return $this->back($r, "/app/endorsements/$id", $this->api->call($r, 'POST', '/'.$this->gw($r)."/endorsements/$id/$action"), ucfirst($action).' accepted.');
    }

    /** Live progress for the job page: the BFF's own event stream, passed through (already reshaped by the allowlist). */
    public function progress(Request $r, string $id)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id), 404);
        $res = $this->api->call($r, 'GET', '/'.$this->gw($r)."/endorsements/$id/progress");
        $r->session()->save();                                       // release the session lock for the length of the stream

        return $res['response'];
    }

    public function cd(Request $r)
    {
        [$list] = $this->get($r, '/cd/accounts', ['page_size' => 50]);

        return $this->view($r, 'cd', ['accounts' => $list['items'] ?? (is_array($list) && array_is_list($list) ? $list : [])]);
    }

    public function cdCredit(Request $r, int $policyId)
    {
        $d = $r->validate(['amount' => 'required|numeric|min:0.01', 'reference' => 'required|string|max:80', 'type' => 'required|in:DEPOSIT,ADJUSTMENT']);

        return $this->back($r, '/app/cd', $this->api->call($r, 'POST', "/broker/cd/accounts/$policyId/credits", $d), 'Credit recorded.');
    }

    public function cdReplenish(Request $r, int $policyId)
    {
        $d = $r->validate(['amount' => 'required|numeric|min:0.01', 'reference' => 'required|string|max:80']);

        return $this->back($r, '/app/cd', $this->api->call($r, 'POST', '/'.$this->gw($r)."/cd/accounts/$policyId/replenishments", $d), 'Top-up requested.');
    }

    public function assistant(Request $r)
    {
        $answer = null;
        if ($r->isMethod('post')) {
            $d = $r->validate(['question' => 'required|string|max:1000']);
            $res = $this->api->call($r, 'POST', '/'.$this->gw($r).'/assistant/ask', $d);
            $answer = $res['status'] === 200 ? $res['json'] : ['answer' => self::problem($res['json'], $res['status'])];
        }

        return $this->view($r, 'assistant', ['answer' => $answer, 'question' => $r->input('question')]);
    }
}
