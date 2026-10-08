@extends('portal.layout')
@section('title', 'Dashboard')
@section('body')
<h1>Dashboard</h1>
<div class="grid">
  <div class="card"><div class="mut">Endorsement jobs</div><div class="n">{{ $dash['jobs']['total'] ?? '–' }}</div><div class="mut">{{ $dash['jobs']['open'] ?? 0 }} open</div></div>
  <div class="card"><div class="mut">CD accounts</div><div class="n">{{ $dash['cd']['accounts'] ?? '–' }}</div><div class="mut">{{ $dash['cd']['low'] ?? 0 }} below threshold</div></div>
  <div class="card"><div class="mut">Red flags</div><div class="n">{{ $flags['count'] ?? '–' }}</div><div class="mut">pending over {{ $flags['pending_days'] ?? 7 }} days</div></div>
  @if($gw === 'broker')<div class="card"><div class="mut">TPA syncs failed</div><div class="n">{{ $dash['tpa_syncs']['failed'] ?? '–' }}</div></div>@endif
</div>
<div class="card"><h2>Recent endorsements</h2>
<table><tr><th>File</th><th>Op</th><th>Status</th><th>Rows</th><th></th></tr>
@forelse($jobs as $j)<tr><td>{{ $j['source_filename'] }}</td><td>{{ $j['op_type'] }}</td><td><span class="tag">{{ $j['status'] }}</span></td><td>{{ $j['total_rows'] }}</td><td><a href="/app/endorsements/{{ $j['id'] }}">Open</a></td></tr>
@empty<tr><td colspan="5" class="mut">Nothing yet.</td></tr>@endforelse</table></div>
<div class="card"><h2>Policies</h2>
<table><tr><th>Number</th><th>Type</th><th>Status</th><th>Period</th><th></th></tr>
@forelse($policies as $p)<tr><td>{{ $p['policy_number'] }}</td><td>{{ $p['policy_type'] }}</td><td><span class="tag">{{ $p['status'] }}</span></td><td>{{ $p['start_date'] }} → {{ $p['end_date'] }}</td><td><a href="/app/policies/{{ $p['id'] }}">Open</a></td></tr>
@empty<tr><td colspan="5" class="mut">No policies.</td></tr>@endforelse</table></div>
@endsection
