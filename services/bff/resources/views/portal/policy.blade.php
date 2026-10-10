@extends('portal.layout')
@section('title', 'Policy '.$p['policy_number'])
@section('body')
<div class="row-head"><div><h1>Policy {{ $p['policy_number'] }}</h1><p class="sub" style="margin:0">{{ $p['policy_type'] }} · {{ $p['start_date'] }} → {{ $p['end_date'] }}</p></div>@include('portal.pill', ['s' => $p['status']])</div>
<div class="grid-2">
  <div class="card"><h2>Details</h2><dl class="kv"><dt>Type</dt><dd>{{ $p['policy_type'] }} {{ $p['policy_sub_type'] }}</dd><dt>Max members</dt><dd>{{ $p['max_members'] ?? '–' }}</dd><dt>Pro-rata</dt><dd>{{ $p['pro_rata_type'] }}</dd><dt>Insurer</dt><dd>{{ $p['insurer_id'] }}</dd></dl></div>
  @if(isset($cd["balance"]))<div class="card"><h2>Deposit account</h2><dl class="kv"><dt>Balance</dt><dd class="num">{{ number_format($cd['balance'], 2) }}</dd><dt>Reserved</dt><dd class="num">{{ number_format($cd['reserved'], 2) }}</dd><dt>Available</dt><dd class="num">{{ number_format($cd['available'], 2) }}</dd><dt>Threshold</dt><dd class="num">{{ number_format($cd['threshold_amount'], 2) }}</dd></dl></div>@endif
</div>
@if($ready !== null && $p['status'] === 'NOT_APPROVED')
<div class="card" style="margin-top:1rem"><h2>Ready to go live?</h2>
  @if($ready['valid'] ?? false)<p>Everything needed is configured.</p><form method="post" action="/app/policies/{{ $p['id'] }}/approve">@csrf<button>Approve and go live</button></form>
  @else<p class="mut">Fix these first:</p><ul>@foreach(($ready['issues'] ?? []) as $i)<li>{{ $i['message'] }}</li>@endforeach</ul>@endif</div>
@endif

@if($window !== null)
<div class="card" style="margin-top:1rem"><div class="row-head" style="margin-bottom:.5rem"><h2 style="margin:0">Enrollment window</h2>@include('portal.pill', ['s' => ($window['enabled'] ?? false) ? 'OPEN' : 'CLOSED'])</div>
  <p class="mut" style="margin-top:0">When employees may join. After it closes, a mid-term joiner can still join for the days below (see the policy's mid-term basis).</p>
  <form class="form" method="post" action="/app/policies/{{ $p['id'] }}/window">@csrf
    <label class="check wide"><input type="checkbox" name="enabled" value="1" @checked($window['enabled'] ?? false)> The policy has an enrollment window</label>
    <label>Opens<input type="date" name="start_date" value="{{ $window['start_date'] ?? '' }}"></label><label>Closes<input type="date" name="end_date" value="{{ $window['end_date'] ?? '' }}"></label>
    <label>Mid-term days<input type="number" name="midterm_days" min="1" max="365" value="{{ $window['midterm_days'] ?? 30 }}"></label>
    <label class="check"><input type="checkbox" name="allow_after_expiry" value="1" @checked($window['allow_after_expiry'] ?? false)> Allow joining after the policy expires</label>
    <label>Days after expiry<input type="number" name="days_after_expiry" min="0" max="365" value="{{ $window['days_after_expiry'] ?? 0 }}"></label>
    @if($gw === 'broker')<div class="actions"><button>Save window</button></div>@endif
  </form></div>
<div class="grid-2" style="margin-top:1rem">
  <div class="card"><h2>Can this employee join?</h2>
    <form class="form" method="post" action="/app/policies/{{ $p['id'] }}/window/check">@csrf
      <label>Employee code<input name="employee_ref" required maxlength="60" value="{{ old('employee_ref') }}"></label><label>Date of joining<input type="date" name="date_of_joining" value="{{ old('date_of_joining') }}"></label>
      <label>As of (optional)<input type="date" name="as_of"></label><div class="actions"><button class="alt">Check</button></div></form>
    @if(session('window_result'))@php $w = session('window_result'); @endphp
      <dl class="kv" style="margin-top:1rem"><dt>Status</dt><dd>@include('portal.pill', ['s' => $w['status']])</dd><dt>Window</dt><dd>{{ $w['start_date'] ?? '–' }} → {{ $w['end_date'] ?? '–' }}</dd>
        <dt>Custom window</dt><dd>{{ ($w['custom_window_set'] ?? false) ? 'yes' : 'no' }}</dd><dt>Mid-term</dt><dd>{{ ($w['midterm_window_set'] ?? false) ? 'applies' : 'does not apply' }}</dd></dl>@endif</div>
  <div class="card"><h2>Extend for one employee</h2>
    <form class="form" method="post" action="/app/policies/{{ $p['id'] }}/window/employee">@csrf
      <label>Employee code<input name="employee_ref" required maxlength="60"></label><label>From<input type="date" name="start_date" required></label><label>Until<input type="date" name="end_date" required></label>
      <label>Hard close (optional)<input type="date" name="close_date"></label>@if($gw === 'broker')<div class="actions"><button class="alt">Save</button></div>@endif</form>
    <div class="table-wrap" style="margin-top:.8rem"><table><thead><tr><th>Employee</th><th>Window</th><th>Closes</th></tr></thead><tbody>
      @forelse($overrides as $o)<tr><td class="mono">{{ $o['employee_ref'] }}</td><td class="nowrap">{{ $o['start_date'] }} → {{ $o['end_date'] }}</td><td>{{ $o['close_date'] ?? '–' }}</td></tr>@empty<tr><td colspan="3" class="empty">No custom windows.</td></tr>@endforelse</tbody></table></div></div>
</div>
<div class="card flush" style="margin-top:1rem"><h2>Window changes</h2><div class="table-wrap"><table><thead><tr><th>When</th><th>Scope</th><th>Employees</th><th>By</th></tr></thead><tbody>
  @forelse($windowAudit as $a)<tr><td class="nowrap">{{ \Illuminate\Support\Str::of($a['at'])->before('.')->replace('T', ' ') }}</td><td>{{ str_replace('_', ' ', $a['scope']) }}</td><td class="mono">{{ implode(', ', array_slice((array) $a['employees'], 0, 5)) }}{{ count((array) $a['employees']) > 5 ? ' …' : '' }}</td><td class="mono">{{ \Illuminate\Support\Str::limit($a['modified_by'], 12, '') }}</td></tr>
  @empty<tr><td colspan="4" class="empty">No changes yet.</td></tr>@endforelse</tbody></table></div></div>
@endif
@endsection
