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

        [$ann] = $this->get($r, '/announcements');

        return $this->view($r, 'dashboard', ['dash' => $dash, 'flags' => $flags, 'jobs' => $jobs['items'] ?? [], 'policies' => $pols['items'] ?? [],
            'announcements' => array_values(array_filter(is_array($ann) && array_is_list($ann) ? $ann : [], fn ($a) => $a['live'] ?? false))]);
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

        $broker = $this->gw($r) === 'broker';

        return $this->view($r, 'policy', ['p' => $p, 'ready' => $ready, 'cd' => $cd,
            'window' => $broker ? $this->get($r, "/policies/$id/enrollment-window")[0] : null,
            'overrides' => $broker ? ($this->get($r, "/policies/$id/enrollment-window/employees")[0] ?: []) : [],
            'windowAudit' => $broker ? ($this->get($r, "/policies/$id/enrollment-window/audit", ['limit' => 10])[0] ?: []) : []]);
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

    // ── "how many employees?": a live grid up to the limit, a spreadsheet above it ─────────────────────────────────
    public function intake(Request $r)
    {
        [$pols] = $this->get($r, '/policies', ['page_size' => 100]);

        return $this->view($r, 'intake', ['policies' => array_values(array_filter($pols['items'] ?? [], fn ($p) => $p['status'] === 'APPROVED_AND_LIVE'))]);
    }

    public function intakePlan(Request $r)
    {
        $data = $r->validate(['policy_id' => 'required|integer|min:1', 'op_type' => 'required|in:ADD,REMOVE,UPDATE', 'employee_count' => 'required|integer|min:1|max:1000000', 'effective_date' => 'nullable|date']);
        $res = $this->api->call($r, 'POST', '/'.$this->gw($r).'/endorsements/intake/plan', array_filter($data, fn ($v) => $v !== null && $v !== ''));
        if ($res['status'] >= 300) {
            return back()->withInput()->with('err', self::problem($res['json'], $res['status']));
        }
        $plan = $res['json'];
        if (($plan['mode'] ?? '') === 'GRID') {
            $r->session()->put('portal.grid.'.$plan['session_id'], ['plan' => $plan, 'effective_date' => $data['effective_date'] ?? null]);

            return redirect('/app/endorse/grid/'.$plan['session_id']);
        }

        return redirect('/app/endorse/file?'.http_build_query(array_filter($data, fn ($v) => $v !== null && $v !== '')));
    }

    public function intakeFile(Request $r)
    {
        $q = $r->validate(['policy_id' => 'required|integer|min:1', 'op_type' => 'required|in:ADD,REMOVE,UPDATE', 'employee_count' => 'required|integer|min:1|max:1000000', 'effective_date' => 'nullable|date']);
        $res = $this->api->call($r, 'POST', '/'.$this->gw($r).'/endorsements/intake/plan', array_filter($q, fn ($v) => $v !== null && $v !== ''));
        if ($res['status'] >= 300 || ($res['json']['mode'] ?? '') !== 'FILE') {
            return redirect('/app/endorse')->with('err', $res['status'] >= 300 ? self::problem($res['json'], $res['status']) : 'That size goes in the grid.');
        }

        return $this->view($r, 'intake_file', ['plan' => $res['json'], 'q' => $q]);
    }

    public function intakeTemplate(Request $r)
    {
        $q = $r->validate(['policy_id' => 'required|integer|min:1', 'op_type' => 'required|in:ADD,REMOVE,UPDATE', 'employee_count' => 'required|integer|min:1|max:1000000', 'effective_date' => 'nullable|date']);

        return $this->api->call($r, 'GET', '/'.$this->gw($r).'/endorsements/intake/template', array_filter($q, fn ($v) => $v !== null && $v !== ''))['response'];
    }

    public function grid(Request $r, string $id)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id), 404);
        $held = $r->session()->get('portal.grid.'.$id);
        if (! $held) {
            return redirect('/app/endorse')->with('err', 'That grid has expired. Start again.');
        }

        return $this->view($r, 'grid', ['id' => $id, 'plan' => $held['plan'], 'eff' => $held['effective_date']]);
    }

    /** One pause in typing: the whole grid goes to the engine, the verdict and the pandas summary come back. */
    public function gridValidate(Request $r, string $id)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id) && $r->session()->has('portal.grid.'.$id), 404);
        $res = $this->api->call($r, 'POST', '/'.$this->gw($r)."/endorsements/gui/sessions/$id/grid/validate", ['op_type' => $r->input('op_type'), 'rows' => $r->input('rows', []), 'families' => $r->boolean('families', true)]);
        $r->session()->save();                                       // the session lock is not held while the next keystroke's check queues up

        return response()->json($res['json'], $res['status']);
    }

    /** Save the grid as it is, and submit it only if it has no errors. */
    public function gridSubmit(Request $r, string $id)
    {
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id) && $r->session()->has('portal.grid.'.$id), 404);
        $gw = '/'.$this->gw($r)."/endorsements/gui/sessions/$id";
        $saved = $this->api->call($r, 'PUT', "$gw/grid", ['op_type' => $r->input('op_type'), 'rows' => $r->input('rows', [])]);
        if ($saved['status'] >= 300) {
            return response()->json($saved['json'], $saved['status']);
        }
        foreach ($saved['json']['rows'] ?? [] as $row) {
            if (collect($row['issues'] ?? [])->contains('severity', 'ERROR')) {
                return response()->json(['detail' => 'Some rows still need fixing.', 'result' => $saved['json']], 422);
            }
        }
        $sub = $this->api->call($r, 'POST', "$gw/submit");
        if ($sub['status'] >= 300) {
            return response()->json($sub['json'], $sub['status']);
        }
        $r->session()->forget('portal.grid.'.$id);
        $job = $sub['json']['jobs'][0]['job_id'] ?? null;

        return response()->json(['redirect' => $job ? "/app/endorsements/$job" : '/app/endorsements']);
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

    public function windowSave(Request $r, int $id)
    {
        $d = $r->validate(['start_date' => 'nullable|date', 'end_date' => 'nullable|date', 'midterm_days' => 'nullable|integer|min:1|max:365', 'days_after_expiry' => 'nullable|integer|min:0|max:365']);
        $body = ['enabled' => $r->boolean('enabled'), 'allow_after_expiry' => $r->boolean('allow_after_expiry')] + array_filter($d, fn ($v) => $v !== null && $v !== '');

        return $this->back($r, "/app/policies/$id", $this->api->call($r, 'PUT', "/broker/policies/$id/enrollment-window", $body), 'Enrollment window saved.');
    }

    public function windowCheck(Request $r, int $id)
    {
        $d = $r->validate(['employee_ref' => 'required|string|max:60', 'date_of_joining' => 'nullable|date', 'as_of' => 'nullable|date']);
        $res = $this->api->call($r, 'POST', "/broker/policies/$id/enrollment-window/evaluate", array_filter($d, fn ($v) => $v !== null && $v !== ''));
        if ($res['status'] >= 300) {
            return redirect("/app/policies/$id")->withInput()->with('err', self::problem($res['json'], $res['status']));
        }

        return redirect("/app/policies/$id")->withInput()->with('window_result', $res['json']);
    }

    public function windowEmployee(Request $r, int $id)
    {
        $d = $r->validate(['employee_ref' => 'required|string|max:60', 'start_date' => 'required|date', 'end_date' => 'required|date', 'close_date' => 'nullable|date']);
        $body = ['employees' => [array_filter($d, fn ($v) => $v !== null && $v !== '')]];

        return $this->back($r, "/app/policies/$id", $this->api->call($r, 'PUT', "/broker/policies/$id/enrollment-window/employees", $body), 'Custom window saved.');
    }

    public function announcements(Request $r)
    {
        [$items] = $this->get($r, '/announcements');
        $employers = $this->gw($r) === 'broker' ? ($this->get($r, '/employers', ['page_size' => 100])[0]['items'] ?? []) : [];

        return $this->view($r, 'announcements', ['items' => is_array($items) && array_is_list($items) ? $items : [], 'employers' => $employers]);
    }

    public function announcementCreate(Request $r)
    {
        $d = $r->validate(['kind' => 'required|in:BANNER,CAROUSEL,NOTICE', 'title' => 'required|string|max:200', 'content' => 'required|string|max:5000', 'link' => 'nullable|url|max:500',
            'starts_on' => 'nullable|date', 'ttl_days' => 'nullable|integer|min:1|max:3650', 'sequence' => 'nullable|integer|min:0|max:10000', 'audience' => 'required|string']);
        $audience = $d['audience'];
        unset($d['audience']);
        $body = array_filter($d, fn ($v) => $v !== null && $v !== '') + ($audience === 'all' ? ['all_employers' => true] : ['all_employers' => false, 'employer_ids' => [(int) $audience]]);

        return $this->back($r, '/app/announcements', $this->api->call($r, 'POST', '/broker/announcements', $body), 'Announcement published.');
    }

    public function announcementRetire(Request $r, int $id)
    {
        return $this->back($r, '/app/announcements', $this->api->call($r, 'DELETE', "/broker/announcements/$id"), 'Announcement retired.');
    }
}
