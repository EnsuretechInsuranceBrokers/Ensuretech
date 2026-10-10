@extends('portal.layout')
@section('title', 'Endorsement')
@section('body')
@php $stages = ['Validation' => 'VALIDATION', 'Premium' => 'PREMIUM', 'Deposit' => 'CD', 'Endorsement' => 'EXECUTION']; $order = array_values($stages); $at = array_search($job['phase'], $order, true); @endphp
<div class="row-head"><div><h1>{{ $job['source_filename'] }}</h1><p class="sub" style="margin:0">{{ $job['op_type'] }} · effective {{ $job['effective_date'] }}</p></div><span id="status">@include('portal.pill', ['s' => $job['status']])</span></div>
<div class="card">
  <div class="bar" role="progressbar" aria-valuemin="0" aria-valuemax="100"><i id="bar" style="width:{{ $job['is_terminal'] ? 100 : 0 }}%"></i></div>
  <div class="steps" id="steps">@foreach($stages as $label => $phase)<span data-phase="{{ $phase }}" class="{{ $at !== false && array_search($phase, $order, true) < $at ? 'done' : ($job['phase'] === $phase ? 'on' : '') }}">{{ $label }}</span>@endforeach</div>
  <p class="mut" id="phase" style="margin:.4rem 0 1rem">{{ $job['phase'] }} · {{ $job['category'] }}</p>
  <dl class="kv"><dt>Rows</dt><dd class="num">{{ $job['total_rows'] }} ({{ $job['failed_rows'] }} failed)</dd><dt>Deposit</dt><dd>{{ $job['cd_status'] }}</dd><dt>Premium</dt><dd class="num">{{ $job['total_premium'] }} <span class="mut">(tax {{ $job['tax_amount'] }})</span></dd><dt>Change in premium</dt><dd class="num">{{ $job['premium_delta'] }}</dd></dl>
  <form style="margin-top:1rem;display:flex;gap:.5rem;flex-wrap:wrap" method="post">@csrf
    <button formaction="/app/endorsements/{{ $job['id'] }}/proceed">Proceed</button><button class="alt" formaction="/app/endorsements/{{ $job['id'] }}/retry">Retry</button><button class="danger" formaction="/app/endorsements/{{ $job['id'] }}/cancel">Cancel</button></form></div>
<div class="card flush"><h2>Problems found @if($job['failed_rows'] > 0)<a class="alt" style="font-size:.85rem;font-weight:400;margin-left:.6rem" href="/app/endorsements/{{ $job['id'] }}/errors.xlsx" download>Download as a spreadsheet</a>@endif</h2><div class="table-wrap"><table><thead><tr><th>Line</th><th>Employee</th><th>Field</th><th>Rule</th><th>What is wrong</th></tr></thead><tbody>
  @forelse($rowErrors as $e)<tr><td class="num">{{ $e['line'] }}</td><td class="mono">{{ $e['employee_code'] }}</td><td>{{ $e['field'] }}</td><td class="mono">{{ $e['rule'] }}</td><td>{{ $e['message'] }}</td></tr>
  @empty<tr><td colspan="5" class="empty">Nothing wrong with this file.</td></tr>@endforelse</tbody></table></div></div>
<div class="card flush"><h2>History</h2><div class="table-wrap"><table><thead><tr><th>When</th><th>From</th><th>To</th><th>By</th></tr></thead><tbody>
  @foreach($audit as $a)<tr><td class="nowrap">{{ \Illuminate\Support\Str::of($a['at'])->before('.')->replace('T', ' ') }}</td><td>{{ str_replace('_', ' ', $a['from_status']) }}</td><td>{{ str_replace('_', ' ', $a['to_status']) }}</td><td>{{ $a['actor'] }}</td></tr>@endforeach</tbody></table></div></div>
@unless($job['is_terminal'])
@push('scripts')
<script nonce="{{ $nonce }}">
(function () {
  var es = new EventSource('/app/endorsements/{{ $job['id'] }}/progress'), order = ['VALIDATION', 'PREMIUM', 'CD', 'EXECUTION'];
  function pill(s) { var c = /FAILED|CANCELLED|REJECTED/.test(s) ? 'err' : /^(ENDORSED|CONFIRMED|SUFFICIENT)$/.test(s) ? 'ok' : /INSUFFICIENT|PARTIAL/.test(s) ? 'warn' : /PROGRESS|QUEUED/.test(s) ? 'info' : '';
    var e = document.createElement('span'); e.className = 'pill ' + c; e.textContent = s.replace(/_/g, ' '); return e; }
  es.addEventListener('progress', function (e) {
    var d = JSON.parse(e.data), st = document.getElementById('status'); st.replaceChildren(pill(d.status));
    document.getElementById('bar').style.width = (d.percent || 0) + '%'; document.getElementById('phase').textContent = d.phase + ' · ' + d.category;
    var at = order.indexOf(d.phase); document.querySelectorAll('#steps span').forEach(function (s) { var i = order.indexOf(s.dataset.phase); s.className = i < at ? 'done' : i === at ? 'on' : ''; });
  });
  es.addEventListener('rest', function () { es.close(); }); es.onerror = function () { es.close(); };
})();
</script>
@endpush
@endunless
@endsection
