@extends('portal.layout')
@section('title', 'Policies')
@section('body')
<h1>Policies</h1>
<div class="card"><table><tr><th>Number</th><th>Type</th><th>Status</th><th>Period</th><th>Pro-rata</th><th></th></tr>
@forelse(($page['items'] ?? []) as $p)<tr><td>{{ $p['policy_number'] }}</td><td>{{ $p['policy_type'] }}</td><td><span class="tag">{{ $p['status'] }}</span></td><td>{{ $p['start_date'] }} → {{ $p['end_date'] }}</td><td>{{ $p['pro_rata_type'] }}</td><td><a href="/app/policies/{{ $p['id'] }}">Open</a></td></tr>
@empty<tr><td colspan="6" class="mut">No policies.</td></tr>@endforelse</table></div>
@endsection
