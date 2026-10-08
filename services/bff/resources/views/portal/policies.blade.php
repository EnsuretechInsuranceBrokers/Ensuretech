@extends('portal.layout')
@section('title', 'Policies')
@section('body')
<h1>Policies</h1><p class="sub">Group policies and their current state.</p>
<div class="card flush"><div class="table-wrap"><table><thead><tr><th>Number</th><th>Type</th><th>Status</th><th>Period</th><th>Pro-rata</th><th></th></tr></thead><tbody>
  @forelse(($page['items'] ?? []) as $p)<tr><td><strong>{{ $p['policy_number'] }}</strong></td><td>{{ $p['policy_type'] }}</td><td>@include('portal.pill', ['s' => $p['status']])</td><td class="nowrap">{{ $p['start_date'] }} → {{ $p['end_date'] }}</td><td>{{ $p['pro_rata_type'] }}</td><td class="right"><a href="/app/policies/{{ $p['id'] }}">Open</a></td></tr>
  @empty<tr><td colspan="6" class="empty">No policies.</td></tr>@endforelse</tbody></table></div></div>
@endsection
