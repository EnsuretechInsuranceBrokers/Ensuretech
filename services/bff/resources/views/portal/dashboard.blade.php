@extends('portal.layout')
@section('title', 'Dashboard')
@section('body')
<h1>Dashboard</h1><p class="sub">Where things stand today.</p>
@foreach($announcements as $a)
  <div class="banner {{ strtolower($a['kind']) }}">@if($a['image_url'])<img src="{{ $a['image_url'] }}" alt="" loading="lazy" referrerpolicy="no-referrer">@endif
    <div><strong>{{ $a['title'] }}</strong><p>{{ $a['content'] }}</p>@if($a['link'])<a href="{{ $a['link'] }}" rel="noopener noreferrer" target="_blank">Read more</a>@endif</div></div>
@endforeach
<div class="stats">
  <div class="stat"><div class="k">Endorsement jobs</div><div class="v">{{ $dash['jobs']['total'] ?? '–' }}</div><div class="d">{{ $dash['jobs']['open'] ?? 0 }} still open</div></div>
  <div class="stat {{ ($dash['cd']['low'] ?? 0) > 0 ? 'warn' : 'ok' }}"><div class="k">Deposit accounts</div><div class="v">{{ $dash['cd']['accounts'] ?? '–' }}</div><div class="d">{{ $dash['cd']['low'] ?? 0 }} below threshold</div></div>
  <div class="stat {{ ($flags['count'] ?? 0) > 0 ? 'err' : 'ok' }}"><div class="k">Red flags</div><div class="v">{{ $flags['count'] ?? '–' }}</div><div class="d">pending over {{ $flags['pending_days'] ?? 7 }} days</div></div>
  @if($gw === 'broker')<div class="stat {{ ($dash['tpa_syncs']['failed'] ?? 0) > 0 ? 'err' : '' }}"><div class="k">TPA syncs failed</div><div class="v">{{ $dash['tpa_syncs']['failed'] ?? '–' }}</div><div class="d">{{ $dash['tpa_syncs']['completed'] ?? 0 }} completed</div></div>@endif
</div>
<div class="card flush"><h2>Recent endorsements</h2><div class="table-wrap"><table>
  <thead><tr><th>File</th><th>Operation</th><th>Status</th><th class="right">Rows</th><th></th></tr></thead><tbody>
  @forelse($jobs as $j)<tr><td>{{ $j['source_filename'] }}</td><td>{{ $j['op_type'] }}</td><td>@include('portal.pill', ['s' => $j['status']])</td><td class="right num">{{ $j['total_rows'] }}</td><td class="right"><a href="/app/endorsements/{{ $j['id'] }}">Open</a></td></tr>
  @empty<tr><td colspan="5" class="empty">No endorsements yet. <a href="/app/endorsements">Upload a file</a>.</td></tr>@endforelse</tbody></table></div></div>
<div class="card flush"><h2>Policies</h2><div class="table-wrap"><table>
  <thead><tr><th>Number</th><th>Type</th><th>Status</th><th>Period</th><th></th></tr></thead><tbody>
  @forelse($policies as $p)<tr><td><strong>{{ $p['policy_number'] }}</strong></td><td>{{ $p['policy_type'] }}</td><td>@include('portal.pill', ['s' => $p['status']])</td><td class="nowrap">{{ $p['start_date'] }} → {{ $p['end_date'] }}</td><td class="right"><a href="/app/policies/{{ $p['id'] }}">Open</a></td></tr>
  @empty<tr><td colspan="5" class="empty">No policies.</td></tr>@endforelse</tbody></table></div></div>
@endsection
