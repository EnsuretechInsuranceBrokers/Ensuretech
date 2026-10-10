<?php

namespace App\Http\Controllers\Portal;

use App\Frontend\Gateway;
use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Http\UploadedFile;

/**
 * QA timing page: buttons that generate a sheet, upload it through the BFF's own API like the portal does, and time every step until the result is ready
 * (the job's final status, and the error spreadsheet for sheets with failures). Only exists when BFF_QA_TOOLS is on; never in production. Brokers only.
 */
class QaTimingController extends Controller
{
    public const SCENARIOS = ['valid' => 'all rows valid', 'mixed' => 'some rows invalid', 'invalid' => 'all rows invalid'];

    public function __construct(private readonly Gateway $api)
    {
    }

    private function broker(Request $r): void
    {
        abort_unless($r->attributes->get('gw') === 'broker', 404);
    }

    public function page(Request $r)
    {
        $this->broker($r);
        $pol = $this->api->call($r, 'GET', '/broker/policies', ['page_size' => 100])['json']['items'] ?? [];

        return view('portal.qa_timing', ['user' => $r->session()->get('portal.user'), 'gw' => 'broker', 'nonce' => $r->attributes->get('csp_nonce'),
            'policies' => array_values(array_filter($pol, fn ($p) => ($p['status'] ?? '') === 'APPROVED_AND_LIVE')),
            'employers' => collect($this->api->call($r, 'GET', '/broker/employers', ['page_size' => 100])['json']['items'] ?? [])->mapWithKeys(fn ($e) => [$e['id'] => $e['display_name'] ?? $e['legal_name'] ?? ('Employer '.$e['id'])])->all(),
            'effective' => now()->addDays(35)->toDateString(), 'token' => csrf_token()]);
    }

    /** The CSV for a scenario, as a download (to submit the same sheet to another system). */
    public function sheet(Request $r)
    {
        $this->broker($r);
        [$scenario, $rows, $invalid] = $this->params($r);

        return response(self::csv($scenario, $rows, $invalid), 200, ['Content-Type' => 'text/csv', 'Content-Disposition' => 'attachment; filename="qa-'.$scenario.'-'.$rows.'-rows.csv"', 'Cache-Control' => 'no-store']);
    }

    /** Generate the sheet and upload it. Answers with the job id and how long the upload call took. */
    public function run(Request $r): JsonResponse
    {
        $this->broker($r);
        [$scenario, $rows, $invalid] = $this->params($r);
        $d = $r->validate(['policy_id' => 'required|integer|min:1', 'effective_date' => 'required|date']);
        $tmp = tempnam(sys_get_temp_dir(), 'qa');
        file_put_contents($tmp, self::csv($scenario, $rows, $invalid));
        $t = microtime(true);
        try {
            $res = $this->api->call($r, 'POST', '/broker/endorsements/csv/upload', ['policy_id' => $d['policy_id'], 'op_type' => 'ADD', 'effective_date' => $d['effective_date']],
                new UploadedFile($tmp, 'qa-'.$scenario.'-'.$rows.'.csv', 'text/csv', null, true));
        } finally {
            @unlink($tmp);
        }
        $ms = (int) round((microtime(true) - $t) * 1000);
        if ($res['status'] >= 300) {
            return response()->json(['error' => $res['json']['detail'] ?? $res['json']['title'] ?? 'upload refused', 'status' => $res['status']], 422);
        }

        return response()->json(['job_id' => $res['json']['id'], 'upload_ms' => $ms, 'rows' => $rows, 'scenario' => $scenario], 202);
    }

    /** The job as the page needs it to know when to stop waiting. */
    public function job(Request $r, string $id): JsonResponse
    {
        $this->broker($r);
        abort_unless(preg_match('/^[0-9a-f-]{36}$/', $id), 404);
        $j = $this->api->call($r, 'GET', "/broker/endorsements/$id")['json'] ?? [];

        return response()->json(array_intersect_key($j, array_flip(['id', 'status', 'phase', 'category', 'total_rows', 'failed_rows', 'is_terminal', 'total_premium', 'cd_status'])));
    }

    /** Add money to the policy's deposit so a big sheet is not stopped by the deposit check. */
    public function deposit(Request $r): JsonResponse
    {
        $this->broker($r);
        $d = $r->validate(['policy_id' => 'required|integer|min:1', 'amount' => 'required|numeric|min:1|max:100000000000']);
        $res = $this->api->call($r, 'POST', '/broker/cd/accounts/'.$d['policy_id'].'/credits', ['amount' => $d['amount'], 'reference' => 'QA-'.now()->format('YmdHis').'-'.random_int(100, 999), 'type' => 'DEPOSIT']);

        return response()->json(['ok' => $res['status'] < 300, 'status' => $res['status'], 'balance_after' => $res['json']['balance_after'] ?? $res['json']['balanceAfter'] ?? null], $res['status'] < 300 ? 200 : 422);
    }

    /** @return array{0:string,1:int,2:int} */
    private function params(Request $r): array
    {
        $d = $r->validate(['scenario' => 'required|in:valid,mixed,invalid', 'rows' => 'required|integer|min:1|max:50000', 'invalid_rows' => 'nullable|integer|min:0|max:50000']);
        $rows = (int) $d['rows'];
        $invalid = match ($d['scenario']) {'valid' => 0, 'invalid' => $rows, default => min($rows, max(1, (int) ($d['invalid_rows'] ?? 4)))};

        return [$d['scenario'], $rows, $invalid];
    }

    /** Employees with a spouse and a child (3 rows each) up to exactly $rows; $invalid rows are spoiled, spread evenly. Employee codes come from the clock so they are new each time. */
    public static function csv(string $scenario, int $rows, int $invalid): string
    {
        static $last = 0;
        $code = max((int) round((microtime(true) - 1767225600) * 1000) * 100000, $last + 1);
        $out = [];
        while (count($out) < $rows) {
            $code++;
            $pan = self::letters(5).random_int(1000, 9999).self::letters(1);
            $out[] = [$code, 'Self', "Qa Tester $code", '17/05/1985', 'F', 500000, '', $pan];
            if (count($out) < $rows) {
                $out[] = [$code, 'Spouse', "Qa Spouse $code", '02/02/1983', 'M', 500000, '', ''];
            }
            if (count($out) < $rows) {
                $out[] = [$code, 'SON', "Qa Child $code", '01/01/2015', 'M', 500000, '', ''];
            }
        }
        $last = $code;
        if ($invalid > 0) {
            $step = max(1, intdiv($rows, $invalid));
            for ($n = 0, $i = 0; $i < $rows && $n < $invalid; $i += $step, $n++) {
                match ($n % 3) {0 => $out[$i][3] = '99/99/9999', 1 => $out[$i][2] = '', default => $out[$i][1] = 'NEPHEW'};
            }
        }
        $h = fopen('php://temp', 'r+');
        fputcsv($h, ['Emp Code', 'Relationship', 'Member Name', 'DOB', 'Gender', 'Sum Insured', 'Effective Date', 'PAN']);
        foreach ($out as $row) {
            fputcsv($h, $row);
        }
        rewind($h);

        return (string) stream_get_contents($h);
    }

    private static function letters(int $n): string
    {
        $s = '';
        for ($i = 0; $i < $n; $i++) {
            $s .= chr(random_int(65, 90));
        }

        return $s;
    }
}
